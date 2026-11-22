package com.glslsupport.ide;

import com.glslsupport.lang.*;
import com.intellij.extapi.psi.*;
import com.intellij.lang.*;
import com.intellij.lexer.LexerBase;
import com.intellij.openapi.fileTypes.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.stubs.*;
import com.intellij.psi.tree.*;
import com.intellij.util.IncorrectOperationException;
import java.io.*;
import java.util.*;
import javax.swing.Icon;
import org.jetbrains.annotations.*;

public final class GlslLanguage extends Language {
    public static final GlslLanguage INSTANCE = new GlslLanguage();
    private GlslLanguage() {
        super("GLSL");
    }
    public static final class Type extends LanguageFileType {
        public static final Type INSTANCE = new Type();
        private Type() {
            super(GlslLanguage.INSTANCE);
        }
        public @NotNull String getName() {
            return "GLSL";
        }
        public @NotNull String getDescription() {
            return "OpenGL and Vulkan shader";
        }
        public @NotNull String getDefaultExtension() {
            return "glsl";
        }
        public Icon getIcon() {
            return com.intellij.icons.AllIcons.FileTypes.Text;
        }
    }
    public static final class File extends PsiFileBase {
        File(FileViewProvider view) {
            super(view, INSTANCE);
        }
        public @NotNull FileType getFileType() {
            return Type.INSTANCE;
        }
    }
    public static final class Elements {
        static final IElementType KEYWORD = element("keyword"), TYPE = element("type"),
                                  BUILTIN = element("built-in");
        public static final IStubFileElementType<PsiFileStub<File>> FILE =
            new IStubFileElementType<>("glslsupport.file", INSTANCE) {
                public int getStubVersion() {
                    return 1;
                }
                public @NotNull String getExternalId() {
                    return "glslsupport.file";
                }
            };
        public static final DeclType NAME = new DeclType();
        static final IElementType WORD = element("word"), NUMBER = element("number"), COMMENT = element("comment"),
                                  DIRECTIVE = element("directive"), STRING = element("string"),
                                  SYMBOL = element("symbol"), REF = element("reference"),
                                  PREPROCESSOR = element("preprocessor"), BRACES = element("block");
        private static IElementType element(String name) {
            return new IElementType(name, INSTANCE);
        }
    }
    public static final class Adapter extends LexerBase {
        private CharSequence text = "";
        private int end, start;
        private Lexer.Token token;
        private IElementType type;
        public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int state) {
            text = buffer;
            end = endOffset;
            start = startOffset;
            locate();
        }
        private void locate() {
            if (start >= end) {
                type = null;
                token = null;
                return;
            }
            token = Lexer.next(text, start, end);
            type = switch (token.kind()) {
                case SPACE -> TokenType.WHITE_SPACE;
                case COMMENT -> Elements.COMMENT;
                case DIRECTIVE -> Elements.DIRECTIVE;
                case NUMBER -> Elements.NUMBER;
                case STRING -> Elements.STRING;
                case WORD -> Appearance.word(token.text());
                case SYMBOL -> Elements.SYMBOL;
                case BAD -> TokenType.BAD_CHARACTER;
            };
        }
        public int getState() {
            return 0;
        }
        public IElementType getTokenType() {
            return type;
        }
        public int getTokenStart() {
            return start;
        }
        public int getTokenEnd() {
            return token == null ? end : token.end();
        }
        public void advance() {
            start = getTokenEnd();
            locate();
        }
        public @NotNull CharSequence getBufferSequence() {
            return text;
        }
        public int getBufferEnd() {
            return end;
        }
    }
    public static final class Parser implements ParserDefinition {
        public @NotNull com.intellij.lexer.Lexer createLexer(Project p) {
            return new Adapter();
        }
        public @NotNull PsiParser createParser(Project p) {
            return (root, builder) -> {
                var head = builder.mark();
                var syntax = new Syntax(builder.getOriginalText().toString());
                var braces = new ArrayDeque<PsiBuilder.Marker>();
                while (!builder.eof()) {
                    var type = builder.getTokenType();
                    String tok = builder.getTokenText();
                    if ("{".equals(tok)) {
                        braces.push(builder.mark());
                        builder.advanceLexer();
                    } else if ("}".equals(tok)) {
                        builder.advanceLexer();
                        if (!braces.isEmpty())
                            braces.pop().done(Elements.BRACES);
                    } else if (type == Elements.WORD || type == Elements.KEYWORD || type == Elements.TYPE
                        || type == Elements.BUILTIN || type == Elements.DIRECTIVE) {
                        int pos = builder.getCurrentOffset();
                        var marker = builder.mark();
                        builder.advanceLexer();
                        marker.done(type == Elements.DIRECTIVE    ? Elements.PREPROCESSOR
                                : syntax.declaration(pos) != null ? Elements.NAME
                                                                  : Elements.REF);
                    } else
                        builder.advanceLexer();
                }
                while (!braces.isEmpty()) braces.pop().done(Elements.BRACES);
                head.done(root);
                return builder.getTreeBuilt();
            };
        }
        public @NotNull IFileElementType getFileNodeType() {
            return Elements.FILE;
        }
        public @NotNull TokenSet getCommentTokens() {
            return TokenSet.create(Elements.COMMENT);
        }
        public @NotNull TokenSet getStringLiteralElements() {
            return TokenSet.create(Elements.STRING);
        }
        public @NotNull PsiElement createElement(ASTNode node) {
            return node.getElementType() == Elements.NAME ? new Named(node) : new Element(node);
        }
        public @NotNull PsiFile createFile(@NotNull FileViewProvider view) {
            return new File(view);
        }
    }
    public static final class NameStub extends StubBase<Named> {
        final String name;
        NameStub(StubElement<?> parent, String name) {
            super(parent, Elements.NAME);
            this.name = name;
        }
    }
    public static final class DeclType extends IStubElementType<NameStub, Named> {
        DeclType() {
            super("declaration", INSTANCE);
        }
        public Named createPsi(@NotNull NameStub stub) {
            return new Named(stub);
        }
        public @NotNull NameStub createStub(@NotNull Named psi, StubElement<? extends PsiElement> parent) {
            return new NameStub(parent, psi.getName());
        }
        public @NotNull String getExternalId() {
            return "glslsupport.declaration";
        }
        public void serialize(@NotNull NameStub stub, @NotNull StubOutputStream data) throws IOException {
            data.writeName(stub.name);
        }
        public @NotNull NameStub deserialize(@NotNull StubInputStream in, StubElement parent) throws IOException {
            return new NameStub(parent, in.readNameString());
        }
        public void indexStub(@NotNull NameStub stub, @NotNull IndexSink sink) {
            sink.occurrence(SymbolIndex.KEY, stub.name);
        }
    }
    public static final class SymbolIndex extends StringStubIndexExtension<Named> {
        public static final StubIndexKey<String, Named> KEY = StubIndexKey.createIndexKey("glslsupport.names");
        public @NotNull StubIndexKey<String, Named> getKey() {
            return KEY;
        }
    }
    static PsiElement replaceName(PsiElement element, String name) {
        if (!Editing.identifier(name))
            throw new IncorrectOperationException("Invalid GLSL identifier");
        var leaf = element.getFirstChild();
        if (leaf instanceof LeafPsiElement token)
            token.replaceWithText(name);
        else
            throw new IncorrectOperationException("Missing GLSL identifier");
        return element;
    }
    public static final class Named extends StubBasedPsiElementBase<NameStub>
        implements StubBasedPsiElement<NameStub>, PsiNameIdentifierOwner, com.intellij.navigation.NavigationItem {
        Named(ASTNode node) {
            super(node);
        }
        Named(NameStub stub) {
            super(stub, Elements.NAME);
        }
        public String getName() {
            var stub = getGreenStub();
            return stub == null ? getText() : stub.name;
        }
        public PsiElement setName(@NotNull String name) {
            return replaceName(this, name);
        }
        public PsiElement getNameIdentifier() {
            return getFirstChild();
        }
        public @NotNull com.intellij.psi.search.SearchScope getUseScope() {
            return com.intellij.psi.search.GlobalSearchScope.projectScope(getProject());
        }
        public String toString() {
            return "GLSL declaration " + getName();
        }
    }
    public static final class Element extends ASTWrapperPsiElement {
        Element(ASTNode node) {
            super(node);
        }
        public PsiReference getReference() {
            if (getNode().getElementType() == Elements.REF)
                return new Ref(this, new TextRange(0, getTextLength()), false);
            if (getNode().getElementType() == Elements.PREPROCESSOR) {
                var model = ProjectAccess.model(getContainingFile());
                for (var inc : model.includes)
                    if (getTextRange().contains(inc.start()))
                        return new Ref(
                            this, new TextRange(inc.start() - getTextOffset(), inc.end() - getTextOffset()), true);
            }
            return null;
        }
    }
    static final class Ref extends PsiPolyVariantReferenceBase<Element> {
        final boolean include;
        Ref(Element e, TextRange range, boolean include) {
            super(e, range);
            this.include = include;
        }
        public ResolveResult @NotNull[] multiResolve(boolean incomplete) {
            var element = getElement();
            var file = element.getContainingFile();
            var graph = ProjectAccess.graph(file);
            var key = ProjectAccess.key(file);
            var targets = new ArrayList<ResolveResult>();
            if (include) {
                for (var inc : graph.files.get(key).includes)
                    if (element.getTextRange().contains(inc.start())) {
                        var hit = graph.include(key, inc);
                        var psi = hit == null ? null : ProjectAccess.psi(element.getProject(), hit);
                        if (psi != null)
                            targets.add(new PsiElementResolveResult(psi));
                    }
            } else
                for (var hit : graph.resolve(key, element.getTextOffset())) {
                    var named = ProjectAccess.named(file, hit);
                    if (named != null)
                        targets.add(new PsiElementResolveResult(named));
                }
            return targets.toArray(ResolveResult.EMPTY_ARRAY);
        }
        public PsiElement handleElementRename(@NotNull String name) {
            if (include)
                return super.handleElementRename(name);
            return replaceName(getElement(), name);
        }
    }
}
