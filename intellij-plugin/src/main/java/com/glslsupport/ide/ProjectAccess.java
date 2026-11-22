package com.glslsupport.ide;

import com.glslsupport.lang.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import java.nio.file.Path;
import java.util.*;

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
        return v == null ? null : PsiManager.getInstance(project).findFile(v);
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
        return CachedValuesManager.getCachedValue(file, () -> {
            var config = Settings.of(file.getProject());
            var graph = graph(file, config.effective(file, null).editorDefines());
            List<Object> deps =
                new ArrayList<>(List.of(file, config.changes, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS));
            for (String key : graph.files.keySet()) {
                var dep = psi(file.getProject(), key);
                if (dep != null && dep != file)
                    deps.add(dep);
            }
            return CachedValueProvider.Result.create(graph, deps.toArray());
        });
    }
    static Workspace graph(PsiFile file, Map<String, String> defines) {
        var project = file.getProject();
        var path = key(file);
        var map = new LinkedHashMap<String, Syntax>();
        map.put(path, model(file));
        var queue = new ArrayDeque<String>();
        queue.add(path);
        var roots = roots(project);
        while (!queue.isEmpty()) {
            String here = queue.remove();
            for (var inc : map.get(here).includes) {
                var dirs = new ArrayList<String>();
                var parent = Path.of(here).getParent();
                if (!inc.system())
                    dirs.add(parent == null ? "" : parent.toString());
                dirs.addAll(roots);
                for (var dir : dirs) {
                    var k = Workspace.resolvePath(dir, inc.path());
                    if (k == null)
                        continue;
                    if (map.containsKey(k))
                        break;
                    var included = psi(project, k);
                    if (included != null) {
                        map.put(k, model(included));
                        queue.add(k);
                        break;
                    }
                }
            }
        }
        return new Workspace(map, roots, defines, path);
    }
}
