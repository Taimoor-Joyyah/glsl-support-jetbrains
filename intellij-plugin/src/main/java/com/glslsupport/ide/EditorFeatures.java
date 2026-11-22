package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.editorActions.*;
import com.intellij.codeInsight.hints.*;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.template.*;
import com.intellij.formatting.*;
import com.intellij.formatting.service.*;
import com.intellij.ide.structureView.*;
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase;
import com.intellij.lang.*;
import com.intellij.lang.documentation.*;
import com.intellij.lang.folding.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.*;
import com.intellij.util.ProcessingContext;
import java.nio.file.Path;
import java.util.*;
import javax.swing.Icon;
import org.jetbrains.annotations.*;

public final class EditorFeatures {
    public static final class Complete extends CompletionContributor {
        public Complete() {
            extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(GlslLanguage.INSTANCE),
                new CompletionProvider<>() {
                    protected void addCompletions(@NotNull CompletionParameters args,
                        @NotNull ProcessingContext context, @NotNull CompletionResultSet output) {
                        var file = args.getOriginalFile();
                        var w = ProjectAccess.graph(file);
                        String key = ProjectAccess.key(file);
                        var f = w.files.get(key);
                        int pos = Math.min(args.getOffset(), f.source.length());
                        int start = f.source.lastIndexOf('\n', Math.max(0, pos - 1)) + 1;
                        String line = f.source.substring(start, pos);
                        if (line.matches("\\s*#\\s*include\\s*[\"<][^\">]*")) {
                            int cut = Math.max(line.lastIndexOf('"'), line.lastIndexOf('<'));
                            String path = line.substring(cut + 1);
                            int slash = path.lastIndexOf('/');
                            String parent = slash < 0 ? "" : path.substring(0, slash + 1);
                            var dirs = new ArrayList<>(ProjectAccess.roots(file.getProject()));
                            if (file.getVirtualFile() != null)
                                dirs.addFirst(file.getVirtualFile().getParent().getPath());
                            var results = output.withPrefixMatcher(path.substring(slash + 1));
                            for (String dir : dirs) {
                                var pathToFolder = Workspace.resolvePath(dir, parent);
                                if (pathToFolder == null)
                                    continue;
                                var folder = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(
                                    pathToFolder);
                                if (folder != null)
                                    for (var child : folder.getChildren())
                                        if (child.isDirectory()
                                            || child.getFileType() == GlslLanguage.Type.INSTANCE)
                                            results.addElement(LookupElementBuilder.create(
                                                child.getName() + (child.isDirectory() ? "/" : "")));
                            }
                            return;
                        }
                        var symbols = new HashMap<String, Syntax.Symbol>();
                        for (var visible : w.visible(key))
                            for (var symbol : w.files.get(visible).symbols)
                                symbols.putIfAbsent(symbol.name(), symbol);
                        int itemIndex = f.indexAt(pos);
                        if (itemIndex > 0 && f.ident(itemIndex - 1) && f.code.get(itemIndex - 1).end() == pos)
                            itemIndex--;
                        boolean inLayout = LayoutQualifiers.item(f, itemIndex);
                        for (String name :
                            w.completions(key, pos, Settings.of(file.getProject()).getState().builtins)) {
                            if (!output.getPrefixMatcher().prefixMatches(name))
                                continue;
                            var catalog = Catalog.entries(name);
                            boolean fun = catalog.stream().anyMatch(e -> e.kind().equals("functions"));
                            var local = symbols.get(name);
                            if (local != null)
                                fun = local.kind() == Syntax.Kind.FUNCTION;
                            LookupElementBuilder item = LookupElementBuilder.create(name);
                            if (inLayout && LayoutQualifiers.known(name))
                                item =
                                    item.withIcon(com.intellij.icons.AllIcons.Nodes.Property)
                                        .withTypeText("layout qualifier", true)
                                        .withTailText(LayoutQualifiers.takesValue(name) ? " = value" : "", true);
                            else if (local != null)
                                item = item.withIcon(GlslIcons.symbol(local.kind()));
                            else if (!catalog.isEmpty())
                                item = item.withIcon(fun             ? com.intellij.icons.AllIcons.Nodes.Function
                                        : Catalog.has(name, "types") ? com.intellij.icons.AllIcons.Nodes.Class
                                                                     : com.intellij.icons.AllIcons.Nodes.Constant);
                            if (inLayout && LayoutQualifiers.known(name)) {
                                output.addElement(item);
                                continue;
                            }
                            if (local != null)
                                item = item.withTypeText(local.type(), true)
                                           .withTailText(
                                               fun ? "(" + String.join(", ", local.parameters()) + ")" : "", true);
                            else if (!catalog.isEmpty())
                                item = item.withTypeText(catalog.getFirst().type(), true)
                                           .withTailText(
                                               fun ? "(" + catalog.getFirst().parameters() + ")" : "", true);
                            if (fun)
                                item = item.withInsertHandler((c, selected) -> {
                                    int at = c.getTailOffset();
                                    var doc = c.getDocument();
                                    if (at >= doc.getTextLength() || doc.getCharsSequence().charAt(at) != '(') {
                                        doc.insertString(at, "()");
                                        c.getEditor().getCaretModel().moveToOffset(at + 1);
                                    }
                                });
                            output.addElement(PrioritizedLookupElement.withPriority(item, local == null ? 0 : 10));
                        }
                    }
                });
        }
    }
    public static final class Docs extends AbstractDocumentationProvider {
        public String generateDoc(PsiElement element, PsiElement original) {
            var e = element instanceof GlslLanguage.Named ? element : original != null ? original : element;
            if (e == null || !(e.getContainingFile() instanceof GlslLanguage.File))
                return null;
            if (e.getFirstChild() == null)
                e = e.getParent();
            String name = e.getText();
            if (name.length() > 150)
                return null;
            var w = ProjectAccess.graph(e.getContainingFile());
            var hits = w.resolve(ProjectAccess.key(e.getContainingFile()), e.getTextOffset());
            if (!hits.isEmpty()) {
                var h = hits.getFirst();
                return "<pre>" + StringUtil.escapeXmlEntities(h.symbol().display()) + "</pre><p>"
                    + StringUtil.escapeXmlEntities(h.file()) + "</p>";
            }
            var defs = Catalog.entries(name);
            if (defs.isEmpty())
                return null;
            StringBuilder html = new StringBuilder();
            for (var d : defs.stream().limit(12).toList())
                html.append("<pre>").append(StringUtil.escapeXmlEntities(d.signature())).append("</pre>");
            var d = defs.getFirst();
            html.append("<p>").append(StringUtil.escapeXmlEntities(d.description())).append("</p>");
            return html.toString();
        }
    }
    public static final class Folds extends FoldingBuilderEx {
        public FoldingDescriptor @NotNull[] buildFoldRegions(
            @NotNull PsiElement root, @NotNull Document doc, boolean quick) {
            if (!(root instanceof GlslLanguage.File file))
                return FoldingDescriptor.EMPTY_ARRAY;
            var result = new ArrayList<FoldingDescriptor>();
            for (var span : ProjectAccess.model(file).spans)
                if (span.end() > span.start() + 2
                    && doc.getLineNumber(span.start()) < doc.getLineNumber(span.end() - 1))
                    result.add(new FoldingDescriptor(root.getNode(), new TextRange(span.start(), span.end()), null,
                        span.kind().equals("block") ? "{…}" : "…"));
            return result.toArray(FoldingDescriptor.EMPTY_ARRAY);
        }
        public String getPlaceholderText(@NotNull ASTNode node) {
            return "…";
        }
        public boolean isCollapsedByDefault(@NotNull ASTNode node) {
            return false;
        }
    }
    public static final class Select implements ExtendWordSelectionHandler {
        public boolean canSelect(@NotNull PsiElement e) {
            return e.getLanguage() == GlslLanguage.INSTANCE;
        }
        public List<TextRange> select(
            @NotNull PsiElement e, @NotNull CharSequence text, int offset, @NotNull Editor editor) {
            var f = ProjectAccess.model(e.getContainingFile());
            var r = new ArrayList<TextRange>();
            for (var span : f.spans)
                if (span.start() <= offset && offset < span.end())
                    r.add(new TextRange(span.start(), span.end()));
            for (var s : f.symbols)
                if (s.start() <= offset && offset < s.end())
                    r.add(new TextRange(s.start(), s.end()));
            return r;
        }
    }
    public static final class Structure implements PsiStructureViewFactory {
        public StructureViewBuilder getStructureViewBuilder(@NotNull PsiFile f) {
            return new TreeBasedStructureViewBuilder() {
                public @NotNull StructureViewModel createStructureViewModel(Editor editor) {
                    return new StructureViewModelBase(f, editor, new Item(f, null))
                        .withSuitableClasses(GlslLanguage.Named.class);
                }
            };
        }
        static final class Item extends PsiTreeElementBase<PsiElement> {
            private final Syntax.Symbol symbol;
            Item(PsiElement e, Syntax.Symbol symbol) {
                super(e);
                this.symbol = symbol;
            }
            @Override
            public Icon getIcon(boolean open) {
                return symbol == null ? GlslIcons.FILE : GlslIcons.symbol(symbol.kind());
            }
            public String getPresentableText() {
                var e = getElement();
                return symbol != null ? symbol.display() : e instanceof PsiFile f ? f.getName() : "";
            }
            public @NotNull Collection<StructureViewTreeElement> getChildrenBase() {
                var e = getElement();
                if (e == null)
                    return List.of();
                var f = e.getContainingFile();
                var model = ProjectAccess.model(f);
                var r = new ArrayList<StructureViewTreeElement>();
                for (var s : model.symbols) {
                    if (symbol == null) {
                        if (s.local() || s.kind() == Syntax.Kind.FIELD)
                            continue;
                    } else if (!s.owner().equals(symbol.name()) || s.at() <= symbol.at() || s.at() >= symbol.end()
                        || !(symbol.kind() == Syntax.Kind.FUNCTION ? s.local() : s.kind() == Syntax.Kind.FIELD))
                        continue;
                    var leaf = f.findElementAt(s.at());
                    if (leaf != null)
                        r.add(new Item(leaf.getParent(), s));
                }
                return r;
            }
        }
    }
    public static final class Format extends AbstractDocumentFormattingService {
        public @NotNull Set<Feature> getFeatures() {
            return Set.of(Feature.FORMAT_FRAGMENTS);
        }
        public boolean canFormat(@NotNull PsiFile f) {
            return f instanceof GlslLanguage.File && Settings.of(f.getProject()).getState().format;
        }
        public void formatDocument(@NotNull Document doc, @NotNull List<TextRange> ranges,
            @NotNull FormattingContext ctx, boolean whitespace, boolean quick) {
            var file = ctx.getContainingFile();
            if (!Settings.of(file.getProject()).getState().range
                && ranges.stream().noneMatch(r -> r.getLength() == doc.getTextLength()))
                return;
            var opts = ctx.getCodeStyleSettings().getIndentOptionsByFile(file);
            var ordered = new ArrayList<>(ranges);
            ordered.sort(Comparator.comparingInt(TextRange::getStartOffset).reversed());
            for (var r : ordered) {
                var edit = Formatting.format(doc.getText(), r.getStartOffset(),
                    Math.min(r.getEndOffset(), doc.getTextLength()), opts.INDENT_SIZE, opts.USE_TAB_CHARACTER);
                if (!doc.getCharsSequence()
                        .subSequence(edit.start(), edit.end())
                        .toString()
                        .equals(edit.replacement()))
                    doc.replaceString(edit.start(), edit.end(), edit.replacement());
            }
        }
    }
    public static final class OnType extends TypedHandlerDelegate {
        public @NotNull Result charTyped(
            char c, @NotNull Project p, @NotNull Editor editor, @NotNull PsiFile file) {
            if ((c == ';' || c == '}') && file instanceof GlslLanguage.File && Settings.of(p).getState().onType) {
                var doc = editor.getDocument();
                int offset = editor.getCaretModel().getOffset();
                var opts = CodeStyle.getIndentOptions(file);
                var edit =
                    Formatting.format(doc.getText(), offset - 1, offset, opts.INDENT_SIZE, opts.USE_TAB_CHARACTER);
                if (!doc.getCharsSequence()
                        .subSequence(edit.start(), edit.end())
                        .toString()
                        .equals(edit.replacement()))
                    doc.replaceString(edit.start(), edit.end(), edit.replacement());
            }
            return Result.CONTINUE;
        }
    }
    public static final class Style extends LanguageCodeStyleSettingsProvider {
        public @NotNull Language getLanguage() {
            return GlslLanguage.INSTANCE;
        }
        public String getCodeSample(@NotNull SettingsType type) {
            return "#version 450\nvoid main() {\n    gl_Position = vec4(1.0);\n}";
        }
    }
    public static final class Comments implements Commenter {
        public String getLineCommentPrefix() {
            return "//";
        }
        public String getBlockCommentPrefix() {
            return "/*";
        }
        public String getBlockCommentSuffix() {
            return "*/";
        }
        public String getCommentedBlockCommentPrefix() {
            return null;
        }
        public String getCommentedBlockCommentSuffix() {
            return null;
        }
    }
    public static final class Templates extends TemplateContextType {
        public Templates() {
            super("GLSL");
        }
        public boolean isInContext(@NotNull TemplateActionContext c) {
            return c.getFile() instanceof GlslLanguage.File;
        }
    }
}
