package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.util.xmlb.annotations.*;
import java.nio.file.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

@State(name = "GlslSupport", storages = @Storage("glslSupport.xml"))
public final class Settings implements PersistentStateComponent<Settings.Data> {
    public static final class Variant {
        public String id = "Default", name = "Default", stage = "", profile = "core";
        public int target = -1, version;
        @com.intellij.util.xmlb.annotations.MapAnnotation(sortBeforeSave = false)
        public Map<String, String> macros = new LinkedHashMap<>();
    }
    public static final class FileOptions {
        public String active = "Default";
        public List<Variant> variants = new ArrayList<>();
    }
    @Tag("stage")
    public static final class StageFile {
        @Attribute public String stage = "", file = "", variant = "";
        public StageFile() {}
        public StageFile(String stage, String file, String variant) {
            this.stage = stage;
            this.file = file;
            this.variant = variant;
        }
    }
    @Tag("pipeline")
    public static final class Pipeline {
        @Attribute public String name = "", type = "Graphics";
        @Attribute public int target = -1;
        @XCollection(propertyElementName = "stages", elementName = "stage", style = XCollection.Style.v2)
        public List<StageFile> stages = new ArrayList<>();
        @XMap(propertyElementName = "definitions", entryTagName = "define", keyAttributeName = "name",
            valueAttributeName = "value")
        public Map<String, String> definitions = new LinkedHashMap<>();
        Pipeline copy() {
            var result = new Pipeline();
            result.name = name;
            result.type = type;
            result.target = target;
            stages.forEach(s -> result.stages.add(new StageFile(s.stage, s.file, s.variant)));
            result.definitions.putAll(definitions);
            return result;
        }
        List<Object> snapshot() {
            return List.of(name, type, target,
                stages.stream().map(s -> List.of(s.stage, s.file, s.variant)).toList(),
                new ArrayList<>(definitions.entrySet()));
        }
    }
    public static final class Data {
        public int target;
        public int validationDelaySeconds = 6;
        public String fallback = "VERTEX";
        public List<String> includes = new ArrayList<>();
        public Map<String, Integer> targets = new LinkedHashMap<>();
        public Map<String, String> stageMacros = new LinkedHashMap<>();
        public Map<String, FileOptions> files = new LinkedHashMap<>();
        public Map<String, List<String>> pipelines = new LinkedHashMap<>();
        @XCollection(
            propertyElementName = "pipelineDefinitions", elementName = "pipeline", style = XCollection.Style.v2)
        public List<Pipeline> pipelineDefinitions = new ArrayList<>();
        public boolean diagnostics = true, builtins = true, colors = true, inlays = true, signatures = true,
                       semantic = true, onType = true, range = true, format = true, imported = false;
        public Data() {
            for (var s : ShaderConfig.Stage.values()) stageMacros.put(s.name() + "_SHADER", s.name());
        }
    }
    private volatile Data data = new Data();
    private final Project project;
    public Settings(Project project) {
        this.project = project;
    }
    final com.intellij.openapi.util.SimpleModificationTracker changes =
        new com.intellij.openapi.util.SimpleModificationTracker();
    void changed() {
        changes.incModificationCount();
        PipelineContexts.notifyChanged(project);
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
    public Map<String, Pipeline> pipelines() {
        var result = new LinkedHashMap<String, Pipeline>();
        data.pipelines.forEach((name, paths) -> {
            var p = new Pipeline();
            p.name = name;
            p.type = "Custom";
            for (String path : paths) {
                var parts = path.split("#", 2);
                p.stages.add(new StageFile("", parts[0], parts.length > 1 ? parts[1] : ""));
            }
            result.put(name, p);
        });
        for (var pipeline : data.pipelineDefinitions) result.put(pipeline.name, pipeline);
        return result;
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
    Variant variant(PsiFile file, String id) {
        var path = relative(file.getProject(), ProjectAccess.key(file));
        var options = data.files.get(path);
        String wanted = id != null ? id : options == null ? "Default" : options.active;
        return variants(file)
                        .stream()
                        .filter(x -> x.id.equals(wanted) || x.name.equals(wanted))
                        .findFirst()
                        .orElseGet(() -> variants(file).getFirst());
    }
    ShaderConfig.Effective effective(PsiFile file, String id) {
        var path = relative(file.getProject(), ProjectAccess.key(file));
        Variant v = variant(file, id);
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
