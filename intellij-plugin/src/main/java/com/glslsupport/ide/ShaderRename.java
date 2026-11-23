package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.util.containers.MultiMap;
import java.util.*;
import org.jetbrains.annotations.*;

public final class ShaderRename extends RenamePsiElementProcessor {
    @Override
    public boolean canProcessElement(@NotNull PsiElement e) {
        return e instanceof GlslLanguage.Named;
    }
    private record
        Scan(Set<PsiElement> declarations, Map<Workspace.Use, PsiReference> references, Set<String> problems) {}
    private static Scan scan(PsiElement e, String proposed) {
        var decls = new HashSet<PsiElement>();
        var refs = new LinkedHashMap<Workspace.Use, PsiReference>();
        var problems = new LinkedHashSet<String>();
        var original = e.getContainingFile();
        var project = e.getProject();
        String targetPath = ProjectAccess.key(original);
        var symbol = ProjectAccess.model(original).declaration(e.getTextOffset());
        if (symbol == null)
            return new Scan(decls, refs, Set.of("The declaration changed"));
        var target = new Workspace.Location(targetPath, symbol);
        var other = new HashSet<Workspace.Use>();
        var candidates =
            FileTypeIndex.getFiles(GlslLanguage.Type.INSTANCE, GlobalSearchScope.projectScope(project));
        for (var candidate : candidates) {
            ProgressManager.checkCanceled();
            var root = PsiManager.getInstance(project).findFile(candidate);
            if (root == null)
                continue;
            for (var variant : Settings.of(project).variants(root)) {
                var config = Settings.of(project).effective(root, variant.id);
                var w = ProjectAccess.graph(root, config);
                if (!w.files.containsKey(targetPath))
                    continue;
                if (proposed != null) {
                    try {
                        Editing.rename(w, target, proposed);
                    } catch (IllegalArgumentException err) {
                        if (!err.getMessage().equals("No writable declaration"))
                            problems.add(err.getMessage());
                    }
                }
                for (var entry : w.files.entrySet()) {
                    var file = entry.getKey();
                    var model = entry.getValue();
                    var psi = ProjectAccess.psi(project, file);
                    if (psi == null)
                        continue;
                    for (var token : model.code) {
                        if (!token.text().equals(symbol.name()) || !w.active(file, token.start()))
                            continue;
                        var use = new Workspace.Use(file, token.start(), token.end());
                        var hits = w.resolve(file, token.start());
                        if (hits.stream().noneMatch(hit -> w.equivalent(hit, target))) {
                            other.add(use);
                            continue;
                        }
                        if (hits.stream().anyMatch(hit -> !w.equivalent(hit, target)))
                            problems.add("A reference has multiple possible declarations");
                        if (model.declaration(token.start()) != null) {
                            var named = ProjectAccess.named(
                                psi, new Workspace.Location(file, model.declaration(token.start())));
                            if (named != null)
                                decls.add(named);
                        } else {
                            var ref = psi.findReferenceAt(token.start());
                            if (ref != null)
                                refs.put(use, ref);
                        }
                    }
                }
            }
        }
        if (refs.keySet().stream().anyMatch(other::contains))
            problems.add("A shader variant resolves this name to another declaration");
        return new Scan(decls, refs, problems);
    }
    @Override
    public void prepareRenaming(
        @NotNull PsiElement e, @NotNull String name, @NotNull Map<PsiElement, String> map) {
        for (var d : scan(e, null).declarations)
            if (d != e)
                map.put(d, name);
    }
    @Override
    public void findExistingNameConflicts(
        @NotNull PsiElement e, @NotNull String name, @NotNull MultiMap<PsiElement, String> conflicts) {
        for (var problem : scan(e, name).problems) conflicts.putValue(e, problem);
    }
    @Override
    public @NotNull Collection<PsiReference> findReferences(
        @NotNull PsiElement e, @NotNull SearchScope scope, boolean comments) {
        var refs = new ArrayList<PsiReference>();
        for (var ref : scan(e, null).references.values())
            if (PsiSearchScopeUtil.isInScope(scope, ref.getElement()))
                refs.add(ref);
        return refs;
    }
}
