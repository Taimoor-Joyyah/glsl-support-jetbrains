package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider;
import com.intellij.ide.hierarchy.*;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.search.searches.*;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.util.*;
import java.util.*;
import javax.swing.JTree;
import org.jetbrains.annotations.*;

public final class SymbolNavigation {
    static GlslLanguage.Named named(PsiElement element) {
        if (element instanceof GlslLanguage.Named n)
            return n;
        if (element == null)
            return null;
        if (element.getParent() instanceof GlslLanguage.Named n)
            return n;
        var ref = element.getReference();
        if (ref == null && element.getParent() != null)
            ref = element.getParent().getReference();
        return ref != null && ref.resolve() instanceof GlslLanguage.Named n ? n : null;
    }
    static Syntax.Symbol symbol(PsiElement e) {
        return e == null ? null : ProjectAccess.model(e.getContainingFile()).declaration(e.getTextOffset());
    }
    static GlslLanguage.Named caller(PsiElement element) {
        if (element == null || !(element.getContainingFile() instanceof GlslLanguage.File file))
            return null;
        var model = ProjectAccess.model(file);
        for (var s : model.symbols)
            if (s.kind() == Syntax.Kind.FUNCTION && s.start() <= element.getTextOffset()
                && element.getTextOffset() < s.end())
                return ProjectAccess.named(file, new Workspace.Location(ProjectAccess.key(file), s));
        return null;
    }
    public static final class Types implements TypeDeclarationProvider {
        public PsiElement[] getSymbolTypeDeclarations(@NotNull PsiElement e) {
            var decl = named(e);
            var s = symbol(decl);
            if (s == null)
                return null;
            var file = decl.getContainingFile();
            var w = ProjectAccess.graph(file);
            var hits = new ArrayList<PsiElement>();
            for (var entry : w.files.entrySet())
                for (var type : entry.getValue().symbols)
                    if ((type.kind() == Syntax.Kind.TYPE || type.kind() == Syntax.Kind.BLOCK)
                        && type.name().equals(s.type())) {
                        var n = ProjectAccess.named(file, new Workspace.Location(entry.getKey(), type));
                        if (n != null)
                            hits.add(n);
                    }
            return hits.isEmpty() ? null : hits.toArray(PsiElement.EMPTY_ARRAY);
        }
    }
    public static final class Implementations
        implements QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
        public boolean execute(@NotNull DefinitionsScopedSearch.SearchParameters params,
            @NotNull Processor<? super PsiElement> consumer) {
            var decl = named(params.getElement());
            var s = symbol(decl);
            if (s == null || s.kind() != Syntax.Kind.FUNCTION)
                return true;
            var path = ProjectAccess.key(decl.getContainingFile());
            for (var item : StubIndex.getElements(GlslLanguage.SymbolIndex.KEY, s.name(), decl.getProject(),
                     GlobalSearchScope.projectScope(decl.getProject()), GlslLanguage.Named.class)) {
                var f = item.getContainingFile();
                var model = ProjectAccess.model(f);
                var def = symbol(item);
                if (def == null || def.kind() != Syntax.Kind.FUNCTION || !def.parameters().equals(s.parameters())
                    || !model.source.substring(def.start(), def.end()).contains("{"))
                    continue;
                if ((ProjectAccess.graph(f).visible(ProjectAccess.key(f)).contains(path)
                        || ProjectAccess.graph(decl.getContainingFile())
                            .visible(path)
                            .contains(ProjectAccess.key(f)))
                    && !consumer.process(item))
                    return false;
            }
            return true;
        }
    }
    public static final class Hierarchy implements HierarchyProvider {
        public PsiElement getTarget(@NotNull DataContext ctx) {
            var file = CommonDataKeys.PSI_FILE.getData(ctx);
            if (!(file instanceof GlslLanguage.File))
                return null;
            var editor = CommonDataKeys.EDITOR.getData(ctx);
            var e = CommonDataKeys.PSI_ELEMENT.getData(ctx);
            if (e == null && editor != null)
                e = file.findElementAt(editor.getCaretModel().getOffset());
            var n = named(e);
            return n != null && symbol(n) != null && symbol(n).kind() == Syntax.Kind.FUNCTION ? n : caller(e);
        }
        public @NotNull HierarchyBrowser createHierarchyBrowser(@NotNull PsiElement target) {
            return new Browser(target);
        }
        public void browserActivated(@NotNull HierarchyBrowser b) {
            ((Browser) b).changeView(CallHierarchyBrowserBase.getCallerType());
        }
    }
    static final class Browser extends CallHierarchyBrowserBase {
        Browser(PsiElement e) {
            super(e.getProject(), e);
        }
        protected void createTrees(@NotNull Map<? super String, ? super JTree> trees) {
            trees.put(getCallerType(), createTree(false));
            trees.put(getCalleeType(), createTree(false));
        }
        protected PsiElement getElementFromDescriptor(@NotNull HierarchyNodeDescriptor d) {
            return d.getPsiElement();
        }
        protected boolean isApplicableElement(@NotNull PsiElement e) {
            var s = symbol(e);
            return s != null && s.kind() == Syntax.Kind.FUNCTION;
        }
        protected Comparator<NodeDescriptor<?>> getComparator() {
            return Comparator.comparing(Object::toString);
        }
        protected HierarchyTreeStructure createHierarchyTreeStructure(
            @NotNull String mode, @NotNull PsiElement e) {
            return new HierarchyTreeStructure(e.getProject(), new Node(null, e, true)) {
                protected Object @NotNull[] buildChildren(@NotNull HierarchyNodeDescriptor d) {
                    var root = d.getPsiElement();
                    var s = symbol(root);
                    if (s == null)
                        return HierarchyNodeDescriptor.EMPTY_ARRAY;
                    var nodes = new LinkedHashSet<PsiElement>();
                    var f = root.getContainingFile();
                    if (mode.equals(getCallerType())) {
                        for (var ref : ReferencesSearch.search(root).findAll()) {
                            var call = caller(ref.getElement());
                            if (call != null)
                                nodes.add(call);
                        }
                    } else {
                        var m = ProjectAccess.model(f);
                        var w = ProjectAccess.graph(f);
                        String k = ProjectAccess.key(f);
                        for (var t : m.code) {
                            if (t.start() <= s.at() || t.start() >= s.end() || !w.active(k, t.start()))
                                continue;
                            int i = m.indexAt(t.start());
                            if (!m.word(i + 1).equals("("))
                                continue;
                            for (var loc : w.resolve(k, t.start()))
                                if (loc.symbol().kind() == Syntax.Kind.FUNCTION) {
                                    var n = ProjectAccess.named(f, loc);
                                    if (n != null)
                                        nodes.add(n);
                                }
                        }
                    }
                    return nodes.stream().map(x -> new Node(d, x, false)).toArray();
                }
            };
        }
    }
    static final class Node extends HierarchyNodeDescriptor {
        Node(NodeDescriptor parent, PsiElement e, boolean base) {
            super(e.getProject(), parent, e, base);
        }
        public boolean update() {
            super.update();
            var e = getPsiElement();
            if (e == null)
                return false;
            var s = symbol(e);
            myHighlightedText = new com.intellij.openapi.roots.ui.util.CompositeAppearance();
            myHighlightedText.getEnding().addText(s == null ? e.getText() : s.display());
            myName = myHighlightedText.getText();
            return true;
        }
    }
}
