package com.glslsupport.ide;

import com.glslsupport.bridge.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.table.*;
import org.jetbrains.annotations.NotNull;

public final class TraceInspector extends JPanel implements Disposable {
    public static final class Open extends DumbAwareAction {
        public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.PSI_FILE) instanceof GlslLanguage.File);
        }
        public void actionPerformed(@NotNull AnActionEvent e) {
            var file = e.getData(CommonDataKeys.PSI_FILE);
            if (file == null || file.getVirtualFile() == null) return;
            var window = ToolWindowManager.getInstance(file.getProject()).getToolWindow("GLSL Inspector");
            if (window == null) return;
            var panel = new TraceInspector(file.getProject(), file.getVirtualFile());
            var manager = window.getContentManager();
            var content = ContentFactory.getInstance().createContent(panel, "Trace · " + file.getName(), false);
            content.setDisposer(panel);
            manager.addContent(content);
            manager.setSelectedContent(content);
            window.activate(null);
            panel.refresh();
        }
    }
    private record Variant(String id, String name) { public String toString() { return name; } }
    private record Side(NativeCompiler.Request request, Map<String,String> variant, Map<String,String> pipeline,
                        Set<String> paths) {
        Side(PipelineContexts.Context c) {
            this(c.request(NativeCompiler.Operation.TRACE), c.variantMacros(), c.pipelineMacros(), c.graph().inputPaths());
        }
    }
    private record Inputs(String context, List<Variant> choices, Variant left, Variant right, Side a, Side b) {}
    private record Trace(Map<PreprocessorTrace.Site,PreprocessorTrace.Entry> entries, boolean success, String log) {}
    private final Project project;
    private final VirtualFile file;
    private final AtomicLong generation = new AtomicLong();
    private final JLabel status = new JLabel();
    private final JComboBox<Variant> left = new JComboBox<>(), right = new JComboBox<>();
    private final JTextArea log = new JTextArea(3, 40);
    private final DefaultTableModel model = new DefaultTableModel() {
        public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JBTable table = new JBTable(model);
    private List<PreprocessorTrace.Row> rows = List.of();
    private volatile Set<String> deps = Set.of();
    private boolean installing;
    private volatile boolean disposed;
    private ScheduledFuture<?> pending;
    TraceInspector(Project project, VirtualFile file) {
        super(new BorderLayout(4, 4));
        this.project = project;
        this.file = file;
        deps = Set.of(file.getPath());
        var bar = new JPanel(new FlowLayout(FlowLayout.LEADING));
        bar.add(new JLabel("Variant"));
        bar.add(left);
        bar.add(new JLabel("Compare with"));
        bar.add(right);
        var refresh = new JButton("Refresh");
        bar.add(refresh);
        bar.add(status);
        add(bar, BorderLayout.NORTH);
        add(new JBScrollPane(table));
        log.setEditable(false);
        var errors = new JBScrollPane(log);
        errors.setVisible(false);
        add(errors, BorderLayout.SOUTH);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                            int row, int col) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col);
                setToolTipText(Objects.toString(value, ""));
                if (model.getColumnCount() == 4 && row < rows.size() && rows.get(row).changed())
                    setFont(getFont().deriveFont(Font.BOLD));
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && row >= 0) {
                    var site = rows.get(row).site();
                    var v = site.line() > 0 ? LocalFileSystem.getInstance().findFileByPath(site.file()) : null;
                    if (v != null) new OpenFileDescriptor(project, v, site.line() - 1, 0).navigate(true);
                }
            }
        });
        left.addActionListener(e -> { if (!installing) refresh(); });
        right.addActionListener(e -> { if (!installing) refresh(); });
        refresh.addActionListener(e -> refresh());
        ShaderChanges.watch(project, this, () -> deps, true, this::refresh);
    }
    private Inputs inputs(String leftId, String rightId) {
        var psi = file.isValid() ? PsiManager.getInstance(project).findFile(file) : null;
        if (psi == null) throw new IllegalStateException("Shader is unavailable");
        var contexts = PipelineContexts.of(project);
        var active = contexts.current(psi);
        var choices = Settings.of(project).variants(active.root()).stream().map(v -> new Variant(v.id, v.name)).toList();
        var a = choices.stream().filter(v -> v.id().equals(leftId)).findFirst().orElse(null);
        var b = choices.stream().filter(v -> v.id().equals(rightId)).findFirst().orElse(null);
        return new Inputs(active.label(), choices, a, b,
            new Side(a == null ? active : contexts.withVariant(active, a.id())),
            b == null ? null : new Side(contexts.withVariant(active, b.id())));
    }
    private Trace analyze(Side side) {
        var r = CompilerSupport.of(project).analyze(side.request());
        return new Trace(PreprocessorTrace.entries(side.request(), r, side.variant(), side.pipeline()), r.success(), r.log());
    }
    private void refresh() {
        long ticket = generation.incrementAndGet();
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::refresh, project.getDisposed());
            return;
        }
        if (disposed || project.isDisposed()) return;
        if (pending != null) pending.cancel(false);
        var a = (Variant) left.getSelectedItem();
        var b = (Variant) right.getSelectedItem();
        status.setText("Tracing…");
        PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(() -> {
            if (ticket != generation.get()) return;
            pending = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                try {
                    var input = ReadAction.computeBlocking(() ->
                        disposed || project.isDisposed() || ticket != generation.get() ? null :
                            inputs(a == null ? null : a.id(), b == null ? null : b.id()));
                    if (input == null || ticket != generation.get()) return;
                    var paths = new HashSet<>(input.a().paths());
                    if (input.b() != null) paths.addAll(input.b().paths());
                    if (generation.get() == ticket) deps = Set.copyOf(paths);
                    var first = analyze(input.a());
                    var second = input.b() == null ? null : analyze(input.b());
                    var diff = PreprocessorTrace.compare(first.entries(), second == null ? Map.of() : second.entries());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (ticket == generation.get()) show(input, first, second, diff);
                    }, project.getDisposed());
                } catch (RuntimeException | LinkageError e) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (ticket == generation.get()) {
                            model.setRowCount(0);
                            status.setText("Trace unavailable");
                            showLog(e.toString());
                        }
                    }, project.getDisposed());
                }
            }, 180, TimeUnit.MILLISECONDS);
        });
    }
    private static void variants(JComboBox<Variant> box, List<Variant> choices, Variant selected, String caption) {
        box.removeAllItems();
        var first = new Variant(null, caption);
        box.addItem(first);
        choices.forEach(box::addItem);
        box.setSelectedItem(selected == null ? first : selected);
    }
    private void show(Inputs input, Trace a, Trace b, List<PreprocessorTrace.Row> diff) {
        installing = true;
        try {
            variants(left, input.choices(), input.left(), "Active variant");
            variants(right, input.choices(), input.right(), "No comparison");
        } finally { installing = false; }
        boolean complete = a.success() && (b == null || b.success());
        status.setText(input.context() + (complete ? "" : " · Partial trace"));
        showLog(a.log() + (b == null || b.log().isBlank() ? "" : "\n" + input.right().name() + "\n" + b.log()));
        rows = diff;
        var columns = new ArrayList<>(List.of("Origin", "Directive", input.left() == null ? "Active variant" : input.left().name()));
        if (b != null) columns.add(input.right().name());
        var data = new Object[rows.size()][columns.size()];
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var site = row.site();
            String origin = site.line() == 0 ? site.file() : site.file() + ":" + site.line();
            if (!site.callers().isEmpty())
                origin = String.join(" → ", site.callers().stream().map(c -> c.file() + ":" + c.line()).toList()) + " → " + origin;
            data[i][0] = origin;
            data[i][1] = (row.left() == null ? row.right() : row.left()).directive();
            data[i][2] = row.left() == null ? "—" : row.left().value();
            if (b != null) data[i][3] = row.right() == null ? "—" : row.right().value();
        }
        model.setDataVector(data, columns.toArray());
    }
    private void showLog(String message) {
        log.setText(message);
        log.setCaretPosition(0);
        SwingUtilities.getAncestorOfClass(JScrollPane.class, log).setVisible(!message.isBlank());
    }
    public void dispose() {
        disposed = true;
        generation.incrementAndGet();
        if (pending != null) pending.cancel(false);
    }
}
