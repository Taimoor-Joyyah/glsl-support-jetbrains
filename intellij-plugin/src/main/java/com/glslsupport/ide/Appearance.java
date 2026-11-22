package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.lang.annotation.*;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.*;
import com.intellij.openapi.options.colors.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import java.util.*;
import javax.swing.Icon;
import org.jetbrains.annotations.*;

public final class Appearance {
    static TextAttributesKey key(String name, TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey("GLSLSUPPORT_" + name, fallback);
    }
    static final TextAttributesKey KEYWORD = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD),
                                   TYPE = key("TYPE", DefaultLanguageHighlighterColors.CLASS_NAME),
                                   BUILTIN = key("BUILTIN", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL),
                                   MACRO = key("DIRECTIVE", DefaultLanguageHighlighterColors.METADATA),
                                   FUNCTION =
                                       key("FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION),
                                   FIELD = key("FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD),
                                   GLOBAL = key("INTERFACE", DefaultLanguageHighlighterColors.STATIC_FIELD),
                                   LOCAL = key("LOCAL", DefaultLanguageHighlighterColors.LOCAL_VARIABLE),
                                   PARAM = key("PARAMETER", DefaultLanguageHighlighterColors.PARAMETER),
                                   INACTIVE = key("INACTIVE", DefaultLanguageHighlighterColors.LINE_COMMENT);
    static IElementType word(String s) {
        if (Catalog.has(s, "types"))
            return GlslLanguage.Elements.TYPE;
        if (Syntax.QUALIFIERS.contains(s) || Catalog.has(s, "keywords"))
            return GlslLanguage.Elements.KEYWORD;
        if (!Catalog.entries(s).isEmpty())
            return GlslLanguage.Elements.BUILTIN;
        return GlslLanguage.Elements.WORD;
    }
    public static final class Highlighter extends SyntaxHighlighterBase {
        public @NotNull com.intellij.lexer.Lexer getHighlightingLexer() {
            return new GlslLanguage.Adapter();
        }
        public TextAttributesKey @NotNull[] getTokenHighlights(IElementType t) {
            TextAttributesKey k = null;
            if (t == GlslLanguage.Elements.COMMENT)
                k = DefaultLanguageHighlighterColors.LINE_COMMENT;
            else if (t == GlslLanguage.Elements.STRING)
                k = DefaultLanguageHighlighterColors.STRING;
            else if (t == GlslLanguage.Elements.NUMBER)
                k = DefaultLanguageHighlighterColors.NUMBER;
            else if (t == GlslLanguage.Elements.DIRECTIVE)
                k = MACRO;
            else if (t == GlslLanguage.Elements.KEYWORD)
                k = KEYWORD;
            else if (t == GlslLanguage.Elements.TYPE)
                k = TYPE;
            else if (t == GlslLanguage.Elements.BUILTIN)
                k = BUILTIN;
            return pack(k);
        }
    }
    public static final class Factory extends SyntaxHighlighterFactory {
        public @NotNull SyntaxHighlighter getSyntaxHighlighter(Project p, VirtualFile f) {
            return new Highlighter();
        }
    }
    public static final class Colors implements ColorSettingsPage {
        static final AttributesDescriptor[] DESCRIPTORS = {
            new AttributesDescriptor("Keywords and qualifiers", KEYWORD), new AttributesDescriptor("Types", TYPE),
            new AttributesDescriptor("Built-ins", BUILTIN), new AttributesDescriptor("Preprocessor", MACRO),
            new AttributesDescriptor("Functions", FUNCTION),
            new AttributesDescriptor("Block and struct members", FIELD),
            new AttributesDescriptor("Shader interfaces and globals", GLOBAL),
            new AttributesDescriptor("Local variables", LOCAL), new AttributesDescriptor("Parameters", PARAM),
            new AttributesDescriptor("Inactive branches", INACTIVE)};
        public Icon getIcon() {
            return GlslLanguage.Type.INSTANCE.getIcon();
        }
        public @NotNull SyntaxHighlighter getHighlighter() {
            return new Highlighter();
        }
        public @NotNull String getDemoText() {
            return "#version 450\nlayout(std140) uniform Scene { vec4 <field>tint</field>; } "
                + "<global>scene</global>;\nvoid <function>main</function>() {\n    gl_Position = "
                + "<global>scene</global>.<field>tint</field>;\n}";
        }
        public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
            return Map.of("field", FIELD, "global", GLOBAL, "function", FUNCTION);
        }
        public AttributesDescriptor @NotNull[] getAttributeDescriptors() {
            return DESCRIPTORS;
        }
        public ColorDescriptor @NotNull[] getColorDescriptors() {
            return ColorDescriptor.EMPTY_ARRAY;
        }
        public @NotNull String getDisplayName() {
            return "GLSL";
        }
    }
    public static final class Semantic implements Annotator {
        public void annotate(@NotNull PsiElement e, @NotNull AnnotationHolder holder) {
            if (!Settings.of(e.getProject()).getState().semantic
                || !(e instanceof GlslLanguage.Named
                    || e instanceof GlslLanguage.Element
                        && e.getNode().getElementType() == GlslLanguage.Elements.REF))
                return;
            var file = e.getContainingFile();
            var w = ProjectAccess.graph(file);
            String path = ProjectAccess.key(file);
            if (!w.active(path, e.getTextOffset())) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).textAttributes(INACTIVE).create();
                return;
            }
            if (!(e instanceof GlslLanguage.Named)
                && e.getFirstChild().getNode().getElementType() != GlslLanguage.Elements.WORD)
                return;
            var hits = w.resolve(path, e.getTextOffset());
            if (hits.isEmpty())
                return;
            var key = switch (hits.getFirst().symbol().kind()) {
                case FUNCTION -> FUNCTION;
                case TYPE, BLOCK -> TYPE;
                case FIELD -> FIELD;
                case GLOBAL -> GLOBAL;
                case LOCAL -> LOCAL;
                case PARAMETER -> PARAM;
                case MACRO -> MACRO;
            };
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).textAttributes(key).create();
        }
    }
}
