package com.glslsupport.ide;

import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.util.List;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public final class ContextWidget implements StatusBarWidgetFactory {
    public @NotNull String getId() { return "GlslPipelineContext"; }
    public @NotNull String getDisplayName() { return "GLSL pipeline context"; }
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) { return new Widget(project); }

    private record Options(PsiFile file, PipelineContexts.Context standalone,
                           List<PipelineContexts.Context> matches, String label) {}
    private static final class Widget implements CustomStatusBarWidget {
        final Project project;
        final JButton button = new JButton();
        private Options options;
        private long generation;
        private volatile boolean disposed;
        Widget(Project project) {
            this.project = project;
            button.setBorderPainted(false);
            button.setOpaque(false);
            button.setVisible(false);
            button.setToolTipText("Choose GLSL pipeline context");
            button.addActionListener(event -> popup());
        }
        public @NotNull String ID() { return "GlslPipelineContext"; }
        public @NotNull JComponent getComponent() { return button; }
        public void install(@NotNull StatusBar bar) {
            var bus = project.getMessageBus().connect(this);
            bus.subscribe(PipelineContexts.CHANGED, this::refresh);
            bus.subscribe(PsiModificationTracker.TOPIC, this::refresh);
            bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                public void selectionChanged(@NotNull FileEditorManagerEvent event) { refresh(); }
            });
            refresh();
        }
        private void refresh() {
            if (disposed || project.isDisposed()) return;
            var app = ApplicationManager.getApplication();
            if (!app.isDispatchThread()) {
                app.invokeLater(this::refresh, project.getDisposed());
                return;
            }
            long ticket = ++generation;
            var files = FileEditorManager.getInstance(project).getSelectedFiles();
            if (files.length == 0 || files[0].getFileType() != GlslLanguage.Type.INSTANCE) {
                options = null;
                button.setVisible(false);
                return;
            }
            VirtualFile v = files[0];
            ReadAction.nonBlocking(() -> {
                if (disposed || project.isDisposed() || !v.isValid()) return null;
                var file = PsiManager.getInstance(project).findFile(v);
                if (file == null) return null;
                var contexts = PipelineContexts.of(project);
                var current = contexts.current(file);
                boolean auto = contexts.automatic(file);
                return new Options(file, contexts.standalone(file), contexts.matches(file),
                    (auto ? "Auto · " : "") + current.label());
            }).withDocumentsCommitted(project).expireWith(this).coalesceBy(this)
                .finishOnUiThread(ModalityState.any(), next -> {
                    if (generation != ticket) return;
                    options = next;
                    button.setVisible(next != null);
                    if (next != null) button.setText(next.label());
                }).submit(AppExecutorUtil.getAppExecutorService());
        }
        public void dispose() { disposed = true; generation++; }
        private void popup() {
            var shown = options;
            if (shown == null) return;
            var menu = new JPopupMenu();
            item(menu, shown, "Auto", null);
            item(menu, shown, shown.standalone().label(), shown.standalone());
            for (var stage : shown.matches()) item(menu, shown, stage.label(), stage);
            menu.show(button, 0, -menu.getPreferredSize().height);
        }
        private void item(JPopupMenu menu, Options shown, String name, PipelineContexts.Context context) {
            menu.add(name).addActionListener(event -> {
                if (shown.file().isValid()) PipelineContexts.of(project).select(shown.file(), context);
            });
        }
    }
}
