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
    private record Dependencies(Set<String> inputs, boolean owner) {}
    private final Map<VirtualFile, Dependencies> dependencies = new java.util.concurrent.ConcurrentHashMap<>();
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
                if (headers != null && headers.owner())
                    for (var open : FileEditorManager.getInstance(project).getOpenFiles())
                        if (headers.inputs().contains(open.getPath()))
                            schedule(open);
                dependencies.forEach((root, included) -> {
                    if (included.inputs().contains(changed.getPath()))
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
    void track(PsiFile view, Set<String> inputs, boolean owner) {
        VirtualFile file = view.getVirtualFile();
        if (file != null) {
            if (inputs.isEmpty())
                dependencies.remove(file);
            else
                dependencies.put(file, new Dependencies(Set.copyOf(inputs), owner));
        }
    }
    List<PsiFile> owners(PsiFile file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".glslh") && !name.endsWith(".glsl"))
            return List.of();
        String path = ProjectAccess.key(file);
        return dependencies.entrySet()
            .stream()
            .filter(e -> e.getValue().owner() && e.getValue().inputs().contains(path) && e.getKey().isValid())
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
        var contexts = PipelineContexts.of(file.getProject());
        return (variant == null ? contexts.current(file) : contexts.build(file, null, "", variant)).request(op);
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
        return pipelineInput(p, name).request();
    }
    record PipelineInput(List<PipelineContexts.Context> stages, NativeCompiler.Request request) {}
    static PipelineInput pipelineInput(Project p, String name) {
        return pipelineInput(p, Settings.of(p).pipelines().get(name));
    }
    static PipelineInput pipelineInput(Project p, Settings.Pipeline pipeline) {
        if (pipeline == null || pipeline.stages.isEmpty())
            throw new IllegalArgumentException("Add shader stages to this pipeline in GLSL Support settings");
        int target = -1;
        var units = new ArrayList<NativeCompiler.Unit>();
        var stages = new ArrayList<PipelineContexts.Context>();
        var headers = new LinkedHashMap<String, String>();
        var seen = new HashSet<ShaderConfig.Stage>();
        for (var binding : pipeline.stages) {
            var context = PipelineContexts.of(p).binding(pipeline, binding, null);
            stages.add(context);
            var r = context.request(NativeCompiler.Operation.LINK);
            if (target >= 0 && target != r.vulkan())
                throw new IllegalArgumentException("Pipeline stages use different target environments");
            target = r.vulkan();
            var unit = r.units().getFirst();
            if (!seen.add(unit.config().stage()))
                throw new IllegalArgumentException("Duplicate stage " + unit.config().stage());
            units.add(unit);
            headers.putAll(r.headers());
        }
        return new PipelineInput(List.copyOf(stages),
            new NativeCompiler.Request(target, NativeCompiler.Operation.LINK, units, headers, Settings.of(p).roots(p)));
    }
}
