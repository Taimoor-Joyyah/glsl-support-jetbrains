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
