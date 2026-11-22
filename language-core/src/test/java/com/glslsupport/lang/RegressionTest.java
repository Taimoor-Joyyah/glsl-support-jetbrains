package com.glslsupport.lang;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class RegressionTest {
    @Test
    void editorUsesVirtualProfileAndTarget() {
        String source = "#version 450\n#if __VERSION__ == 310 && GL_ES && VULKAN\nfloat chosen;\n#endif";
        var config = new ShaderConfig.Effective(ShaderConfig.Stage.FRAGMENT, 12, 310, "es", Map.of());
        var w = new Workspace(Map.of("file.frag", new Syntax(source)), List.of(), config.editorDefines());
        assertTrue(w.active("file.frag", source.indexOf("chosen")));
    }
    @ParameterizedTest
    @CsvSource(delimiter = ';',
        value = {"normalize(vec3(1.0));vec3", "length(dvec2(1.0LF));double", "lessThan(ivec2(0),ivec2(1));bvec2"})
    void builtinResultDependsOnArgumentTypes(String expression, String type) {
        var w = EditorTest.workspace(expression);
        assertEquals(type, w.typeAt("main.vert", w.files.get("main.vert").code.size() - 1, 0));
    }
    @Test
    void headerReferencesFollowTheIncludingShader() {
        String shader = "float rootValue;\n#define ENABLE 1\n#include \"read.glslh\"\nvoid main(){}";
        String header = "#ifdef ENABLE\nfloat readValue(){return rootValue;}\n#endif";
        var model = new Workspace(Map.of("shader.vert", new Syntax(shader), "read.glslh", new Syntax(header)),
            List.of(), Map.of(), "shader.vert");
        var offset = header.indexOf("rootValue");
        assertTrue(model.active("read.glslh", offset));
        assertEquals("shader.vert", model.resolve("read.glslh", offset).getFirst().file());
    }
    @ParameterizedTest
    @CsvSource({"struct_fields.frag,ccc.aaa.f1,int", "struct_fields.frag,ccc.bbb.fb,bool",
        "struct_fields.frag,aaa.fa,uint", "buffers.frag,ssbuffer3.aaa[0][0].field0,int",
        "buffers.frag,ssbuffer4.ssbuffer4.ssbuffer4.ssbuffer4,uint", "buffers.frag,ubuffer2[0].field0,mat2",
        "static_arrays.frag,elements[0].field1,int"})
    void priorEditorCases(String file, String expression, String expected) throws Exception {
        var f = new Syntax(Files.readString(Path.of("test-data", "editor", file)));
        int at = f.source.indexOf(expression + ";");
        assertTrue(at >= 0, expression);
        var workspace = new Workspace(Map.of(file, f), List.of(), Map.of());
        assertEquals(expected, workspace.typeAt(file, f.indexAt(at + expression.length() - 1), 0));
    }
    @ParameterizedTest
    @ValueSource(
        strings = {"struct_fields.frag", "static_arrays.frag", "functions.frag", "shadowing.frag", "buffers.frag",
            "interface_blocks.geom", "images_and_samplers.frag", "hover_positions.frag", "comments.frag"})
    void previousSourcesRetainEveryToken(String name) throws Exception {
        var source = Files.readString(Path.of("test-data", "editor", name));
        var f = new Syntax(source);
        assertEquals(source, String.join("", f.tokens.stream().map(Lexer.Token::text).toList()));
        for (var s : f.symbols)
            assertTrue(s.at() >= s.start() && s.end() <= source.length() && source.startsWith(s.name(), s.at()),
                s.toString());
    }
    @Test
    void macroOrderAcrossNestedIncludes() {
        String main = "#define MODE 2\n#include \"config.glslh\"\n#if FROM_INCLUDE == 9\nfloat "
            + "enabled;\n#endif\nvoid main(){enabled; exported;}";
        String config = "#ifndef CONFIG\n#define CONFIG\n#include \"inner.glslh\"\n#endif";
        String inner = "#if MODE == 2\n#define FROM_INCLUDE 9\nfloat exported;\n#endif";
        var w = new Workspace(Map.of("a.vert", new Syntax(main), "inner.glslh", new Syntax(inner), "config.glslh",
                                  new Syntax(config)),
            List.of(), Map.of());
        assertTrue(w.active("a.vert", main.indexOf("enabled")));
        assertEquals("inner.glslh", w.resolve("a.vert", main.lastIndexOf("exported")).getFirst().file());
    }
    @Test
    void completingDirectiveAndCode() {
        String s = "#extension GL_EXT_mesh_s";
        var w = EditorTest.workspace(s);
        assertTrue(w.completions("main.vert", s.length(), true).contains("GL_EXT_mesh_shader"));
        assertFalse(EditorTest.workspace("").completions("main.vert", 0, true).contains("GL_EXT_mesh_shader"));
    }
    @ParameterizedTest
    @CsvSource({"0xFE,int", "0xFFu,uint", "1.0LF,double", "2e4,float", "0xdeadbeef,int"})
    void literalTypes(String value, String expected) {
        var w = EditorTest.workspace(value);
        assertEquals(expected, w.typeAt("main.vert", 0, 0));
    }
    @Test
    void indexesMatricesVectorsAndArrays() {
        String s = "void main(){mat2x3 m;vec4 v[4];m[1];m[1][0];v[0];v[0][2];}";
        var w = EditorTest.workspace(s);
        var f = w.files.get("main.vert");
        for (var e :
            Map.of("m[1];", "vec3", "m[1][0];", "float", "v[0];", "vec4", "v[0][2];", "float").entrySet()) {
            int right = s.indexOf(e.getKey()) + e.getKey().length() - 2;
            assertEquals(e.getValue(), w.typeAt("main.vert", f.indexAt(right), 0), e.getKey());
        }
    }
    @Test
    void formattingAdjacentFunctionsAndBlockInstances() {
        String s = "struct S{int x;} v;void one(){}void two(){}";
        String formatted = Formatting.format(s, 0, s.length(), 4, false).apply(s);
        assertTrue(formatted.contains("} v;"));
        assertTrue(formatted.contains("void one() {}\nvoid two() {}"), formatted);
        assertEquals(formatted, Formatting.format(formatted, 0, formatted.length(), 4, false).apply(formatted));
    }
    @Test
    void inlineRejectsChangingWritesAndMultipleDeclarators() {
        for (String s : List.of("void main(){int n=2,m=3;n;}", "void main(){int n=2;n%=3;}")) {
            var w = EditorTest.workspace(s);
            var target = w.resolve("main.vert", s.indexOf("n=")).getFirst();
            assertThrows(IllegalArgumentException.class, () -> Editing.inline(w, target), s);
        }
    }
}
