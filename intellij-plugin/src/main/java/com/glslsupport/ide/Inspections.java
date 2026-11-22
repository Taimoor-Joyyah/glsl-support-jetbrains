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
                for (var report : Editing.inspect(w, key)) {
                    var e = f.findElementAt(report.start());
                    if (e != null)
                        holder.registerProblem(e, report.message(), ProblemHighlightType.WEAK_WARNING);
                }
            }
        };
    }
}
