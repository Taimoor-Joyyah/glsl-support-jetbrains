package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.*;
public final class Inspections extends LocalInspectionTool {
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean onTheFly) {
        return new PsiElementVisitor() {
            public void visitFile(@NotNull PsiFile f) {
                if (!(f instanceof GlslLanguage.File))
                    return;
                var w = ProjectAccess.graph(f);
                var key = ProjectAccess.key(f);
                var syntax = w.files.get(key);
                if (!syntax.source.isEmpty() && !syntax.hasVersion()
                    && (ShaderConfig.Stage.fromPath(key).isPresent()
                        || f.getName().endsWith(".glsl")
                            && syntax.named("main").stream().anyMatch(s -> s.kind() == Syntax.Kind.FUNCTION))
                    && PipelineContexts.of(f.getProject()).current(f).config().version() == 0) {
                    holder.registerProblem(f, "Missing #version directive",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new com.intellij.openapi.util.TextRange(0, 1), new Intentions.Version());
                }
                for (var report : Editing.inspect(w, key)) {
                    var e = f.findElementAt(report.start());
                    if (e != null)
                        holder.registerProblem(e, report.message(), ProblemHighlightType.WEAK_WARNING,
                            new com.intellij.openapi.util.TextRange(report.start() - e.getTextOffset(),
                                Math.min(report.end(), e.getTextRange().getEndOffset()) - e.getTextOffset()));
                }
            }
        };
    }
}
