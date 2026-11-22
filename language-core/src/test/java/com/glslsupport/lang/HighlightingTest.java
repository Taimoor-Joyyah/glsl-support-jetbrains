package com.glslsupport.lang;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class HighlightingTest {
    private List<String> complete(String text, boolean vulkan) {
        int caret = text.indexOf('|');
        String file = "stage.vert";
        var w = new Workspace(Map.of(file, new Syntax(text.replace("|", ""))), List.of(),
            vulkan ? Map.of("VULKAN", "100") : Map.of());
        return w.completions(file, caret, true);
    }
    @Test
    void layoutCompletionRespectsContext() {
        assertTrue(complete("layout(|", false)
                .containsAll(List.of("std140", "std430", "location", "binding", "rgba32f", "local_size_x")));
        assertEquals(List.of("binding"), complete("layout(std140, bindi|) uniform Scene", false));
        assertFalse(complete("layout(binding=1, |) uniform Scene", true).contains("binding"));
        assertTrue(complete("layout(location=(1+2), se|)", true).contains("set"));
        assertFalse(complete("layout(se|)", false).contains("set"));
        assertFalse(complete("layout(location=|)", true).contains("local_size_x"));
        assertFalse(complete("void f(){ other(|); }", false).contains("local_size_x"));
    }
    @Test
    void swizzleDimensionAndAlphabets() {
        for (String s : List.of("x", "yyxx", "gr", "stts")) assertTrue(Workspace.validSwizzle("vec2", s));
        for (String s : List.of("xr", "z", "xxxxy", "", "member")) assertFalse(Workspace.validSwizzle("vec2", s));
        assertFalse(Workspace.validSwizzle("Light", "xyz"));
        assertTrue(Workspace.validSwizzle("uvec4", "wzyx"));
        assertTrue(Workspace.validSwizzle("f16vec3", "xyz"));
        assertTrue(
            Catalog.has("accelerationStructureEXT", "types") && Catalog.has("terminateInvocation", "keywords"));
    }
    @Test
    void unreachableRanges() {
        String s = "void f(){ if(ok) return; live(); {return; first(); { nested(); } second();} live(); "
            + "switch(k){case 0: break; dead(); case 1: live();} }";
        assertEquals(List.of("first(); { nested(); } second();", "dead();"),
            Editing.unreachable(new Syntax(s)).stream().map(r -> s.substring(r.start(), r.end())).toList());
        String conditional = "void f(){\n#if OFF\nreturn;\n#endif\nlive();}";
        assertTrue(Editing.unreachable(new Syntax(conditional)).isEmpty());
        String branches =
            "void f(){if(false){dead();} if(true){live();}else{dead();}\n#if 1\nreturn;\n#endif\ndead();}";
        assertEquals(List.of("dead();", "dead();", "dead();"),
            Editing.unreachable(new Syntax(branches))
                .stream()
                .map(r -> branches.substring(r.start(), r.end()))
                .toList());
    }
    @Test
    void duplicateDefineAndUndef() {
        String s = "#define X 1\n#define X 1\n#undef X\n#define X 2\n#if 0\n#define X 3\n#endif\n";
        assertEquals(1,
            Editing.inspect(EditorTest.workspace(s), "main.vert")
                .stream()
                .filter(i -> i.message().contains("Duplicate macro"))
                .count());
        assertEquals("core", ShaderConfig.Variant.defaultVariant().profile());
    }
}
