package com.glslsupport.tests;

import static com.glslsupport.tests.IdeSmoke.check;

import com.glslsupport.bridge.*;
import com.glslsupport.ide.*;
import com.glslsupport.lang.*;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class Features {
    static void verify(Project p, Path root) throws InterruptedException {
        var fs = LocalFileSystem.getInstance();
        var v = Objects.requireNonNull(fs.refreshAndFindFileByNioFile(root.resolve("tools.vert")));
        var frag = Objects.requireNonNull(fs.refreshAndFindFileByNioFile(root.resolve("tools.frag")));
        var tool = ReadAction.compute(() -> PsiManager.getInstance(p).findFile(v));
        var settings = Settings.of(p);
        var data = settings.getState();
        check(data.imported && data.includes.equals(List.of("legacy-headers"))
                && data.stageMacros.containsKey("OLD_TESS"),
            "settings migration");
        var imported = data.files.get("legacy.glsl");
        check(imported != null && imported.active.equals("imported")
                && new ArrayList<>(imported.variants.getFirst().macros.keySet()).equals(List.of("ZED", "ALPHA")),
            "ordered variant migration");
        data.includes.clear();
        check(Settings.of(p).getState().includes.isEmpty(), "migration only runs once");
        check(
            ReferenceDocs.entries().containsAll(List.of("glSpecializeShader", "gl_DrawID", "GL_PARAMETER_BUFFER"))
                && ReferenceDocs.page("allInvocationsEqual") != null,
            "installed OpenGL 4.6 reference");
        var alias = ReferenceDocs.page("glGetIntegerv");
        check(alias != null && alias.equals(ReferenceDocs.page("glGet")), "Khronos grouped-page alias");
        try {
            var page = ReferenceDocs.page("glPolygonOffsetClamp").toURI();
            check(page.getHost().equals("127.0.0.1"), "reference browser reads through the local cache");
            try (var in = page.toURL().openStream()) {
                check(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("mathjax/MathJax.js?config=TeX-AMS-MML_SVG"), "installed page uses local math");
            }
            try (var in = page.resolve("mathjax/extensions/MathML/content-mathml.js").toURL().openStream()) {
                check(in.read() >= 0, "deferred math available");
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        var compiled =
            ReadAction.compute(() -> CompilerSupport.request(tool, null, NativeCompiler.Operation.SPIRV));
        var compiler = p.getService(CompilerSupport.class);
        var asm = compiler.analyze(compiled);
        check(asm.success() && asm.assembly().contains("OpEntryPoint") && !asm.instructions().isEmpty(),
            "packaged DLL SPIR-V and source map: " + asm.log());
        var req = ReadAction.compute(() -> CompilerSupport.request(tool, null, NativeCompiler.Operation.LAYOUT));
        var report = compiler.analyze(req);
        check(report.success() && report.layout().contains("tint\t16\t12\t16"),
            "block offsets: " + report.log() + report.layout());
        var standalone = compiler.analyze(ShaderViews.preview(req, "Light", "std430"));
        check(standalone.success() && standalone.layout().contains("value.color"),
            "standalone struct preview: " + standalone.log());
        data.pipelines.put("Main graphics", List.of("tools.vert", "tools.frag"));
        var pipeline = ReadAction.compute(() -> CompilerSupport.pipeline(p, "Main graphics"));
        var linked = compiler.analyze(pipeline);
        check(linked.success(), "pipeline: " + linked.log());
        var fileConfig = new Settings.FileOptions();
        var v1 = new Settings.Variant();
        v1.id = "day";
        v1.name = "Day";
        v1.macros.put("MODE", "1");
        var v2 = new Settings.Variant();
        v2.id = "bad";
        v2.name = "Night";
        v2.macros.put("BROKEN", "1");
        fileConfig.active = "day";
        fileConfig.variants.add(v1);
        fileConfig.variants.add(v2);
        data.files.put("tools.vert", fileConfig);
        var variant =
            ReadAction.compute(() -> CompilerSupport.request(tool, "bad", NativeCompiler.Operation.CHECK));
        check(!compiler.analyze(variant).success(), "variant macro selection");
        ReadAction.run(() -> {
            var doc = PsiDocumentManager.getInstance(p).getDocument(tool);
            check(new EditorFeatures.Folds().buildFoldRegions(tool, doc, false).length > 0, "code folding");
            check(new EditorFeatures.Structure().getStructureViewBuilder(tool) != null, "structure view");
            int at = tool.getText().indexOf("normalize");
            var original = tool.findElementAt(at);
            check(new EditorFeatures.Docs().generateDoc(original, original).contains("normalize"),
                "built-in documentation");
            var nameTool = new Navigation.Symbols();
            boolean newer =
                com.intellij.openapi.application.ApplicationInfo.getInstance().getBuild().getBaselineVersion()
                >= 262;
            check(Arrays.asList(nameTool.getNames(p, false)).contains("Main graphics") == newer,
                "version-gated shader search");
            var colors = new Calls.Colors();
            int literal = tool.getText().indexOf("vec3(0.1");
            var color = colors.getColorFrom(tool.findElementAt(literal).getParent());
            check(color != null && color.getBlue() > color.getRed(), "document color");
            var ref = root.resolve("scene.vert");
            check(
                v.getFileType() == GlslLanguage.Type.INSTANCE && frag.getFileType() == GlslLanguage.Type.INSTANCE,
                "stage types");
        });
        ApplicationManager.getApplication().invokeAndWait(() -> {
            for (String id : List.of("GlslSupport.Layout", "GlslSupport.Spirv", "GlslSupport.New",
                     "GlslSupport.Extract", "GlslSupport.Inline", "GlslSupport.Pipeline"))
                check(ActionManager.getInstance().getAction(id) != null, "missing IDE action " + id);
            var page = new SettingsUI(p);
            check(page.createComponent() != null && !page.isModified(), "editable settings/reset");
            var formatFile = PsiManager.getInstance(p).findFile(
                Objects.requireNonNull(fs.findFileByNioFile(root.resolve("format.frag"))));
            WriteCommandAction.runWriteCommandAction(
                p, () -> { com.intellij.psi.codeStyle.CodeStyleManager.getInstance(p).reformat(formatFile); });
            check(formatFile.getText().contains("x = 1."), "IDE reformat action: " + formatFile.getText());
            var provider = com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.getSettingsPagesProviders()
                .stream().filter(GlslCodeStyle.class::isInstance).findFirst().orElseThrow();
            var style = com.intellij.application.options.CodeStyle.getSettings(formatFile).clone();
            var configurable = provider.createConfigurable(style, style.clone());
            try { check(configurable.createComponent() != null, "GLSL Code Style page renders"); }
            finally { configurable.disposeUIResources(); }
            var common = style.getCommonSettings(GlslLanguage.INSTANCE);
            common.SPACE_AROUND_ASSIGNMENT_OPERATORS = false;
            com.intellij.application.options.CodeStyle.doWithTemporarySettings(p, style, () ->
                WriteCommandAction.runWriteCommandAction(p, () -> {
                    com.intellij.psi.codeStyle.CodeStyleManager.getInstance(p).reformat(formatFile);
                }));
            check(formatFile.getText().contains("x=1."), "project GLSL assignment spacing");
        });
        for (String folder : List.of("first", "second")) {
            IdeSmoke.awaitProjectConfiguration(p);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                var template = com.intellij.ide.fileTemplates.FileTemplateManager.getInstance(p).getTemplate(
                    "GLSL Header.glslh");
                check(template != null, "file template discovered");
                try {
                    var action = ActionManager.getInstance().getAction("GlslSupport.New");
                    var method = action.getClass().getDeclaredMethod("createFileFromTemplate", String.class,
                        com.intellij.ide.fileTemplates.FileTemplate.class, PsiDirectory.class);
                    method.setAccessible(true);
                    var directory = PsiManager.getInstance(p).findDirectory(v.getParent());
                    var sub = Objects.requireNonNull(directory.findSubdirectory(folder));
                    var created = (PsiFile) method.invoke(action, "common", template, sub);
                    var guard = Editing.guard(folder + "/common.glslh");
                    check(created != null && created.getName().equals("common.glslh")
                            && created.getText().contains("#ifndef " + guard)
                            && created.getText().contains("#define " + guard),
                        "New header action for " + folder);
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
            });
        }
    }
}
