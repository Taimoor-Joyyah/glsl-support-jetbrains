package com.glslsupport.tests;

import com.glslsupport.bridge.*;
import com.glslsupport.ide.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.*;
import com.intellij.psi.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import static com.glslsupport.tests.IdeSmoke.*;
import static com.glslsupport.tests.ReferenceWindow.find;

final class PipelineEditing {
    private static void perform(Project p, PsiFile file, String id) {
        perform(p, file, id, null);
    }
    private static void perform(Project p, PsiFile file, String id, Editor editor) {
        var action = Objects.requireNonNull(ActionManager.getInstance().getAction(id), id);
        var data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, p).add(CommonDataKeys.PSI_FILE, file);
        if (editor != null) data.add(CommonDataKeys.EDITOR, editor);
        action.actionPerformed(AnActionEvent.createEvent(action, data.build(), null, "test", ActionUiKind.NONE, null));
    }
    private static PsiFile file(Project p, Path path, String text) throws Exception {
        Files.writeString(path, text);
        var v = Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path));
        return ReadAction.compute(() -> Objects.requireNonNull(PsiManager.getInstance(p).findFile(v)));
    }
    private static Settings.Pipeline pipeline(String name, String mode) {
        var result = new Settings.Pipeline();
        result.name = name;
        result.target = 12;
        result.definitions.put("MODE",mode);
        result.stages.add(new Settings.StageFile("VERTEX","context.vert","day"));
        return result;
    }
    private static Settings.Data configuration(Settings.FileOptions options, Settings.Pipeline... pipelines) {
        var data = new Settings.Data();
        data.imported = true;
        data.validationDelaySeconds = 0;
        data.files.put("context.vert", options);
        data.pipelineDefinitions.addAll(List.of(pipelines));
        return data;
    }
    private static void edit(Project project, PsiFile f, String text) {
        ApplicationManager.getApplication().invokeAndWait(() -> WriteCommandAction.runWriteCommandAction(project, () -> {
            var manager = PsiDocumentManager.getInstance(project);
            var document = Objects.requireNonNull(manager.getDocument(f));
            document.setText(text);
            manager.commitDocument(document);
        }));
    }
    private static void exportFromInspector(JButton export, Path target, int[] words) throws Exception {
        ApplicationManager.getApplication().invokeLater(() -> {
            // The chooser runs a modal event loop. Approve a file inside the isolated test project.
            var choose = new javax.swing.Timer(100, e -> {
                for (var window : java.awt.Window.getWindows()) {
                    if (!window.isShowing()) continue;
                    var dialog = find(window, JFileChooser.class, JFileChooser::isShowing);
                    if (dialog != null) {
                        ((javax.swing.Timer)e.getSource()).stop();
                        dialog.setSelectedFile(target.toFile());
                        dialog.approveSelection();
                        break;
                    }
                }
            });
            choose.start();
            try { export.doClick(); } finally { choose.stop(); }
        });
        await(() -> Files.isRegularFile(target), "saved SPIR-V from the inspector");
        var bytes = java.nio.ByteBuffer.wrap(Files.readAllBytes(target)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        check(bytes.remaining() == words.length * Integer.BYTES, "exported module size");
        for (int word : words) check(bytes.getInt() == word, "exported displayed module");
    }
    private static void verifyLayoutRetarget(Project project, Path root, ToolWindow inspector) throws Exception {
        var vf = LocalFileSystem.getInstance().findFileByNioFile(root.resolve("tools.vert"));
        var file = ReadAction.compute(() -> Objects.requireNonNull(PsiManager.getInstance(project).findFile(vf)));
        var editor = onEdt(() -> FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true));
        try {
            var panel = onEdt(() -> {
                editor.getCaretModel().moveToOffset(file.getText().indexOf("struct Light") + 7);
                perform(project, file, "GlslSupport.Layout", editor);
                return inspector.getContentManager().getSelectedContent().getComponent();
            });
            await(() -> onEdt(() -> find(panel, JComboBox.class, b -> "Light".equals(b.getSelectedItem())) != null),
                "layout displays the struct under the caret");
            onEdt(() -> {
                editor.getCaretModel().moveToOffset(file.getText().indexOf("uniform Scene") + 8);
                perform(project, file, "GlslSupport.Layout", editor);
                check(inspector.getContentManager().getSelectedContent().getComponent() == panel, "one layout tab per shader");
                return true;
            });
            await(() -> onEdt(() -> find(panel, JComboBox.class, b -> "Scene".equals(b.getSelectedItem())) != null),
                "reopening layout selects the new block");
        } finally {
            onEdt(() -> {
                var manager = inspector.getContentManager();
                var content = manager.findContent("Layout · tools.vert");
                if (content != null) manager.removeContent(content, true);
                FileEditorManager.getInstance(project).closeFile(vf);
                return true;
            });
        }
    }
    static void verify(Project p, Path root, boolean graphical) throws Exception {
        String headerText = "#if MODE == 9\nconst float fromPipeline=1.;\n#else\nconst float fromFile=2.;\n#endif\n";
        var header = file(p,root.resolve("context.glslh"),headerText);
        var vs = file(p,root.resolve("context.vert"),"""
            #version 450
            #define DOUBLE(x) ((x)*2)
            #if DOUBLE(MODE) > 0
            #include "context.glslh"
            #endif
            #if DAY
            #define EXPOSURE 2
            #endif
            #if MODE == 2
            #error night_standalone
            #endif
            layout(location=3) out vec3 tint;
            float exposure(float gain){return gain;}
            void main(){tint=vec3(exposure(1.));gl_Position=vec4(1);}
            """);
        var fragment = file(p,root.resolve("context.frag"),"""
            #version 450
            layout(location=3) in vec4 tint;
            layout(location=0) out vec4 c;
            void main(){c=tint;}
            """);
        var settings = Settings.of(p);
        var saved = settings.getState();
        var opts = new Settings.FileOptions();
        for (String id : List.of("day","night")) {
            var v = new Settings.Variant();
            v.id = v.name = id;
            v.macros.put("DAY",id.equals("day") ? "1" : "0");
            v.macros.put("MODE",id.equals("day") ? "1" : "2");
            opts.variants.add(v);
        }
        opts.active = "day";
        var forward = pipeline("Forward","9");
        forward.stages.add(new Settings.StageFile("FRAGMENT","context.frag",""));
        var alternate = pipeline("Alternate","3");
        var contexts = PipelineContexts.of(p);
        var compiler = p.getService(CompilerSupport.class);
        var app = ApplicationManager.getApplication();
        try {
            app.invokeAndWait(() -> settings.loadState(configuration(opts, forward)));
            var lateRoot = file(p, root.resolve("late.vert"), "#include \"late.glslh\"\nvoid main(){}");
            var missing = ReadAction.compute(() -> contexts.current(lateRoot).graph());
            var lateHeader = file(p, root.resolve("late.glslh"), "const int added = 1;");
            ReadAction.run(() -> {
                String path = lateHeader.getVirtualFile().getPath();
                check(missing.inputPaths().contains(path) && !missing.files.containsKey(path), "missing include is watched");
                check(contexts.current(lateRoot).graph().files.containsKey(path), "creating an include renews the graph");
            });
            if (graphical) {
                onEdt(() -> FileEditorManager.getInstance(p).openFile(header.getVirtualFile(), true));
                await(() -> onEdt(() -> {
                    var bar = WindowManager.getInstance().getStatusBar(p);
                    var widget = bar == null ? null : bar.getWidget("GlslPipelineContext");
                    return widget instanceof CustomStatusBarWidget custom && custom.getComponent() instanceof JButton b
                        && b.isShowing() && b.getText().contains("Auto · Forward → VERTEX");
                }), "visible status-bar selector for a header opened before its root");
                onEdt(() -> {
                    var widget = (CustomStatusBarWidget)WindowManager.getInstance().getStatusBar(p).getWidget("GlslPipelineContext");
                    var button = (JButton)widget.getComponent();
                    button.doClick(0);
                    return true;
                });
                await(() -> onEdt(() -> {
                    for (var window : java.awt.Window.getWindows()) {
                        if (!window.isShowing()) continue;
                        var list = find(window, JList.class, l -> l.isShowing() && PopupUtil.getPopupContainerFor(l) instanceof ListPopup lp
                            && "GLSL pipeline context".equals(lp.getListStep().getTitle()));
                        if (list == null) continue;
                        var popup = (ListPopup)PopupUtil.getPopupContainerFor(list);
                        var step = popup.getListStep();
                        check(step.isSpeedSearchEnabled() && list.getSelectedIndex() == 0 && step.getValues().size() == 3,
                            "the searchable context chooser opens on Auto and includes the pipeline");
                        popup.cancel();
                        return true;
                    }
                    return false;
                }), "visible pipeline context popup");
                var editor = onEdt(() -> FileEditorManager.getInstance(p).openTextEditor(
                    new OpenFileDescriptor(p, vs.getVirtualFile()), true));
                int argument = ReadAction.compute(() -> vs.getText().indexOf("exposure(1.)") + "exposure(".length());
                await(() -> onEdt(() -> editor != null &&
                    !editor.getInlayModel().getInlineElementsInRange(argument, argument).isEmpty()),
                    "declarative parameter hint at the function argument");
            }
            ReadAction.run(() -> {
                var c = contexts.current(header);
                check(c.root().equals(vs) && "Forward".equals(c.pipeline()),"cold header owner");
                String path = header.getVirtualFile().getPath();
                check(c.graph().active(path,headerText.indexOf("fromPipeline")) &&
                    !c.graph().active(path,headerText.indexOf("fromFile")),"active header ranges");
                var names = c.graph().completions(path,headerText.length(),false);
                check(names.contains("fromPipeline") && !names.contains("fromFile"),"pipeline-aware completion");
                var builtins = c.graph().completions(path,headerText.length(),true);
                check(builtins.contains("gl_VertexIndex") && !builtins.contains("gl_VertexID") &&
                    !builtins.contains("gl_FragCoord"), "a shared header gets Vulkan vertex built-ins");
                check(CompilerSupport.request(header,null,NativeCompiler.Operation.CHECK).equals(
                    c.request(NativeCompiler.Operation.CHECK)), "inspector and editor share root and settings");
            });
            var vertexGraph = ReadAction.compute(() -> contexts.current(vs).graph());
            var fragmentGraph = ReadAction.compute(() -> contexts.current(fragment).graph());
            edit(p,header,headerText + "#error unsaved_context_header\n");
            ReadAction.run(() -> {
                check(contexts.current(header).graph() != vertexGraph, "editing an include renews its owner graph");
                check(contexts.current(fragment).graph() == fragmentGraph, "an independent stage reuses its graph");
            });
            var unsaved = ReadAction.compute(() -> CompilerSupport.request(header,null,NativeCompiler.Operation.CHECK));
            check(compiler.analyze(unsaved).log().contains("unsaved_context_header"),"unsaved included buffer in JNI");
            edit(p,header,headerText);
            app.invokeAndWait(() -> {
                settings.loadState(configuration(opts, forward, alternate));
                var matches = contexts.matches(header).stream().map(PipelineContexts.Context::label).toList();
                var selected = contexts.current(header);
                check(matches.size() == 2 && selected.pipeline() == null,
                    "ambiguous header uses standalone file; configured=" + settings.pipelines().keySet() +
                    ", matches=" + matches + ", selected=" + selected.label());
                contexts.select(header,contexts.matches(header).getFirst());
                check("Forward".equals(contexts.current(header).pipeline()),"explicit header choice");
                contexts.select(vs,contexts.matches(vs).getFirst());
                var document = PsiDocumentManager.getInstance(p).getDocument(vs);
                new Diagnostics.Saved().beforeDocumentSaving(document);
            });
            var jobs = ReadAction.compute(() -> new Diagnostics().collectInformation(vs));
            var diagnostics = new Diagnostics().doAnnotate(jobs);
            check(diagnostics != null && diagnostics.issues().stream().anyMatch(i ->
                i.message().contains("night_standalone") && i.message().contains("night")), "all-variant save diagnostics");
            var inspector = onEdt(() -> {
                var manager = ToolWindowManager.getInstance(p);
                var w = manager.getToolWindow("GLSL Inspector");
                if (w == null && !graphical) w = manager.registerToolWindow(RegisterToolWindowTask.notClosable("GLSL Inspector"));
                check(w != null,"registered GLSL Inspector");
                perform(p,vs,"GlslSupport.AllPipelines");
                return w;
            });
            var batch = onEdt(() -> inspector.getContentManager().findContent("Pipeline validation").getComponent());
            await(() -> onEdt(() -> find(batch,JLabel.class,l -> l.getText() != null && l.getText().startsWith("1 passed, 1 failed")) != null),
                "mixed pipeline batch");
            edit(p,header,headerText + "// pending rerun\n");
            await(() -> onEdt(() -> find(batch,JLabel.class,l -> Objects.toString(l.getText()).contains("Inputs changed")) != null),
                "an edit marks the existing link results stale");
            edit(p,header,headerText);
            onEdt(() -> {
                var tree = find(batch,JTree.class,t -> true);
                var nodes = ((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();
                var labels = Collections.list(nodes).stream().map(Object::toString).toList();
                check(labels.stream().anyMatch(s -> s.contains("vertex · tint")) &&
                    labels.stream().anyMatch(s -> s.contains("fragment · tint")), "both links in validation tree: " + labels);
                find(batch,JButton.class,b -> "Rerun".equals(b.getText()) && b.isEnabled()).doClick(0);
                return true;
            });
            await(() -> onEdt(() -> find(batch,JLabel.class,l -> "1 passed, 1 failed".equals(l.getText())) != null),
                "rerunning from the results clears the stale state");
            onEdt(() -> {
                perform(p,vs,"GlslSupport.AllPipelines");
                check(inspector.getContentManager().findContent("Pipeline validation").getComponent() == batch,"reused validation tab");
                find(batch,JButton.class,b -> "Cancel".equals(b.getText())).doClick();
                check(find(batch,JLabel.class,l -> Objects.toString(l.getText()).startsWith("Cancelled")) != null,
                    "an in-flight batch can be cancelled");
                perform(p,vs,"GlslSupport.Trace");
                return true;
            });
            var trace = onEdt(() -> inspector.getContentManager().findContent("Trace · context.vert").getComponent());
            await(() -> onEdt(() -> find(trace,JTable.class,t -> t.getRowCount() > 4) != null),"native trace in inspector");
            onEdt(() -> {
                var compare = find(trace,JComboBox.class,b -> Objects.toString(b.getSelectedItem()).equals("No comparison"));
                compare.setSelectedIndex(2);
                return true;
            });
            await(() -> onEdt(() -> find(trace,JTable.class,t -> t.getColumnCount() == 4 && t.getColumnName(3).equals("night")) != null),
                "independent variant comparison");
            onEdt(() -> {
                var table = find(trace,JTable.class,t -> true);
                int all = table.getRowCount();
                var differences = find(trace,JCheckBox.class,b -> "Differences only".equals(b.getText()));
                differences.doClick(0);
                check(table.getRowCount() > 0 && table.getRowCount() < all, "comparison hides equal directives");
                for (int i = 0; i < table.getRowCount(); i++)
                    check(!Objects.equals(table.getValueAt(i,2),table.getValueAt(i,3)), "every visible trace value differs");
                perform(p,vs,"GlslSupport.Trace");
                check(inspector.getContentManager().getSelectedContent().getComponent() == trace && differences.isSelected(),
                    "reopening the trace retains the comparison and its filter");
                if (graphical) {
                    boolean navigated = false;
                    for (int i = 0; i < table.getRowCount(); i++) {
                        if (!"#if DAY".equals(table.getValueAt(i,1))) continue;
                        table.setRowSelectionInterval(i, i);
                        var key = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                            .get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0));
                        table.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(table,0,""));
                        var editor = FileEditorManager.getInstance(p).getSelectedTextEditor();
                        check(editor != null && vs.getVirtualFile().equals(FileDocumentManager.getInstance().getFile(editor.getDocument()))
                            && editor.getDocument().getLineNumber(editor.getCaretModel().getOffset()) ==
                            vs.getText().substring(0, vs.getText().indexOf("#if DAY")).lines().count(),
                            "Enter opens the filtered directive at its source line");
                        navigated = true;
                        break;
                    }
                    check(navigated, "the filtered trace contains the conditional source directive");
                }
                var compare = find(trace,JComboBox.class,b -> "night".equals(Objects.toString(b.getSelectedItem())));
                compare.setSelectedIndex(1);
                return true;
            });
            await(() -> onEdt(() -> find(trace,JTable.class,t -> t.getColumnCount() == 4 &&
                t.getColumnName(3).equals("day") && t.getRowCount() == 0) != null),
                "comparing the active variant to itself produces an empty differences view");
            onEdt(() -> { perform(p,vs,"GlslSupport.Spirv"); return true; });
            var spv = onEdt(() -> inspector.getContentManager().findContent("SPIR-V · context.vert").getComponent());
            await(() -> onEdt(() -> find(spv,JButton.class,b -> "Export SPIR-V…".equals(b.getText()) && b.isEnabled()) != null),
                "validated export action");
            onEdt(() -> {
                perform(p,vs,"GlslSupport.Spirv");
                check(inspector.getContentManager().getSelectedContent().getComponent() == spv,
                    "reopening the SPIR-V inspector selects the compiled tab");
                return true;
            });
            if (graphical) {
                var req = ReadAction.compute(() -> CompilerSupport.request(vs,null,NativeCompiler.Operation.SPIRV));
                var button = onEdt(() -> find(spv,JButton.class,b -> "Export SPIR-V…".equals(b.getText())));
                exportFromInspector(button,root.resolve("context-export.spv"),compiler.analyze(req).words());
                verifyLayoutRetarget(p, root, inspector);
            }
            onEdt(() -> {
                contexts.select(vs,contexts.matches(vs).getFirst());
                contexts.select(vs,contexts.matches(vs).getLast());
                return true;
            });
            await(() -> onEdt(() -> find(spv,JLabel.class,l -> Objects.toString(l.getText()).contains("Alternate → VERTEX")) != null),
                "latest editor selection wins pending inspection");
            onEdt(() -> {
                settings.loadState(configuration(opts, alternate));
                check("Alternate".equals(contexts.current(header).pipeline()) && contexts.getState().files.get("context.glslh")==null,
                    "missing pinned binding returns to Auto");
                return true;
            });
            onEdt(() -> {
                find(batch,JButton.class,b -> "Rerun".equals(b.getText())).doClick(0);
                return true;
            });
            await(() -> onEdt(() -> find(batch,JLabel.class,l -> "1 passed, 0 failed".equals(l.getText())) != null),
                "rerunning all pipelines uses the current configuration");
        } finally {
            app.invokeAndWait(() -> {
                var window = ToolWindowManager.getInstance(p).getToolWindow("GLSL Inspector");
                if (window != null) {
                    var manager = window.getContentManager();
                    for (String name : List.of("Pipeline validation", "Trace · context.vert", "SPIR-V · context.vert")) {
                        var content = manager.findContent(name);
                        if (content != null) manager.removeContent(content, true);
                    }
                }
                if (graphical) {
                    FileEditorManager.getInstance(p).closeFile(header.getVirtualFile());
                    FileEditorManager.getInstance(p).closeFile(vs.getVirtualFile());
                }
                contexts.select(header,null);
                contexts.select(vs,null);
                settings.loadState(saved);
            });
        }
    }
}
