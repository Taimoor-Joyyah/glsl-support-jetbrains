package com.glslsupport.bridge;

import com.glslsupport.lang.*;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PipelineToolsTest {
    @BeforeAll static void load() { NativeCompiler.load(Path.of(System.getProperty("glsl.native"))); }
    private static NativeCompiler.Unit unit(String path, ShaderConfig.Stage stage, int target, String source,
                                             Map<String,String> macros) {
        return new NativeCompiler.Unit(path, source, new ShaderConfig.Effective(stage, target, 0, "", macros));
    }
    private static Workspace graph(NativeCompiler.Request request, NativeCompiler.Unit unit) {
        var files = new HashMap<String,Syntax>();
        request.headers().forEach((k,v) -> files.put(k, new Syntax(v)));
        files.put(unit.path(), new Syntax(unit.text()));
        return new Workspace(files, request.includeRoots(), unit.config().editorDefines(), unit.path());
    }
    @Test void mismatchLinksBothActiveDeclarationsIncludingHeader() {
        var v = unit("s/color.vert", ShaderConfig.Stage.VERTEX, 12,
            "#version 450\n#include \"out.glslh\"\nvoid main(){tint=vec3(1);gl_Position=vec4(1);}", Map.of());
        var f = unit("s/color.frag", ShaderConfig.Stage.FRAGMENT, 12,
            "#version 450\nlayout(location=3) in vec4 tint;\nlayout(location=0) out vec4 c; void main(){c=tint;}", Map.of());
        var headers = Map.of("s/out.glslh", "#if 0\nout int tint;\n#endif\nlayout(location=3) out vec3 tint;\n");
        var request = new NativeCompiler.Request(12, NativeCompiler.Operation.LINK, List.of(v,f), headers, List.of());
        var result = NativeCompiler.compile(request);
        assertFalse(result.success());
        var problems = PipelineDiagnostics.collect(request, result, List.of(graph(request,v),graph(request,f)));
        var mismatch = problems.stream().filter(i -> i.message().contains("Types must match")).findFirst().orElseThrow();
        assertEquals(-1, mismatch.stage());
        assertEquals(List.of("s/out.glslh:4", "s/color.frag:2"),
            mismatch.locations().stream().map(l -> l.path() + ":" + l.line()).toList());
    }
    @Test void locationlessLinkErrorAndSourceErrorStayWithTheirOwners() {
        for (boolean parse : List.of(true,false)) {
            var u = unit("s/compute.comp", ShaderConfig.Stage.COMPUTE, 12, "#version 450\nlayout(local_size_x=1) in;\n" +
                (parse ? "#include \"bad.glslh\"\nvoid main(){}" : "void helper(){}"), Map.of());
            var req = new NativeCompiler.Request(12, NativeCompiler.Operation.LINK, List.of(u),
                Map.of("s/bad.glslh", "#error header_problem\n"), List.of());
            var result = NativeCompiler.compile(req);
            assertFalse(result.success());
            var issues = PipelineDiagnostics.collect(req, result, List.of(graph(req,u)));
            assertTrue(issues.stream().anyMatch(i -> parse ? i.stage() == 0 && i.locations().stream().anyMatch(
                l -> l.path().equals("s/bad.glslh") && l.line() == 1)
                : i.stage() == -1 && i.message().contains("entry point") && i.locations().isEmpty()), issues.toString());
        }
    }
    @Test void traceComparesNativeBranchesIncludesOverridesAndOccurrences() {
        var variants = new ArrayList<Map<PreprocessorTrace.Site,PreprocessorTrace.Entry>>();
        for (String mode : List.of("0","2")) {
            var vertex = unit("s/color.vert", ShaderConfig.Stage.VERTEX, 0, """
                #version 450
                #define DOUBLE(x) ((x)*2)
                #if DOUBLE(MODE)==4
                #include "common.glslh"
                #else
                #include <common.glslh>
                #endif
                #undef HEADER
                #include "common.glslh"
                void main(){}
                """, Map.of("MODE",mode,"LIGHTS","8"));
            var request = new NativeCompiler.Request(0, NativeCompiler.Operation.TRACE, List.of(vertex),
                Map.of("s/common.glslh","#define HEADER 1\n","lib/common.glslh","#define HEADER 3\n"), List.of("lib"));
            var result = NativeCompiler.compile(request);
            assertTrue(result.success(), result.log());
            var entries = PreprocessorTrace.entries(request, result, Map.of("MODE",mode,"LIGHTS","1"),Map.of("LIGHTS","8"));
            assertTrue(entries.entrySet().stream().anyMatch(e -> e.getKey().file().equals("Pipeline")
                && e.getKey().name().equals("LIGHTS") && e.getValue().value().equals("8")));
            assertEquals(2, entries.keySet().stream().filter(k -> k.kind().equals("define") && k.name().equals("HEADER")).count());
            assertTrue(entries.entrySet().stream().anyMatch(e -> e.getKey().kind().equals("undef") &&
                e.getValue().value().contains(mode.equals("2") ? "previously 1" : "previously 3")));
            variants.add(entries);
        }
        var rows = PreprocessorTrace.compare(variants.get(0), variants.get(1));
        assertTrue(rows.stream().anyMatch(r -> r.site().kind().equals("branch") && r.changed() &&
            r.left().value().contains("false") && r.right().value().contains("taken")));
        assertTrue(rows.stream().anyMatch(r -> r.left() == null && r.right().value().equals("s/common.glslh")));
        var broken = BridgeTest.run("s/bad.vert","#version 450\n#define FIRST 1\n#if (\n",NativeCompiler.Operation.TRACE, Map.of());
        assertFalse(broken.success());
        assertTrue(broken.trace().stream().anyMatch(e -> e.name().equals("FIRST")));
    }
    @Test void exportedModulesMatchValidatedWordsAndProtectTheDestination(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("module.spv");
        for (int target : List.of(0,12)) {
            var u = unit("test.comp", ShaderConfig.Stage.COMPUTE, target,
                "#version 450\nlayout(local_size_x=1) in; void main(){}", Map.of());
            var req = new NativeCompiler.Request(target, NativeCompiler.Operation.SPIRV, List.of(u),Map.of(),List.of());
            var result = NativeCompiler.compile(req);
            assertTrue(result.success(),result.log());
            SpirvExport.write(out,result,Files.exists(out),()->true);
            byte[] good = Files.readAllBytes(out);
            var words = ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            int[] decoded = new int[words.remaining()];
            words.get(decoded);
            assertArrayEquals(result.words(),decoded);
            assertThrows(FileAlreadyExistsException.class, () -> SpirvExport.write(out,result,false,()->true));
            var checks = new AtomicInteger();
            assertThrows(IOException.class, () -> SpirvExport.write(out,result,true,()->checks.incrementAndGet()==1));
            assertThrows(IOException.class, () -> SpirvExport.write(out,
                new NativeCompiler.Result(false,"failed","","",result.words()),true,()->true));
            assertArrayEquals(good, Files.readAllBytes(out));
            try (var files = Files.list(dir)) { assertEquals(List.of(out),files.toList()); }
        }
    }
}
