package com.glslsupport.bridge;
import static org.junit.jupiter.api.Assertions.*;

import com.glslsupport.lang.ShaderConfig;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
class BridgeTest {
    static final ShaderConfig.Effective C =
        new ShaderConfig.Effective(ShaderConfig.Stage.VERTEX, 0, 0, "", Map.of());
    static NativeCompiler.Result run(
        String path, String text, NativeCompiler.Operation op, Map<String, String> headers) {
        return NativeCompiler.compile(new NativeCompiler.Request(
            0, op, List.of(new NativeCompiler.Unit(path, text, C)), headers, List.of()));
    }
    @BeforeAll
    static void init() {
        NativeCompiler.load(Path.of(System.getProperty("glsl.native")));
    }
    @Test
    void unsavedHeader() {
        var r = run("s/ü.vert", "#version 450\n#include \"α.glsl\"\nvoid main(){}", NativeCompiler.Operation.CHECK,
            Map.of("s/α.glsl", "// 😀\n#error broken\n"));
        assertFalse(r.success());
        assertTrue(
            r.diagnostics("").stream().anyMatch(d -> d.file().endsWith("α.glsl") && d.line() == 2), r.log());
    }
    @Test
    void invalidSwizzlesUseTheNativeCompilerForBothTargets() {
        String path = "C:/shaders/scene.vert";
        for (int target : List.of(0, 13)) {
            var config = new ShaderConfig.Effective(ShaderConfig.Stage.VERTEX, target, 0, "core", Map.of());
            for (String expression : List.of("v.z", "v.xr", "v.xx = vec2(1); float x = v.x", "v.x")) {
                String source = "#version 450\nvoid main(){ vec2 v=vec2(0);\n"
                    + (expression.contains("=") ? expression : "float x=" + expression)
                    + ";\n gl_Position=vec4(x);}";
                var result =
                    NativeCompiler.compile(new NativeCompiler.Request(target, NativeCompiler.Operation.CHECK,
                        List.of(new NativeCompiler.Unit(path, source, config)), Map.of(), List.of()));
                assertEquals(expression.equals("v.x"), result.success(), result.log());
                if (!result.success())
                    assertTrue(result.diagnostics(path).stream().anyMatch(
                                   d -> d.file().equals(path) && d.line() == 3 && d.severity().equals("ERROR")),
                        result.log());
            }
        }
    }
    @Test
    void trailingUnknownComponentIsReportedAtTheSourceLine() {
        String path = "shaders/clip.vert";
        String source = "#version 450\nlayout(location=0) out vec2 uv;\nvoid main(){vec4 currentClip=vec4(1);\n"
            + "uv = currentClip.xyl / currentClip.w;\ngl_Position=currentClip;}";
        for (int target : List.of(0, 13)) {
            var config = new ShaderConfig.Effective(ShaderConfig.Stage.VERTEX, target, 0, "core", Map.of());
            var result = NativeCompiler.compile(new NativeCompiler.Request(target, NativeCompiler.Operation.CHECK,
                List.of(new NativeCompiler.Unit(path, source, config)), Map.of(), List.of()));
            assertFalse(result.success());
            assertTrue(result.diagnostics(path).stream().anyMatch(d
                           -> d.file().equals(path) && d.line() == 4 && d.severity().equals("ERROR")
                               && d.message().contains("'xyl' : unknown swizzle selection")),
                result.log());
        }
    }
    @Test
    void sourceMap() {
        var r = run("shaders/main.vert", "#version 450\nvoid main(){\n gl_Position=vec4(1);\n}",
            NativeCompiler.Operation.SPIRV, Map.of());
        assertTrue(r.success(), r.log());
        assertTrue(r.words().length > 5);
        assertTrue(
            r.instructions().values().stream().anyMatch(l -> l.file().contains("main.vert") && l.line() == 3),
            r.assembly());
    }
    @Test
    void reverseSourceMapKeepsTheFirstInstructionForEachFileAndLine() {
        String assembly = """
            %a = OpString "main.vert"
            %b = OpString "common.glslh"
            OpLine %a 7 0
            OpNop
            OpNoLine
            OpNop
            OpLine %b 7 0
            OpNop
            OpLine %a 7 1
            OpNop
            OpFunctionEnd
            """;
        var map = new NativeCompiler.Result(true, "", assembly, "", new int[0]).sourceMap();
        var shader = new NativeCompiler.Line("main.vert", 7);
        var header = new NativeCompiler.Line("common.glslh", 7);
        assertEquals(Map.of(shader, 2, header, 6), map.firstInstruction());
        assertEquals(Map.of(2, shader, 3, shader, 6, header, 7, header, 8, shader, 9, shader), map.instructions());
    }
    @Test
    void explicitOffsets() {
        assertEquals(5, NativeCompiler.offset("foo\né😀b", 2, 3));
        assertEquals(7, NativeCompiler.offset("foo\né😀b", 2, 7));
    }
    @Test
    void rejectsEmbeddedNull() {
        assertThrows(IllegalArgumentException.class,
            () -> run("t.vert", "void\0main", NativeCompiler.Operation.CHECK, Map.of()));
    }
    @Test
    void layout() {
        var r = run("a.vert",
            "#version 450\nlayout(std140) uniform Scene{float value;} u;void main(){gl_Position=vec4(u.value);}",
            NativeCompiler.Operation.LAYOUT, Map.of());
        assertTrue(r.success());
        assertTrue(r.layout().contains("member\tScene\tvalue\t0\t4"));
    }
}
