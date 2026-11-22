package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.lang.annotation.*;
import com.intellij.openapi.editor.colors.*;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.*;
import com.intellij.openapi.options.colors.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import java.awt.Color;
import java.util.*;
import javax.swing.Icon;
import org.jetbrains.annotations.*;

public final class Appearance {
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
        private static final Map<IElementType, TextAttributesKey[]> KEYS = new IdentityHashMap<>();
        static {
            for (var role : ShaderPalette.values()) KEYS.put(role.token, pack(role.key));
        }
        public @NotNull com.intellij.lexer.Lexer getHighlightingLexer() {
            return new ColorLexer();
        }
        public TextAttributesKey @NotNull[] getTokenHighlights(IElementType type) {
            return KEYS.getOrDefault(type, TextAttributesKey.EMPTY_ARRAY);
        }
    }
    public static final class Factory extends SyntaxHighlighterFactory {
        public @NotNull SyntaxHighlighter getSyntaxHighlighter(Project p, VirtualFile f) {
            return new Highlighter();
        }
    }
    public static final class Colors implements ColorSettingsPage {
        static final AttributesDescriptor[] DESCRIPTORS = Arrays.stream(ShaderPalette.values())
                                                              .map(r -> new AttributesDescriptor(r.label, r.key))
                                                              .toArray(AttributesDescriptor[] ::new);
        public Icon getIcon() {
            return GlslLanguage.Type.INSTANCE.getIcon();
        }
        public @NotNull SyntaxHighlighter getHighlighter() {
            return new Highlighter();
        }
        public @NotNull String getDemoText() {
            return """
            #version 460 core
            #extension GL_GOOGLE_include_directive : enable
            #include "lighting/common.glslh"
            #define LIGHT_COUNT 4
            /** @param normal Surface normal in world coordinates. */
            struct <struct>Light</struct> { vec4 <field>color</field>; };
            layout(<layout_item>std140</layout_item>, <layout_item>binding</layout_item> = 1) uniform <block>Lights</block> {
                <type_use>Light</type_use> <field>sources</field>[<macro>LIGHT_COUNT</macro>];
            } <uniform>scene</uniform>;
            layout(<layout_item>location</layout_item> = 0) out vec4 <io>fragment</io>;
            vec3 <func>shade</func>(in vec3 <arg>normal</arg>) {
                const float <const>scale</const> = 0.75;
                vec3 <local>color</local> = <uniform>scene</uniform>.<field>sources</field>[0].<field>color</field>.<swizzle>rgb</swizzle>;
                if (true && <arg>normal</arg>.z > 0.0) <written_local>color</written_local> *= <const>scale</const>;
                return normalize(<local>color</local>);
            }
            void <func>main</func>() { <io>fragment</io> = vec4(<call>shade</call>(gl_FragCoord.<swizzle>xyz</swizzle>), 1.0); }
            """;
        }
        public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
            var tags = new HashMap<String, TextAttributesKey>();
            for (var r : ShaderPalette.values()) tags.put(r.name().toLowerCase(Locale.ROOT), r.key);
            return tags;
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
    static ShaderPalette role(Workspace w, String path, Syntax f, int i, ShaderPalette original) {
        String name = f.word(i);
        if (LayoutQualifiers.known(name) && LayoutQualifiers.item(f, i))
            return ShaderPalette.LAYOUT_ITEM;
        if (f.word(i - 1).equals(".")) {
            if (name.equals("length") && f.word(i + 1).equals("("))
                return ShaderPalette.METHOD;
            if (Workspace.validSwizzle(w.typeAt(path, i - 2, 0), name))
                return ShaderPalette.SWIZZLE;
            return ShaderPalette.FIELD;
        }
        if (original != ShaderPalette.NAME && original != ShaderPalette.BUILTIN_FUNC)
            return original;
        int at = f.code.get(i).start();
        var declaration = f.declaration(at);
        var symbol = declaration;
        Syntax owner = f;
        if (symbol == null) {
            var hits = w.resolve(path, at);
            if (!hits.isEmpty()) {
                var hit = hits.getFirst();
                symbol = hit.symbol();
                owner = w.files.get(hit.file());
            }
        }
        if (symbol == null && !w.active(path, at)) {
            for (var candidate : f.named(name)) {
                if (candidate.at() > at || candidate.scopeStart() > at || candidate.scopeEnd() < at
                    || candidate.kind() == Syntax.Kind.FIELD)
                    continue;
                if (symbol == null || candidate.at() > symbol.at())
                    symbol = candidate;
            }
        }
        if (symbol == null)
            return original;
        boolean write = declaration == null
            && (ShaderPalette.symbol(f.word(i + 1)) == ShaderPalette.ASSIGN
                || Set.of("++", "--").contains(f.word(i - 1)) || Set.of("++", "--").contains(f.word(i + 1)));
        var basic = switch (symbol.kind()) {
            case TYPE -> declaration != null ? ShaderPalette.STRUCT : ShaderPalette.TYPE_USE;
            case BLOCK -> ShaderPalette.BLOCK;
            case FUNCTION -> declaration != null ? ShaderPalette.FUNC : ShaderPalette.CALL;
            case MACRO -> ShaderPalette.MACRO;
            case FIELD -> ShaderPalette.FIELD;
            case PARAMETER -> write ? ShaderPalette.WRITTEN_ARG : ShaderPalette.ARG;
            case LOCAL -> write ? ShaderPalette.WRITTEN_LOCAL : ShaderPalette.LOCAL;
            case GLOBAL -> ShaderPalette.GLOBAL;
        };
        if (symbol.kind() == Syntax.Kind.GLOBAL || symbol.kind() == Syntax.Kind.LOCAL) {
            for (int j = owner.indexAt(symbol.start());
                j < owner.code.size() && Syntax.QUALIFIERS.contains(owner.word(j)); j++) {
                switch (owner.word(j)) {
                    case "const":
                        return ShaderPalette.CONST;
                    case "uniform", "buffer":
                        return ShaderPalette.UNIFORM;
                    case "in", "out", "attribute", "varying":
                        return ShaderPalette.IO;
                }
            }
        }
        return basic;
    }
    public static TextAttributes dim(TextAttributes original, Color foreground, Color background) {
        var attrs = original == null ? new TextAttributes() : original.clone();
        var fg = Objects.requireNonNullElse(attrs.getForegroundColor(), foreground);
        attrs.setForegroundColor(new Color((fg.getRed() * 3 + background.getRed() * 2) / 5,
            (fg.getGreen() * 3 + background.getGreen() * 2) / 5,
            (fg.getBlue() * 3 + background.getBlue() * 2) / 5));
        return attrs;
    }
    public static final class Semantic implements Annotator {
        public void annotate(@NotNull PsiElement e, @NotNull AnnotationHolder holder) {
            if (!(e instanceof GlslLanguage.File file) || !Settings.of(file.getProject()).getState().semantic)
                return;
            var w = ProjectAccess.graph(file);
            String path = ProjectAccess.key(file);
            var f = w.files.get(path);
            var dead = Editing.unreachable(f, at -> w.active(path, at));
            int next = 0;
            var scheme = EditorColorsManager.getInstance().getGlobalScheme();
            var dimmed = new EnumMap<ShaderPalette, TextAttributes>(ShaderPalette.class);
            for (var t : f.tokens) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                if (t.kind() == Lexer.Kind.SPACE)
                    continue;
                while (next < dead.size() && dead.get(next).end() <= t.start()) next++;
                boolean fade =
                    !w.active(path, t.start()) || next < dead.size() && dead.get(next).start() <= t.start();
                var original = ShaderPalette.lexical(t);
                var actual =
                    t.kind() == Lexer.Kind.WORD ? role(w, path, f, f.indexAt(t.start()), original) : original;
                if (!fade) {
                    if (actual != original)
                        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                            .range(new TextRange(t.start(), t.end()))
                            .textAttributes(actual.key)
                            .create();
                    continue;
                }
                for (var part : ColorLexer.parts(t)) {
                    var color = actual != original ? actual : part.color();
                    if (color == null)
                        continue;
                    var attrs = dimmed.computeIfAbsent(color,
                        r
                        -> dim(scheme.getAttributes(r.key), scheme.getDefaultForeground(),
                            scheme.getDefaultBackground()));
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(new TextRange(part.from(), part.to()))
                        .enforcedTextAttributes(attrs)
                        .create();
                }
            }
        }
    }
}
