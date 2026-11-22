package com.glslsupport.bridge;
import static org.junit.jupiter.api.Assertions.*;

import com.glslsupport.lang.ShaderConfig;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StageTemplatesTest {
    @BeforeAll
    static void load() {
        NativeCompiler.load(Path.of(System.getProperty("glsl.native")));
    }
    @ParameterizedTest
    @EnumSource(ShaderConfig.Stage.class)
    void stageTemplateEmitsValidVulkanSpirv(ShaderConfig.Stage stage) throws Exception {
        Path dir = Path.of("intellij-plugin/src/main/resources/fileTemplates");
        String ext = stage.extensions.getFirst();
        Path template;
        try (var paths = Files.list(dir)) {
            template = paths.filter(p -> p.toString().endsWith("." + ext + ".ft")).findFirst().orElseThrow();
        }
        var effective = new ShaderConfig.Effective(stage, 12, 0, "", Map.of());
        var unit = new NativeCompiler.Unit("main." + ext, Files.readString(template), effective);
        var request =
            new NativeCompiler.Request(12, NativeCompiler.Operation.SPIRV, List.of(unit), Map.of(), List.of());
        var result = NativeCompiler.compile(request);
        assertTrue(result.success(), result.log());
        assertTrue(result.assembly().contains("OpEntryPoint"));
    }
    @Test
    void includedSourceMap() {
        String main = "#version 450\n#include \"position.glslh\"\nvoid main(){gl_Position=position();}";
        String header = "vec4 position(){\nreturn vec4(2);\n}";
        var result = BridgeTest.run(
            "sh/main.vert", main, NativeCompiler.Operation.SPIRV, Map.of("sh/position.glslh", header));
        assertTrue(result.success(), result.log());
        assertTrue(result.instructions().containsValue(new NativeCompiler.Line("sh/position.glslh", 2)),
            result.assembly());
    }
}
