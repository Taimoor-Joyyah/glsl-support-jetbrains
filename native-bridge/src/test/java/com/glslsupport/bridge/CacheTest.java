package com.glslsupport.bridge;
import static org.junit.jupiter.api.Assertions.*;

import com.glslsupport.lang.ShaderConfig;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
class CacheTest {
    @Test
    void editsOnlyInvalidateDependentSnapshots() {
        var calls = new AtomicInteger();
        var c = new AnalysisCache(1 << 20, r -> {
            calls.incrementAndGet();
            return new NativeCompiler.Result(true, "", "", "", new int[0]);
        });
        var u = new NativeCompiler.Unit(
            "a.vert", "void main(){}", new ShaderConfig.Effective(ShaderConfig.Stage.VERTEX, 0, 0, "", Map.of()));
        var independent =
            new NativeCompiler.Request(0, NativeCompiler.Operation.CHECK, List.of(u), Map.of(), List.of());
        var before = new NativeCompiler.Request(
            0, NativeCompiler.Operation.CHECK, List.of(u), Map.of("inc", "1"), List.of());
        var after = new NativeCompiler.Request(
            0, NativeCompiler.Operation.CHECK, List.of(u), Map.of("inc", "2"), List.of());
        c.analyze(independent);
        c.analyze(before);
        c.analyze(after);
        c.analyze(independent);
        c.analyze(after);
        assertEquals(3, calls.get());
    }
}
