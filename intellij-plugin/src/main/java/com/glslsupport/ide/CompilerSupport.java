package com.glslsupport.ide;

import com.glslsupport.bridge.*;
import com.glslsupport.lang.*;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class CompilerSupport implements Disposable {
    private final AnalysisCache cache = new AnalysisCache(32L << 20, NativeCompiler::compile);
    private final Map<VirtualFile, Set<String>> dependencies = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<VirtualFile, Runnable> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final com.intellij.util.Alarm alarm =
        new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this);
    private final Project project;
    public CompilerSupport(Project project) {
        this.project = project;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile changed = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (changed == null)
                    return;
                if (changed.getFileType() == GlslLanguage.Type.INSTANCE
                    && FileEditorManager.getInstance(project).isFileOpen(changed))
                    schedule(changed);
                var headers = dependencies.get(changed);
                if (headers != null)
                    for (var open : FileEditorManager.getInstance(project).getOpenFiles())
                        if (headers.contains(open.getPath()))
                            schedule(open);
                dependencies.forEach((root, included) -> {
                    if (included.contains(changed.getPath()))
                        schedule(root);
                });
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override
                public void fileClosed(@NotNull FileEditorManager manager, @NotNull VirtualFile file) {
                    dependencies.remove(file);
                    ready(file);
                }
            });
    }
    void track(PsiFile root, Set<String> headers) {
        VirtualFile file = root.getVirtualFile();
        if (file != null) {
            if (headers.isEmpty())
                dependencies.remove(file);
            else
                dependencies.put(file, Set.copyOf(headers));
        }
    }
    List<PsiFile> owners(PsiFile file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".glslh") && !name.endsWith(".glsl"))
            return List.of();
        String path = ProjectAccess.key(file);
        return dependencies.entrySet()
            .stream()
            .filter(e -> e.getValue().contains(path) && e.getKey().isValid())
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(VirtualFile::getPath)))
            .map(e -> PsiManager.getInstance(project).findFile(e.getKey()))
            .filter(Objects::nonNull)
            .toList();
    }
    private synchronized void schedule(VirtualFile file) {
        ready(file);
        if (project.isDisposed())
            return;
        var options = Settings.of(project).getState();
        if (!options.diagnostics)
            return;
        var task = new Runnable() {
            public void run() {
                if (!pending.remove(file, this) || project.isDisposed())
                    return;
                PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(() -> {
                    if (!file.isValid() || pending.containsKey(file)
                        || !FileEditorManager.getInstance(project).isFileOpen(file))
                        return;
                    var psi = PsiManager.getInstance(project).findFile(file);
                    if (psi != null)
                        DaemonCodeAnalyzer.getInstance(project).restart(psi, "Validate edited GLSL source");
                });
            }
        };
        pending.put(file, task);
        int delay = options.validationDelaySeconds == 0 ? 0 : Math.clamp(options.validationDelaySeconds, 5, 10);
        alarm.addRequest(task, 1000 * delay);
    }
    boolean waiting(VirtualFile file) {
        return file != null && pending.containsKey(file);
    }
    synchronized void ready(VirtualFile file) {
        var previous = pending.remove(file);
        if (previous != null)
            alarm.cancelRequest(previous);
    }
    @Override
    public void dispose() {
        pending.clear();
        dependencies.clear();
    }
    public static NativeCompiler.Request request(PsiFile file, String variant, NativeCompiler.Operation op) {
        var settings = Settings.of(file.getProject());
        var effective = settings.effective(file, variant);
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".glslh") && effective.version() == 0
            && !ProjectAccess.model(file).hasVersion())
            effective = new ShaderConfig.Effective(
                effective.stage(), effective.vulkan(), 460, "core", effective.defines());
        return request(file, op, effective, variant == null);
    }
    private static NativeCompiler.Request request(
        PsiFile file, NativeCompiler.Operation op, ShaderConfig.Effective effective, boolean useActiveGraph) {
        var graph =
            useActiveGraph ? ProjectAccess.graph(file) : ProjectAccess.graph(file, effective.editorDefines());
        var key = ProjectAccess.key(file);
        var headers = new LinkedHashMap<String, String>();
        graph.files.forEach((path, model) -> {
            if (!key.equals(path))
                headers.put(path, model.source);
        });
        return new NativeCompiler.Request(effective.vulkan(), op,
            List.of(new NativeCompiler.Unit(key, graph.files.get(key).source, effective)), headers,
            graphRoots(file));
    }
    static List<String> graphRoots(PsiFile file) {
        return ProjectAccess.roots(file.getProject());
    }
    public NativeCompiler.Result analyze(NativeCompiler.Request req) {
        var jar = PathManager.getJarForClass(CompilerSupport.class);
        if (jar == null)
            throw new IllegalStateException("GLSL Support plugin path unavailable");
        NativeCompiler.load(jar.resolveSibling("native/win-x64/glsl_support.dll"));
        return cache.analyze(req);
    }
    static CompilerSupport of(Project p) {
        return p.getService(CompilerSupport.class);
    }
    public static NativeCompiler.Request pipeline(Project p, String name) {
        var settings = Settings.of(p);
        var pipeline = settings.pipelines().get(name);
        if (pipeline == null || pipeline.stages.isEmpty())
            throw new IllegalArgumentException("Add shader stages to this pipeline in GLSL Support settings");
        int target = -1;
        var units = new ArrayList<NativeCompiler.Unit>();
        var headers = new LinkedHashMap<String, String>();
        var seen = new HashSet<ShaderConfig.Stage>();
        for (var binding : pipeline.stages) {
            var path =
                java.nio.file.Path.of(Objects.requireNonNullElse(p.getBasePath(), "")).resolve(binding.file);
            var f = ProjectAccess.psi(p, Workspace.path(path.toString()));
            if (f == null)
                throw new IllegalArgumentException("Missing stage file " + binding.file);
            if (!binding.variant.isBlank()
                && settings.variants(f).stream().noneMatch(
                    v -> v.id.equals(binding.variant) || v.name.equals(binding.variant)))
                throw new IllegalArgumentException("Unknown variant " + binding.variant + " in " + binding.file);
            var base = settings.effective(f, binding.variant.isBlank() ? null : binding.variant);
            var assigned = binding.stage.isBlank() ? base.stage() : Settings.stage(binding.stage);
            if (assigned == null)
                throw new IllegalArgumentException("Unknown shader stage " + binding.stage);
            var defines = new LinkedHashMap<>(base.defines());
            defines.putAll(pipeline.definitions);
            var config = new ShaderConfig.Effective(assigned,
                pipeline.target < 0 ? base.vulkan() : pipeline.target, base.version(), base.profile(), defines);
            var r = request(f, NativeCompiler.Operation.CHECK, config, false);
            if (target >= 0 && target != r.vulkan())
                throw new IllegalArgumentException("Pipeline stages use different target environments");
            target = r.vulkan();
            var unit = r.units().getFirst();
            if (!seen.add(unit.config().stage()))
                throw new IllegalArgumentException("Duplicate stage " + unit.config().stage());
            units.add(unit);
            headers.putAll(r.headers());
        }
        return new NativeCompiler.Request(
            target, NativeCompiler.Operation.CHECK, units, headers, settings.roots(p));
    }
}
