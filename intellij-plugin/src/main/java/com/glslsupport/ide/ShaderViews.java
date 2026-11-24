package com.glslsupport.ide;

import com.glslsupport.bridge.*;
import com.glslsupport.lang.*;
import com.intellij.openapi.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.wm.*;
import com.intellij.psi.*;
import com.intellij.ui.*;
import com.intellij.ui.components.*;
import com.intellij.ui.content.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import org.jetbrains.annotations.NotNull;

public final class ShaderViews implements ToolWindowFactory {
    public void createToolWindowContent(@NotNull Project p, @NotNull ToolWindow w) {}
    public abstract static class Open extends DumbAwareAction {
        final NativeCompiler.Operation op;
        Open(NativeCompiler.Operation op) {
            this.op = op;
        }
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(
                e.getData(CommonDataKeys.PSI_FILE) instanceof GlslLanguage.File);
        }
        public void actionPerformed(@NotNull AnActionEvent e) {
            var f = e.getData(CommonDataKeys.PSI_FILE);
            var editor = e.getData(CommonDataKeys.EDITOR);
            if (f != null)
                open(f, editor == null ? 0 : editor.getCaretModel().getOffset(), op);
        }
    }
    public static final class Spirv extends Open {
        public Spirv() {
            super(NativeCompiler.Operation.SPIRV);
        }
    }
    public static final class Layout extends Open {
        public Layout() {
            super(NativeCompiler.Operation.LAYOUT);
        }
    }
    static void open(PsiFile f, int offset, NativeCompiler.Operation op) {
        var p = f.getProject();
        var w = ToolWindowManager.getInstance(p).getToolWindow("GLSL Inspector");
        if (w == null || f.getVirtualFile() == null)
            return;
        var manager = w.getContentManager();
        for (var existing : manager.getContents()) {
            if (existing.getComponent() instanceof Panel panel && panel.op == op && panel.file.equals(f.getVirtualFile())) {
                manager.setSelectedContent(existing);
                w.activate(null);
                if (op == NativeCompiler.Operation.LAYOUT) {
                    panel.offset = offset;
                    panel.blocks.setSelectedItem(null);
                    panel.refresh();
                }
                return;
            }
        }
        var panel = new Panel(p, f.getVirtualFile(), offset, op);
        var content = ContentFactory.getInstance().createContent(
            panel, (op == NativeCompiler.Operation.SPIRV ? "SPIR-V · " : "Layout · ") + f.getName(), false);
        content.setDescription(f.getVirtualFile().getPresentableUrl());
        content.setDisposer(panel);
        manager.addContent(content);
        manager.setSelectedContent(content);
        w.activate(null);
        panel.refresh();
    }
    record Selected(String struct, String block) {}
    static Selected selection(PsiFile file, int offset, Workspace graph) {
        String key = ProjectAccess.key(file);
        var syntax = graph.files.get(key);
        for (var s : syntax.symbols)
            if (s.start() <= offset && offset < s.end()) {
                if (s.kind() == Syntax.Kind.TYPE)
                    return new Selected(s.name(), null);
                if (s.kind() == Syntax.Kind.BLOCK)
                    return new Selected(null, s.name());
            }
        var types = new LinkedHashSet<String>();
        for (var hit : graph.resolve(key, offset)) {
            types.add(hit.symbol().type());
            types.add(hit.symbol().owner());
        }
        types.add(syntax.word(syntax.indexAt(offset)));
        for (String type : types)
            for (var path : graph.visible(key))
                for (var s : graph.files.get(path).named(type)) {
                    if (s.kind() == Syntax.Kind.TYPE)
                        return new Selected(type, null);
                    if (s.kind() == Syntax.Kind.BLOCK)
                        return new Selected(null, type);
                }
        return new Selected(null, null);
    }
    public static NativeCompiler.Request preview(NativeCompiler.Request req, String struct, String packing) {
        if (struct == null)
            return req;
        var base = req.units().getFirst();
        var old = base.config();
        String text = base.text();
        var versionPattern =
            java.util.regex.Pattern.compile("#\\s*version\\s+(\\d+)(?:\\s+(core|compatibility|es))?.*");
        var token =
            Lexer.tokens(text)
                .stream()
                .filter(t -> t.kind() == Lexer.Kind.DIRECTIVE && versionPattern.matcher(t.text()).matches())
                .findFirst();
        int version = old.version();
        String profile = old.profile();
        if (version == 0 && token.isPresent()) {
            var m = versionPattern.matcher(token.get().text());
            if (m.matches()) {
                version = Integer.parseInt(m.group(1));
                profile = Objects.requireNonNullElse(m.group(2), "");
            }
        }
        if (packing.equals("scalar")) {
            int at = token.map(Lexer.Token::end).orElse(0);
            text =
                text.substring(0, at) + "\n#extension GL_EXT_scalar_block_layout : require\n" + text.substring(at);
        }
        text += "\nlayout(" + packing + ", binding=0) " + (packing.equals("std430") ? "buffer" : "uniform")
            + " GlslSupportPreview { " + struct + " value; } glslSupportPreview;\n";
        boolean es = version == 100 || profile.equals("es");
        int minimum = es ? 310 : packing.equals("std140") ? 420 : 430;
        var cfg = version >= minimum ? old
                                     : new ShaderConfig.Effective(old.stage(), old.vulkan(), es ? 310 : 450,
                                           es ? "es"
                                               : version > 0 && version < 150 ? "compatibility"
                                                                              : profile,
                                           old.defines());
        return new NativeCompiler.Request(req.vulkan(), req.operation(),
            List.of(new NativeCompiler.Unit(base.path(), text, cfg)), req.headers(), req.includeRoots());
    }
    record CompileInput(NativeCompiler.Request request, Selected selected, String label, String variant, Set<String> paths) {}
    record Output(NativeCompiler.Result result, CompileInput input, long revision,
                  NativeCompiler.SourceMap sourceMap, List<BlockLayout.Block> layout) {
        static Output prepare(NativeCompiler.Result result, CompileInput input, long revision) {
            boolean spirv = input.request.operation() == NativeCompiler.Operation.SPIRV;
            var lines = result.success() && spirv ? result.sourceMap() : null;
            var layout = result.success() && !spirv ? BlockLayout.parse(result.layout()).stream()
                .filter(b -> input.selected.struct == null || b.name().equals("GlslSupportPreview")).toList()
                : List.<BlockLayout.Block>of();
            return new Output(result, input, revision, lines, layout);
        }
    }
    static final class Panel extends JPanel implements Disposable {
        final Project project;
        final VirtualFile file;
        int offset;
        final NativeCompiler.Operation op;
        final AtomicLong revision = new AtomicLong();
        volatile Set<String> deps;
        volatile boolean disposed;
        final JLabel status = new JBLabel();
        final JButton export = new JButton("Export SPIR-V…");
        volatile Output displayed;
        final JComboBox<String> packing = new JComboBox<>(new String[] {"std140", "std430", "scalar"}),
                                blocks = new JComboBox<>();
        final JTextArea assembly = new JTextArea(), errors = new JTextArea();
        final JPanel pages = new JPanel(new CardLayout());
        final LayoutTable table = new LayoutTable();
        int highlighted = -1;
        List<BlockLayout.Block> layout = List.of();
        boolean installing;
        ScheduledFuture<?> pending;
        Panel(Project p, VirtualFile file, int offset, NativeCompiler.Operation op) {
            super(new BorderLayout(6, 6));
            project = p;
            this.file = file;
            this.offset = offset;
            this.op = op;
            deps = Set.of(file.getPath());
            var bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 2));
            var refresh = new JButton("Refresh");
            refresh.addActionListener(e -> refresh());
            bar.add(refresh);
            if (op == NativeCompiler.Operation.SPIRV) {
                export.setEnabled(false);
                export.addActionListener(e -> export());
                bar.add(export);
            }
            if (op == NativeCompiler.Operation.LAYOUT) {
                bar.add(blocks);
                bar.add(packing);
                packing.addActionListener(e -> {
                    if (!installing)
                        refresh();
                });
                blocks.addActionListener(e -> selectBlock());
            }
            bar.add(status);
            add(bar, BorderLayout.NORTH);
            assembly.setEditable(false);
            assembly.setFont(new Font(Font.MONOSPACED, Font.PLAIN, EditorColors.fontSize()));
            assembly.getAccessibleContext().setAccessibleName("SPIR-V disassembly");
            errors.setEditable(false);
            errors.setLineWrap(true);
            errors.setWrapStyleWord(true);
            pages.add(new JBScrollPane(assembly), "assembly");
            pages.add(table, "layout");
            pages.add(new JBScrollPane(errors), "errors");
            add(pages);
            ShaderChanges.watch(p, this, () -> deps, true, this::refresh);
            if (op == NativeCompiler.Operation.SPIRV) installSourceNavigation();
        }
        private void installSourceNavigation() {
            assembly.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    var current = displayed;
                    if (current == null) return;
                    try {
                        int line = assembly.getLineOfOffset(assembly.viewToModel2D(e.getPoint()));
                        var loc = current.sourceMap.instructions().get(line);
                        if (loc != null)
                            navigate(loc);
                    } catch (BadLocationException ignored) {
                    }
                }
            });
            EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
                public void caretPositionChanged(@NotNull CaretEvent e) {
                    var current = displayed;
                    if (current == null || e.getEditor().getProject() != project) return;
                    var v = FileDocumentManager.getInstance().getFile(e.getEditor().getDocument());
                    if (v != null && current.input.paths.contains(v.getPath())) {
                        var line = new NativeCompiler.Line(v.getPath(), e.getNewPosition().line + 1);
                        highlight(current.sourceMap.firstInstruction().getOrDefault(line, -1));
                    }
                }
            }, this);
        }
        void highlight(int line) {
            if (highlighted == line) return;
            highlighted = line;
            assembly.getHighlighter().removeAllHighlights();
            if (line < 0) return;
            try {
                int a = assembly.getLineStartOffset(line), b = assembly.getLineEndOffset(line);
                assembly.getHighlighter().addHighlight(a, b,
                    new DefaultHighlighter.DefaultHighlightPainter(
                        JBColor.namedColor("SearchMatch.startBackground", new JBColor(0xDBEAFE, 0x344660))));
                var bounds = assembly.modelToView2D(a);
                if (bounds != null) assembly.scrollRectToVisible(bounds.getBounds());
            } catch (BadLocationException ignored) {
            }
        }
        void navigate(NativeCompiler.Line loc) {
            VirtualFile v = LocalFileSystem.getInstance().findFileByPath(loc.file());
            if (v != null)
                new OpenFileDescriptor(project, v, Math.max(0, loc.line() - 1), 0).navigate(true);
        }
        void refresh() {
            if (!SwingUtilities.isEventDispatchThread()) {
                revision.incrementAndGet();
                ApplicationManager.getApplication().invokeLater(this::refresh);
                return;
            }
            if (disposed || project.isDisposed())
                return;
            long ticket = revision.incrementAndGet();
            displayed = null;
            highlight(-1);
            export.setEnabled(false);
            if (pending != null)
                pending.cancel(false);
            status.setText("Compiling…");
            String requested = (String) packing.getSelectedItem();
            int caretOffset = offset;
            PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(() -> {
                if (disposed || revision.get() != ticket)
                    return;
                pending = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                    try {
                        var data = ReadAction.computeBlocking(() -> {
                            if (disposed || project.isDisposed() || revision.get() != ticket)
                                return null;
                            var f = file.isValid() ? PsiManager.getInstance(project).findFile(file) : null;
                            if (f == null)
                                throw new IllegalStateException("Shader file is unavailable");
                            var context = PipelineContexts.of(project).current(f);
                            var req = context.request(op);
                            var selected = op == NativeCompiler.Operation.LAYOUT ? selection(f, caretOffset, context.graph())
                                                                                 : new Selected(null, null);
                            return new CompileInput(preview(req, selected.struct, requested), selected,
                                context.label(), context.variantName(), context.graph().inputPaths());
                        });
                        if (data == null || revision.get() != ticket) return;
                        var req = data.request;
                        if (revision.get() == ticket) deps = data.paths();
                        var r = CompilerSupport.of(project).analyze(req);
                        if (revision.get() != ticket) return;
                        var output = Output.prepare(r, data, ticket);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!disposed && revision.get() == ticket)
                                show(output);
                        });
                    } catch (RuntimeException | LinkageError e) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!disposed && revision.get() == ticket)
                                failure(e.getMessage());
                        });
                    }
                }, 180, TimeUnit.MILLISECONDS);
            });
        }
        void failure(String message) {
            status.setText("Compilation failed");
            errors.setText(message);
            ((CardLayout) pages.getLayout()).show(pages, "errors");
        }
        void show(Output out) {
            deps = out.input.paths;
            if (!out.result.success()) {
                failure(out.result.log());
                return;
            }
            String struct = out.input.selected.struct;
            status.setText(SettingsUI.target(out.input.request.vulkan()) + " · " + out.input.label
                + (struct == null ? "" : " · " + struct + " preview"));
            if (op == NativeCompiler.Operation.SPIRV) {
                displayed = out;
                export.setEnabled(out.result.words().length > 0);
                assembly.setText(out.result.assembly());
                assembly.setCaretPosition(0);
                ((CardLayout) pages.getLayout()).show(pages, "assembly");
                return;
            }
            installing = true;
            try {
                packing.setVisible(struct != null);
                layout = out.layout;
                Object selected = blocks.getSelectedItem();
                if (selected == null)
                    selected = out.input.selected.block;
                blocks.removeAllItems();
                layout.forEach(b -> blocks.addItem(b.name().equals("GlslSupportPreview") ? struct : b.name()));
                if (selected != null)
                    for (int i = 0; i < blocks.getItemCount(); i++)
                        if (selected.equals(blocks.getItemAt(i)))
                            blocks.setSelectedIndex(i);
            } finally {
                installing = false;
            }
            if (layout.isEmpty()) {
                status.setText("No storage block at this location");
                errors.setText("Place the caret on a struct to preview its layout, or open a shader containing a "
                    + "uniform, storage, or push-constant block.");
                ((CardLayout) pages.getLayout()).show(pages, "errors");
            } else {
                selectBlock();
                ((CardLayout) pages.getLayout()).show(pages, "layout");
            }
        }
        void selectBlock() {
            int i = blocks.getSelectedIndex();
            if (!installing && i >= 0 && i < layout.size())
                table.display(layout.get(i));
        }
        private boolean current(Output out) {
            if (disposed || project.isDisposed() || revision.get() != out.revision || displayed != out) return false;
            return ReadAction.computeBlocking(() -> {
                if (disposed || project.isDisposed() || !file.isValid()) return false;
                var f = PsiManager.getInstance(project).findFile(file);
                if (f == null) return false;
                var ctx = PipelineContexts.of(project).current(f);
                return ctx.label().equals(out.input.label) && ctx.request(op).equals(out.input.request)
                    && revision.get() == out.revision;
            });
        }
        private void export() {
            var out = displayed;
            if (out == null) return;
            var root = java.nio.file.Path.of(out.input.request.units().getFirst().path());
            var chooser = new JFileChooser(root.getParent().toFile());
            chooser.setDialogTitle("Export validated SPIR-V");
            chooser.setSelectedFile(root.resolveSibling(root.getFileName() + "." +
                out.input.variant.replaceAll("[^\\p{L}\\p{N}._-]", "_") + ".spv").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            var chosen = chooser.getSelectedFile().toPath();
            var target = chosen.toString().toLowerCase(Locale.ROOT).endsWith(".spv")
                ? chosen : chosen.resolveSibling(chosen.getFileName() + ".spv");
            boolean overwrite = java.nio.file.Files.exists(target);
            if (overwrite && com.intellij.openapi.ui.Messages.showYesNoDialog(project,
                "Replace " + target + "?", "Export SPIR-V", null) != com.intellij.openapi.ui.Messages.YES) return;
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    SpirvExport.write(target, out.result, overwrite, () -> current(out));
                    LocalFileSystem.getInstance().refreshIoFiles(List.of(target.toFile()), true, false, null);
                } catch (java.io.IOException | RuntimeException ex) {
                    ApplicationManager.getApplication().invokeLater(() -> com.intellij.openapi.ui.Messages.showErrorDialog(
                        project, ex.getMessage(), "Export SPIR-V"), project.getDisposed());
                }
            });
        }
        public void dispose() {
            disposed = true;
            revision.incrementAndGet();
            if (pending != null)
                pending.cancel(false);
        }
    }
    static final class EditorColors {
        static int fontSize() {
            return com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
                .getGlobalScheme()
                .getEditorFontSize();
        }
    }
    static final class LayoutTable extends JPanel {
        final JLabel info = new JBLabel();
        final ByteMap bytes = new ByteMap();
        final DefaultTableModel rows =
            new DefaultTableModel(new Object[] {"Member", "Offset", "Size", "Align", "Array stride",
                                      "Matrix stride", "Order", "Padding"},
                0) {
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
        final JTable table = new com.intellij.ui.table.JBTable(rows);
        BlockLayout.Block block;
        final Set<String> hidden = new HashSet<>();
        LayoutTable() {
            super(new BorderLayout(4, 4));
            var top = new JPanel(new BorderLayout());
            top.add(info, BorderLayout.NORTH);
            top.add(bytes);
            add(top, BorderLayout.NORTH);
            add(new JBScrollPane(table));
            table.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent event) {
                    int i = table.rowAtPoint(event.getPoint());
                    if (event.getClickCount() == 2 && i >= 0) {
                        String p = rows.getValueAt(i, 0).toString();
                        if (!hidden.add(p))
                            hidden.remove(p);
                        render();
                    }
                }
            });
        }
        void display(BlockLayout.Block b) {
            block = b;
            hidden.clear();
            bytes.block = b;
            bytes.repaint();
            info.setText(b.packing().equals("driver-dependent") ? "OpenGL driver determines member offsets"
                                                                : b.packing() + " · " + b.size() + " bytes"
                        + (b.members().stream().anyMatch(m -> m.size() < 0) ? " before runtime array" : "")
                        + " · alignment " + b.alignment());
            render();
        }
        void render() {
            rows.setRowCount(0);
            for (var m : block.members())
                if (hidden.stream().noneMatch(p -> m.path().startsWith(p + ".") || m.path().startsWith(p + "[")))
                    rows.addRow(new Object[] {m.path(), m.offset(), m.size() < 0 ? "runtime" : m.size(),
                        m.alignment(), m.arrayStride() == 0 ? "" : m.arrayStride(),
                        m.matrixStride() == 0 ? "" : m.matrixStride(), m.order(), m.padding()});
        }
    }
    static final class ByteMap extends JComponent {
        BlockLayout.Block block;
        ByteMap() {
            setPreferredSize(new Dimension(300, 22));
            setToolTipText("Occupied bytes are colored. Empty space is padding.");
        }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (block == null || block.size() <= 0)
                return;
            int width = getWidth();
            g.setColor(JBColor.border());
            g.fillRect(0, 2, width, 16);
            int i = 0;
            for (var m : block.members()) {
                if (m.path().contains(".") || m.size() < 1)
                    continue;
                g.setColor(Color.getHSBColor((i++ * .161f + .48f) % 1, .48f, .76f));
                int x = (int) ((long) m.offset() * width / block.size());
                g.fillRect(x, 2, Math.max(1, (int) ((long) m.size() * width / block.size())), 16);
            }
        }
    }
}
