package com.glslsupport.lang;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
class EditorTest {
    static Workspace workspace(String text) {
        return new Workspace(Map.of("main.vert", new Syntax(text)), List.of(), Map.of());
    }
    @Test
    void lexerIsLossless() {
        for (String text : List.of("/* unfinished", "// hi\r\nvec4 v=1.e-2;",
                 "#define F(x) \\\n (x)\nvoid main(){}", "/*😀*/vec2(1,2).xy", "void f( {"))
            assertEquals(text, Lexer.tokens(text).stream().map(Lexer.Token::text).reduce("", String::concat));
    }
    @Test
    void indexesRecover() {
        String s = "struct Light { vec3 color; }; uniform Light light; void main() { vec3 result = light.";
        var w = workspace(s);
        assertTrue(w.completions("main.vert", s.length(), false).contains("color"));
    }
    @Test
    void scopedLookup() {
        String s = "float a; void f(float p){ int a=1; { float a=2.; a; } a; p; }";
        var w = workspace(s);
        assertEquals("float", w.resolve("main.vert", s.indexOf("a; }")).getFirst().symbol().type());
        assertEquals("int", w.resolve("main.vert", s.lastIndexOf("a;")).getFirst().symbol().type());
        assertEquals(
            Syntax.Kind.PARAMETER, w.resolve("main.vert", s.lastIndexOf("p;")).getFirst().symbol().kind());
    }
    @Test
    void localInspectionsRespectScopesMembersAndInactiveCode() {
        String source = """
            #include "other.glslh"
            struct Surface { float tint; };
            #if 0
            float disabled;
            #endif
            void main() {
                Surface s;
                float tint;
                float value;
                { float value; s.tint = value; }
                float disabled;
                #if 0
                tint = disabled;
                #endif
            }
            """;
        var w = new Workspace(Map.of("main.vert", new Syntax(source), "other.glslh",
            new Syntax("void other(){float tint; tint = 0.;}")), List.of(), Map.of(), "main.vert");
        var findings = Editing.inspect(w, "main.vert");
        assertEquals(List.of("Unused local 'tint'", "Unused local 'value'", "Declaration shadows 'value'",
            "Unused local 'disabled'"), findings.stream().map(Editing.Finding::message).toList());
        assertEquals(source.indexOf("value;"), findings.get(1).start());
        assertEquals(source.lastIndexOf("float value;") + 6, findings.get(2).start());
    }
    @Test
    void memberCompletionUsesActiveFieldsOfTheResolvedOwner() {
        String source = "#include \"types.glslh\"\nvoid main(){Surface s; s.}";
        String header = """
            struct Other { float unrelated; };
            struct Surface {
                float base;
                #if DETAIL
                float detail;
                #endif
            };
            """;
        var files = Map.of("main.vert", new Syntax(source), "types.glslh", new Syntax(header));
        int caret = source.lastIndexOf('.') + 1;
        for (int detail : List.of(0, 1)) {
            var w = new Workspace(files, List.of(), Map.of("DETAIL", Integer.toString(detail)), "main.vert");
            assertEquals(detail == 0 ? List.of("base") : List.of("base", "detail"),
                w.completions("main.vert", caret, false));
        }
    }
    @Test
    void conditional() {
        String s = "#define A 2\n#if defined(A) && (A << 1) == 4\nfloat yes;\n#else\nfloat no;\n#endif";
        var w = workspace(s);
        assertTrue(w.active("main.vert", s.indexOf("yes")));
        assertFalse(w.active("main.vert", s.indexOf("no")));
    }
    @Test
    void headerVisibilityUsesFunctionMacrosInTranslationOrder() {
        String source = """
            #include "defines.glslh"
            #define TWICE(x) ((x) + (x))
            #if defined(MODE) && PICK(TWICE(MODE), 7) == 6
            #include "selected.glslh"
            #endif
            #undef MODE
            #if defined(MODE)
            float inactive;
            #endif
            """;
        var files = Map.of("main.vert", new Syntax(source),
            "defines.glslh", new Syntax("#define PICK(a,b) ((a) ? (a) : (b))\n"),
            "selected.glslh", new Syntax("float chosen;\n"));
        var enabled = new Workspace(files, List.of(), Map.of("MODE","3"), "main.vert");
        assertTrue(enabled.visible("main.vert").contains("selected.glslh"));
        assertFalse(enabled.active("main.vert", source.indexOf("inactive")));
        var disabled = new Workspace(files, List.of(), Map.of("MODE","0"), "main.vert");
        assertFalse(disabled.visible("main.vert").contains("selected.glslh"));
    }
    @Test
    void overloadsAndClosedFiles() {
        String h = "float gain(float x){return x;} int gain(int x){return x;}";
        String s = "#include \"inc.glslh\"\nvoid main(){int y=gain(1);}";
        var w = new Workspace(Map.of("main.vert", new Syntax(s), "inc.glslh", new Syntax(h), "other.frag",
                                  new Syntax("int gain(int x);")),
            List.of(), Map.of());
        var hit = w.resolve("main.vert", s.indexOf("gain")).getFirst();
        assertEquals("int", hit.symbol().type());
        assertEquals("inc.glslh", hit.file());
        assertEquals(2, Editing.rename(w, hit, "strength").size());
    }
    @Test
    void guardCollisions() {
        assertNotEquals(Editing.guard("a/common.glslh"), Editing.guard("b/common.glslh"));
        assertEquals(Editing.guard("a/../b/common.glslh"), Editing.guard("b/common.glslh"));
        assertTrue(Editing.header("1/α.glslh").contains("#endif"));
    }
    @Test
    void formatting() {
        String s = "#version 450\nvoid main(){float x=1.;}";
        var result = Formatting.format(s, 0, s.length(), 4, false).apply(s);
        assertEquals("#version 450\nvoid main() {\n    float x = 1.;\n}", result);
        assertEquals(result, Formatting.format(result, 0, result.length(), 4, false).apply(result));
    }
    @Test
    void rangeFormatting() {
        String s = "void main() {\nfloat x=1.;\nfloat y=2.;\n}\n";
        int at = s.indexOf("float x");
        String text = Formatting.format(s, at, at + 5, 2, false).apply(s);
        assertEquals("void main() {\n  float x = 1.;\nfloat y=2.;\n}\n", text);
    }
    @Test
    void catalog() {
        assertTrue(Catalog.has("mat4", "types"));
        assertTrue(Catalog.entries("normalize").stream().anyMatch(e -> e.kind().equals("functions")));
        assertTrue(Catalog.names().size() > 1000);
    }
    @Test
    void localRefactoring() {
        String s = "void main(){ float n=2.; float m=n; }";
        var w = workspace(s);
        var t = w.resolve("main.vert", s.indexOf("n=")).getFirst();
        assertEquals(2, Editing.inline(w, t).size());
        int start = s.indexOf("n;", s.indexOf("m="));
        var edits = Editing.extract(w, "main.vert", start, start + 1, "calc");
        assertTrue(edits.get(1).text().contains("float calc(in float n)"));
        assertThrows(IllegalArgumentException.class, () -> Editing.rename(w, t, "m"));
    }
    @Test
    void rejectSideEffects() {
        String s = "float value(); void main(){ float n=value(); n++; }";
        var w = workspace(s);
        assertThrows(IllegalArgumentException.class,
            () -> Editing.inline(w, w.resolve("main.vert", s.indexOf("n=")).getFirst()));
    }
    @Test
    void observations() {
        String s = "float v; void f(){float v;return;float dead;}";
        assertEquals(4, Editing.inspect(workspace(s), "main.vert").size());
    }
    @Test
    void settings() {
        var v = new ShaderConfig.Variant("frag", null, null, 450, "core", Map.of("PIXEL", "1"));
        var c = ShaderConfig.resolve("project/vk/f.vert", 0, Map.of("project/vk", 13), ShaderConfig.Stage.COMPUTE,
            Map.of("PIXEL", ShaderConfig.Stage.FRAGMENT), v);
        assertEquals(13, c.vulkan());
        assertEquals(ShaderConfig.Stage.FRAGMENT, c.stage());
    }
    @Test
    void malformedInputTerminates() {
        var rnd = new Random(41);
        String alphabet = "float x[]{}();*/#\n\t";
        for (int i = 0; i < 500; i++) {
            var b = new StringBuilder();
            for (int j = 0; j < 120; j++) b.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            var f = new Syntax(b.toString());
            assertEquals(b.length(), f.tokens.getLast().end());
        }
    }
}
