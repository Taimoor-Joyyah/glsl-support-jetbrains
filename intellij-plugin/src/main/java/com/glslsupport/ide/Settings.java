package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import java.nio.file.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

@State(name = "GlslSupport", storages = @Storage("glslSupport.xml"))
public final class Settings implements PersistentStateComponent<Settings.Data> {
    public static final class Variant {
        public String id = "Default", name = "Default", stage = "", profile = "";
        public int target = -1, version;
        @com.intellij.util.xmlb.annotations.MapAnnotation(sortBeforeSave = false)
        public Map<String, String> macros = new LinkedHashMap<>();
    }
    public static final class FileOptions {
        public String active = "Default";
        public List<Variant> variants = new ArrayList<>();
    }
    public static final class Data {
        public int target;
        public String fallback = "VERTEX";
        public List<String> includes = new ArrayList<>();
        public Map<String, Integer> targets = new LinkedHashMap<>();
        public Map<String, String> stageMacros = new LinkedHashMap<>();
        public Map<String, FileOptions> files = new LinkedHashMap<>();
        public Map<String, List<String>> pipelines = new LinkedHashMap<>();
        public boolean diagnostics = true, builtins = true, colors = true, inlays = true, signatures = true,
                       semantic = true, onType = true, range = true, format = true, imported = false;
        public Data() {
            for (var s : ShaderConfig.Stage.values()) stageMacros.put(s.name() + "_SHADER", s.name());
        }
    }
    private Data data = new Data();
    final com.intellij.openapi.util.SimpleModificationTracker changes =
        new com.intellij.openapi.util.SimpleModificationTracker();
    void changed() {
        changes.incModificationCount();
    }
    public static Settings of(Project p) {
        var settings = p.getService(Settings.class);
        synchronized (settings) {
            Migration.importOnce(p, settings.data);
        }
        return settings;
    }
    public Data getState() {
        return data;
    }
    public void loadState(@NotNull Data state) {
        data = state;
        changed();
    }
    static String relative(Project p, String file) {
        var base = p.getBasePath();
        if (base != null) {
            var root = Path.of(base).normalize();
            var path = Path.of(file).normalize();
            if (path.startsWith(root))
                return Workspace.path(root.relativize(path).toString());
        }
        return Workspace.path(file);
    }
    List<String> roots(Project p) {
        var root = Path.of(Objects.requireNonNullElse(p.getBasePath(), ""));
        return data.includes.stream()
            .filter(s -> !s.isBlank())
            .map(v -> Workspace.resolvePath(root.toString(), v))
            .filter(Objects::nonNull)
            .toList();
    }
    List<Variant> variants(PsiFile file) {
        var f = data.files.get(relative(file.getProject(), ProjectAccess.key(file)));
        return f == null || f.variants.isEmpty() ? List.of(new Variant()) : List.copyOf(f.variants);
    }
    ShaderConfig.Effective effective(PsiFile file, String id) {
        var path = relative(file.getProject(), ProjectAccess.key(file));
        var options = data.files.get(path);
        String wanted = id != null ? id : options == null ? "Default" : options.active;
        Variant v = variants(file)
                        .stream()
                        .filter(x -> x.id.equals(wanted) || x.name.equals(wanted))
                        .findFirst()
                        .orElseGet(() -> variants(file).getFirst());
        var macros = new LinkedHashMap<String, ShaderConfig.Stage>();
        data.stageMacros.forEach((k, s) -> {
            var stage = stage(s);
            if (stage != null)
                macros.put(k, stage);
        });
        return ShaderConfig.resolve(path, data.target, data.targets,
            Objects.requireNonNullElse(stage(data.fallback), ShaderConfig.Stage.VERTEX), macros,
            new ShaderConfig.Variant(
                v.name, stage(v.stage), v.target < 0 ? null : v.target, v.version, v.profile, v.macros));
    }
    static ShaderConfig.Stage stage(String value) {
        if ("TESS_EVALUATION".equals(value))
            return ShaderConfig.Stage.TESS_EVAL;
        if ("INTERSECT".equals(value))
            return ShaderConfig.Stage.INTERSECTION;
        try {
            return ShaderConfig.Stage.valueOf(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
