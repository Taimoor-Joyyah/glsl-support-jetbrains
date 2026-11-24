package com.glslsupport.ide;

import com.glslsupport.lang.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ProjectAccess {
    private ProjectAccess() {}
    static Syntax model(PsiFile file) {
        return CachedValuesManager.getCachedValue(
            file, () -> CachedValueProvider.Result.create(new Syntax(file.getText()), file));
    }
    static String key(PsiFile file) {
        var v = file.getOriginalFile().getVirtualFile();
        return v == null ? file.getName() : Workspace.path(v.getPath());
    }
    static PsiFile psi(Project project, String path) {
        var v = LocalFileSystem.getInstance().findFileByPath(path);
        return v == null || !v.isValid() ? null : PsiManager.getInstance(project).findFile(v);
    }
    static GlslLanguage.Named named(PsiFile origin, Workspace.Location hit) {
        var file = key(origin).equals(hit.file()) ? origin : psi(origin.getProject(), hit.file());
        if (file == null)
            return null;
        var leaf = file.findElementAt(hit.symbol().at());
        return leaf == null ? null : PsiTreeUtil.getParentOfType(leaf, GlslLanguage.Named.class);
    }
    static List<String> roots(Project project) {
        return Settings.of(project).roots(project);
    }
    static Workspace graph(PsiFile file) {
        return PipelineContexts.of(file.getProject()).current(file).graph();
    }
    static Workspace graph(PsiFile file, ShaderConfig.Effective config) {
        var sources = sources(file);
        return sources.workspaces.computeIfAbsent(config,
            c -> new Workspace(sources.files, sources.roots, c, sources.root));
    }
    static final class SourceFiles {
        final String root;
        final List<String> roots;
        final Map<String, Syntax> files = new LinkedHashMap<>();
        final List<PsiFile> dependencies = new ArrayList<>();
        final Map<ShaderConfig.Effective, Workspace> workspaces = new ConcurrentHashMap<>();
        SourceFiles(String root, List<String> roots) {
            this.root = root;
            this.roots = roots;
        }
        void add(String path, PsiFile file) {
            files.put(path, model(file));
            dependencies.add(file);
        }
    }
    static SourceFiles sources(PsiFile file) {
        var project = file.getProject();
        var owner = PipelineContexts.of(project).sourceCache(file);
        return CachedValuesManager.getManager(project).getCachedValue(owner, () -> discover(file));
    }
    private static CachedValueProvider.Result<SourceFiles> discover(PsiFile file) {
        var project = file.getProject();
        var sources = new SourceFiles(key(file), roots(project));
        sources.add(sources.root, file);
        var queue = new ArrayDeque<String>();
        queue.add(sources.root);
        while (!queue.isEmpty()) {
            ProgressManager.checkCanceled();
            String here = queue.remove();
            for (var inc : sources.files.get(here).includes) {
                var dirs = new ArrayList<String>();
                var parent = Path.of(here).getParent();
                if (!inc.system())
                    dirs.add(parent == null ? "" : parent.toString());
                dirs.addAll(sources.roots);
                for (var dir : dirs) {
                    var k = Workspace.resolvePath(dir, inc.path());
                    if (k == null)
                        continue;
                    if (sources.files.containsKey(k))
                        break;
                    var included = psi(project, k);
                    if (included != null) {
                        sources.add(k, included);
                        queue.add(k);
                        break;
                    }
                }
            }
        }
        var deps = new ArrayList<Object>(sources.dependencies);
        deps.add(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS);
        return CachedValueProvider.Result.create(sources, deps.toArray());
    }
}
