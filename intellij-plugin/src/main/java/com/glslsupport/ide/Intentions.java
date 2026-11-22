package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import java.nio.file.*;
import java.util.*;
import org.jetbrains.annotations.*;
public final class Intentions {
    public abstract static class Base implements IntentionAction {
        final String text;
        Base(String text) {
            this.text = text;
        }
        public @NotNull String getText() {
            return text;
        }
        public @NotNull String getFamilyName() {
            return "GLSL Support";
        }
        public boolean startInWriteAction() {
            return true;
        }
        public boolean isAvailable(@NotNull Project p, Editor e, PsiFile f) {
            return f instanceof GlslLanguage.File && e != null && enabled(e, f);
        }
        abstract boolean enabled(Editor e, PsiFile f);
        public void invoke(@NotNull Project p, Editor e, PsiFile f) {
            edit(e, f);
            PsiDocumentManager.getInstance(p).commitDocument(e.getDocument());
        }
        abstract void edit(Editor e, PsiFile f);
    }
    public static final class Version extends Base implements com.intellij.codeInspection.LocalQuickFix {
        public Version() {
            super("Add #version directive");
        }
        boolean enabled(Editor e, PsiFile f) {
            return !ProjectAccess.model(f).hasVersion();
        }
        void edit(Editor e, PsiFile f) {
            insert(e.getDocument());
        }
        private static void insert(Document document) {
            document.insertString(0, "#version 460 core\n");
        }
        public @NotNull String getName() {
            return getText();
        }
        public void applyFix(
            @NotNull Project project, @NotNull com.intellij.codeInspection.ProblemDescriptor descriptor) {
            var file = descriptor.getPsiElement().getContainingFile();
            if (file != null && !ProjectAccess.model(file).hasVersion()) {
                var manager = PsiDocumentManager.getInstance(project);
                var document = manager.getDocument(file);
                if (document != null) {
                    insert(document);
                    manager.commitDocument(document);
                }
            }
        }
    }
    public static final class Main extends Base {
        public Main() {
            super("Generate void main()");
        }
        boolean enabled(Editor e, PsiFile f) {
            return ProjectAccess.model(f).symbols.stream().noneMatch(
                s -> s.name().equals("main") && s.kind() == Syntax.Kind.FUNCTION);
        }
        void edit(Editor e, PsiFile f) {
            e.getDocument().insertString(e.getDocument().getTextLength(), "\nvoid main() {\n}\n");
        }
    }
    public static final class Extension extends Base {
        public Extension() {
            super("Add required #extension");
        }
        String extension(Editor e, PsiFile f) {
            var m = ProjectAccess.model(f);
            String n = m.word(m.indexAt(e.getCaretModel().getOffset()));
            return Catalog.entries(n)
                .stream()
                .map(Catalog.Entry::extensions)
                .filter(v -> !v.isEmpty())
                .flatMap(v -> Arrays.stream(v.split(",")))
                .filter(v -> !f.getText().contains("#extension " + v))
                .findFirst()
                .orElse(null);
        }
        boolean enabled(Editor e, PsiFile f) {
            return extension(e, f) != null;
        }
        void edit(Editor e, PsiFile f) {
            String ext = extension(e, f);
            if (ext == null)
                return;
            var m = ProjectAccess.model(f);
            int at = m.code.stream()
                         .filter(t -> t.text().matches("(?s)#\\s*version\\b.*"))
                         .mapToInt(Lexer.Token::end)
                         .findFirst()
                         .orElse(0);
            e.getDocument().insertString(at, (at == 0 ? "" : "\n") + "#extension " + ext + " : require\n");
        }
    }
    public static final class Include extends Base {
        public Include() {
            super("Create missing GLSL include");
        }
        Path target(Editor e, PsiFile f) {
            if (f.getVirtualFile() == null)
                return null;
            var graph = ProjectAccess.graph(f);
            var key = ProjectAccess.key(f);
            int pos = e.getCaretModel().getOffset();
            for (var inc : graph.files.get(key).includes)
                if (pos >= inc.start() && pos <= inc.end() && graph.include(key, inc) == null) {
                    var parent = Path.of(key).getParent();
                    if (parent == null)
                        return null;
                    var resolved = Workspace.resolvePath(parent.toString(), inc.path());
                    if (resolved == null)
                        return null;
                    var next = Path.of(resolved);
                    if (next.startsWith(parent) && !Files.exists(next))
                        return next;
                }
            return null;
        }
        boolean enabled(Editor e, PsiFile f) {
            return target(e, f) != null;
        }
        void edit(Editor e, PsiFile f) {
            var path = target(e, f);
            if (path == null)
                return;
            try {
                var dir = com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(path.getParent().toString());
                if (dir != null) {
                    var file = dir.createChildData(this, path.getFileName().toString());
                    com.intellij.openapi.vfs.VfsUtil.saveText(
                        file, Editing.header(Settings.relative(f.getProject(), path.toString())));
                }
            } catch (java.io.IOException ex) {
                throw new com.intellij.util.IncorrectOperationException(ex);
            }
        }
    }
    public abstract static class Wrap extends Base {
        final String directive;
        Wrap(String name, String directive) {
            super(name);
            this.directive = directive;
        }
        boolean enabled(Editor e, PsiFile f) {
            return e.getSelectionModel().hasSelection();
        }
        void edit(Editor e, PsiFile f) {
            var s = e.getSelectionModel();
            String selected = s.getSelectedText();
            int start = s.getSelectionStart();
            String result = directive.equals("define") ? "#define NAME " + selected.replace("\n", " \\\n")
                                                       : "#" + directive + " FEATURE\n" + selected + "\n#endif";
            e.getDocument().replaceString(start, s.getSelectionEnd(), result);
            int marker = result.indexOf(directive.equals("define") ? "NAME" : "FEATURE");
            s.setSelection(start + marker, start + marker + (directive.equals("define") ? 4 : 7));
        }
    }
    public static final class Ifdef extends Wrap {
        public Ifdef() {
            super("Wrap selection with #ifdef", "ifdef");
        }
    }
    public static final class Ifndef extends Wrap {
        public Ifndef() {
            super("Wrap selection with #ifndef", "ifndef");
        }
    }
    public static final class Define extends Wrap {
        public Define() {
            super("Convert selection to #define", "define");
        }
    }
}
