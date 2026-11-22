package com.glslsupport.ide;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import java.nio.file.*;
import java.util.*;
import org.jdom.Element;

final class Migration {
    private Migration() {}
    static Element option(Element el, String name) {
        return el.getChildren("option")
            .stream()
            .filter(o -> name.equals(o.getAttributeValue("name")))
            .findFirst()
            .orElse(null);
    }
    static String val(Element el, String key, String fallback) {
        var o = option(el, key);
        return o == null ? fallback : o.getAttributeValue("value", fallback);
    }
    static List<Element> list(Element el, String key) {
        var opt = option(el, key);
        if (opt == null || opt.getChildren().isEmpty())
            return List.of();
        return opt.getChildren().getFirst().getChildren();
    }
    static Element component(Path path, String name) throws Exception {
        if (!Files.isRegularFile(path))
            return null;
        return JDOMUtil.load(path)
            .getChildren("component")
            .stream()
            .filter(c -> name.equals(c.getAttributeValue("name")))
            .findFirst()
            .orElse(null);
    }
    static void importOnce(Project project, Settings.Data d) {
        if (d.imported || project.getBasePath() == null || project.isDefault())
            return;
        Path dir = Path.of(project.getBasePath(), ".idea");
        try {
            var old = component(dir.resolve("glslAnalyzer.xml"), "GlslAnalyzerSettings");
            if (old != null) {
                d.target = "VULKAN".equals(val(old, "glslangDiagnosticTarget", "OPENGL")) ? 10 : 0;
                d.includes = new ArrayList<>(val(old, "includeSearchPaths", "")
                        .lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
                String fallback = val(old, "genericGlslStage", "AUTO");
                if (Settings.stage(fallback) != null)
                    d.fallback = fallback;
                for (String line : val(old, "shaderStageMacros", "").lines().toList()) {
                    var pair = line.split("=", 2);
                    if (pair.length == 2)
                        for (String name : pair[1].split("[,;]")) d.stageMacros.put(name.strip(), pair[0].strip());
                }
                d.diagnostics = Boolean.parseBoolean(val(old, "diagnosticsEnabled", "true"))
                    && Boolean.parseBoolean(val(old, "glslangDiagnosticsEnabled", "true"));
                d.builtins = Boolean.parseBoolean(val(old, "builtinCompletionsEnabled", "true"));
                d.semantic = Boolean.parseBoolean(val(old, "semanticTokensEnabled", "true"));
                d.colors = Boolean.parseBoolean(val(old, "documentColorsEnabled", "true"));
                d.format = Boolean.parseBoolean(val(old, "formattingEnabled", "true"));
                d.range = Boolean.parseBoolean(val(old, "rangeFormattingEnabled", "true"));
                d.onType = Boolean.parseBoolean(val(old, "onTypeFormattingEnabled", "true"));
                d.signatures = Boolean.parseBoolean(val(old, "signatureHelpEnabled", "true"));
                d.inlays = Boolean.parseBoolean(val(old, "inlayHintsEnabled", "true"));
            }
            var variants = component(dir.resolve("glslVariants.xml"), "GlslVariantSettings");
            if (variants != null)
                for (var file : list(variants, "files")) {
                    String url = val(file, "url", "").replace("$PROJECT_DIR$", project.getBasePath());
                    if (url.isBlank())
                        continue;
                    String local = com.intellij.openapi.vfs.VfsUtilCore.urlToPath(url);
                    var settings = new Settings.FileOptions();
                    settings.active = val(file, "defaultVariantId", "Default");
                    for (var v : list(file, "variants")) {
                        var variant = new Settings.Variant();
                        variant.id = val(v, "id", "Default");
                        variant.name = val(v, "name", variant.id);
                        if (Boolean.parseBoolean(val(file, "versionOverrideEnabled", "false"))) {
                            variant.version = Integer.parseInt(val(file, "version", "460"));
                            variant.profile = val(file, "profile", "core");
                        }
                        for (var macro : list(v, "definitions"))
                            variant.macros.put(val(macro, "name", ""), val(macro, "value", ""));
                        settings.variants.add(variant);
                    }
                    d.files.putIfAbsent(Settings.relative(project, local), settings);
                }
            d.imported = true;
        } catch (Exception e) {
            Logger.getInstance(Migration.class).warn("Could not import GLSL Analyzer settings", e);
        }
    }
}
