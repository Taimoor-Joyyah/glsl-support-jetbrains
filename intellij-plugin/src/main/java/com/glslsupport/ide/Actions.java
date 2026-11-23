package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.diff.*;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.*;
import com.intellij.psi.*;
import java.awt.Dimension;
import java.util.*;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public final class Actions {
    public abstract static class Refactor extends AnAction {
        final boolean extract;
        Refactor(boolean extract) {
            this.extract = extract;
        }
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(
                e.getData(CommonDataKeys.PSI_FILE) instanceof GlslLanguage.File);
        }
        public void actionPerformed(@NotNull AnActionEvent e) {
            var p = e.getProject();
            var editor = e.getData(CommonDataKeys.EDITOR);
            var file = e.getData(CommonDataKeys.PSI_FILE);
            if (p == null || editor == null || file == null)
                return;
            String title = extract ? "Extract GLSL Function" : "Inline GLSL Local Variable";
            var document = editor.getDocument();
            String before = document.getText();
            try {
                var w = ProjectAccess.graph(file);
                var key = ProjectAccess.key(file);
                List<Editing.Change> edits;
                if (extract) {
                    var sel = editor.getSelectionModel();
                    if (!sel.hasSelection())
                        throw new IllegalArgumentException("Select the code to extract");
                    String name = Messages.showInputDialog(p, "Function name", title, null);
                    if (name == null)
                        return;
                    edits = Editing.extract(w, key, sel.getSelectionStart(), sel.getSelectionEnd(), name);
                } else {
                    var hits = w.resolve(key, editor.getCaretModel().getOffset());
                    if (hits.size() != 1)
                        throw new IllegalArgumentException("Place the caret on a local variable");
                    edits = Editing.inline(w, hits.getFirst());
                }
                var ordered =
                    edits.stream().sorted(Comparator.comparingInt(Editing.Change::start).reversed()).toList();
                var updated = new StringBuilder(before);
                ordered.forEach(c -> updated.replace(c.start(), c.end(), c.text()));
                var dialog = new DialogWrapper(p) {
                    protected JComponent createCenterPanel() {
                        var view = DiffManager.getInstance().createRequestPanel(p, getDisposable(), null);
                        var factory = DiffContentFactory.getInstance();
                        view.setRequest(
                            new SimpleDiffRequest(title, factory.create(p, before, GlslLanguage.Type.INSTANCE),
                                factory.create(p, updated.toString(), GlslLanguage.Type.INSTANCE),
                                "Current shader", "Proposed changes"));
                        var part = view.getComponent();
                        part.setPreferredSize(new Dimension(760, 480));
                        return part;
                    }
                    {
                        setTitle(title);
                        setOKButtonText("Apply refactoring");
                        init();
                    }
                };
                if (dialog.showAndGet()) {
                    if (!before.equals(document.getText()))
                        throw new IllegalArgumentException(
                            "The shader changed during preview. Run the refactoring again.");
                    WriteCommandAction.runWriteCommandAction(p, title, null, () -> {
                        for (var c : ordered) document.replaceString(c.start(), c.end(), c.text());
                        PsiDocumentManager.getInstance(p).commitDocument(document);
                    }, file);
                }
            } catch (IllegalArgumentException ex) {
                Messages.showInfoMessage(p, ex.getMessage(), title);
            }
        }
    }
    public static final class Inline extends Refactor {
        public Inline() {
            super(false);
        }
    }
    public static final class Extract extends Refactor {
        public Extract() {
            super(true);
        }
    }
    public static final class Pipeline extends DumbAwareAction {
        public void actionPerformed(@NotNull AnActionEvent e) {
            var p = e.getProject();
            if (p == null)
                return;
            var names = Settings.of(p).pipelines().keySet().toArray(String[] ::new);
            if (names.length == 0) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(
                    p, SettingsUI.class);
                return;
            }
            String name = names.length == 1 ? names[0]
                                            : Messages.showEditableChooseDialog("Select a pipeline",
                                                  "Validate GLSL Pipeline", null, names, names[0], null);
            if (name == null)
                return;
            validate(p, name);
        }
        static void validate(com.intellij.openapi.project.Project p, String name) {
            PipelineValidation.open(p, List.of(name));
        }
    }
    public static final class AllPipelines extends DumbAwareAction {
        public void actionPerformed(@NotNull AnActionEvent e) {
            var project = e.getProject();
            if (project != null) validate(project);
        }
        static void validate(com.intellij.openapi.project.Project project) {
            PipelineValidation.open(project, List.copyOf(Settings.of(project).pipelines().keySet()));
        }
    }
}
