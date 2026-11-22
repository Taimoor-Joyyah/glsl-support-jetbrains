package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.ide.actions.*;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import java.nio.file.Path;
import java.util.*;
import org.jetbrains.annotations.*;

public final class Templates extends CreateFileFromTemplateAction {
    public Templates() {
        super("GLSL Shader or Header", "Create a GLSL shader or guarded header",
            GlslLanguage.Type.INSTANCE.getIcon());
    }
    protected void buildDialog(
        @NotNull Project p, @NotNull PsiDirectory dir, CreateFileFromTemplateDialog.Builder b) {
        b.setTitle("New GLSL shader or header");
        var icon = GlslLanguage.Type.INSTANCE.getIcon();
        b.addKind("Header with include guard", icon, "GLSL Header.glslh");
        for (String entry : List.of("Empty Shader.glsl", "Vertex Shader.vert", "Fragment Shader.frag",
                 "Geometry Shader.geom", "Compute Shader.comp", "Tess Control Shader.tesc",
                 "Tess Evaluation Shader.tese", "Task Shader.task", "Mesh Shader.mesh", "Ray Generation.rgen",
                 "Intersection.rint", "Any Hit.rahit", "Closest Hit.rchit", "Miss.rmiss", "Callable.rcall"))
            b.addKind(entry.substring(0, entry.lastIndexOf('.')), icon, "GLSL " + entry);
    }
    protected String getActionName(@NotNull PsiDirectory d, @NotNull String n, @NotNull String t) {
        return "Create GLSL file";
    }
    protected PsiFile createFileFromTemplate(String name, FileTemplate template, PsiDirectory dir) {
        if (!template.getExtension().equals("glslh") && !template.getName().equals("GLSL Header.glslh"))
            return super.createFileFromTemplate(name, template, dir);
        String path = Path.of(dir.getVirtualFile().getPath())
                          .resolve(name.endsWith(".glslh") ? name : name + ".glslh")
                          .toString();
        String guard = Editing.guard(Settings.relative(dir.getProject(), path));
        return CreateFileFromTemplateAction.createFileFromTemplate(
            name, template, dir, null, true, Map.of(), Map.of("GLSL_GUARD", guard));
    }
}
