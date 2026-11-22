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
    private final Set<VirtualFile> pending = new HashSet<>();
    private final Project project;
    public CompilerSupport(Project project) {
        this.project = project;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile changed = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (changed != null)
                    headerChanged(changed.getPath());
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override
                public void fileClosed(@NotNull FileEditorManager manager, @NotNull VirtualFile file) {
                    dependencies.remove(file);
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
    private void headerChanged(String path) {
        synchronized (pending) {
            boolean scheduled = !pending.isEmpty();
            dependencies.forEach((root, headers) -> {
                if (headers.contains(path))
                    pending.add(root);
            });
            if (scheduled || pending.isEmpty())
                return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed())
                return;
            PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(() -> {
                List<VirtualFile> roots;
                synchronized (pending) {
                    roots = List.copyOf(pending);
                    pending.clear();
                }
                for (var v : roots)
                    if (v.isValid() && FileEditorManager.getInstance(project).isFileOpen(v)) {
                        var file = PsiManager.getInstance(project).findFile(v);
                        if (file != null)
                            DaemonCodeAnalyzer.getInstance(project).restart(file, "GLSL include changed");
                    }
            });
        }, project.getDisposed());
    }
    @Override
    public void dispose() {
        dependencies.clear();
    }
    public static NativeCompiler.Request request(PsiFile file, String variant, NativeCompiler.Operation op) {
        var settings = Settings.of(file.getProject());
        var effective = settings.effective(file, variant);
        var graph =
            variant == null ? ProjectAccess.graph(file) : ProjectAccess.graph(file, effective.editorDefines());
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
        var members = settings.getState().pipelines.get(name);
        if (members == null || members.isEmpty())
            throw new IllegalArgumentException("Add shader stages to this pipeline in GLSL Support settings");
        int target = -1;
        var units = new ArrayList<NativeCompiler.Unit>();
        var headers = new LinkedHashMap<String, String>();
        var seen = new HashSet<ShaderConfig.Stage>();
        for (String entry : members) {
            String[] parts = entry.split("#", 2);
            var path = java.nio.file.Path.of(Objects.requireNonNullElse(p.getBasePath(), "")).resolve(parts[0]);
            var f = ProjectAccess.psi(p, Workspace.path(path.toString()));
            if (f == null)
                throw new IllegalArgumentException("Missing stage file " + parts[0]);
            var r = request(f, parts.length > 1 ? parts[1] : null, NativeCompiler.Operation.CHECK);
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
