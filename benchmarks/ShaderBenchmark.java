import com.glslsupport.bridge.AnalysisCache;
import com.glslsupport.bridge.NativeCompiler;
import com.glslsupport.lang.ShaderConfig;
import com.glslsupport.lang.Syntax;
import com.glslsupport.lang.Workspace;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic source-model and compiler-cache workloads. Times exclude fixture generation. */
public final class ShaderBenchmark {
    private static final int FILES = 256;
    private static final int DECLARATIONS = 96;
    private static final ShaderConfig.Effective CONFIG =
        new ShaderConfig.Effective(ShaderConfig.Stage.VERTEX, 0, 0, "", Map.of());

    static NativeCompiler.Request request(int index, String value) {
        String header = "values" + index + ".glslh";
        var unit = new NativeCompiler.Unit("shader" + index + ".vert",
            "#version 450\n#include \"" + header + "\"\nvoid main(){ gl_Position=vec4(v); }", CONFIG);
        return new NativeCompiler.Request(0, NativeCompiler.Operation.CHECK, List.of(unit),
            Map.of(header, "const float v=" + value + ";"), List.of());
    }

    public static void main(String[] args) {
        NativeCompiler.load(Path.of(args[0]));
        var sources = new LinkedHashMap<String, String>();
        sources.put("common.glslh", "struct Light { vec3 normal; vec4 color; };");
        for (int i = 0; i < FILES; i++) {
            var source = new StringBuilder("#version 450\n#include \"common.glslh\"\n");
            for (int j = 0; j < DECLARATIONS; j++) source.append("float value").append(j).append(";\n");
            source.append("void main(){Light light;vec4 c=light.}");
            sources.put("shader" + i + ".vert", source.toString());
        }
        long start = System.nanoTime();
        var trees = new LinkedHashMap<String, Syntax>();
        sources.forEach((path, text) -> trees.put(path, new Syntax(text)));
        var workspace = new Workspace(trees, List.of(), Map.of());
        for (String file : trees.keySet()) workspace.visible(file);
        double indexMillis = (System.nanoTime() - start) / 1e6;
        long[] samples = new long[1024];
        for (int i = -256; i < samples.length; i++) {
            String file = "shader" + Math.floorMod(i, FILES) + ".vert";
            int caret = sources.get(file).lastIndexOf('.') + 1;
            start = System.nanoTime();
            var result = workspace.completions(file, caret, true);
            long elapsed = System.nanoTime() - start;
            if (!result.containsAll(List.of("normal", "color")))
                throw new AssertionError(result);
            if (i >= 0)
                samples[i] = elapsed;
        }
        Arrays.sort(samples);
        var calls = new AtomicInteger();
        var cache = new AnalysisCache(32L << 20, r -> {
            calls.incrementAndGet();
            var result = NativeCompiler.compile(r);
            if (!result.success())
                throw new AssertionError(result.log());
            return result;
        });
        for (int i = 0; i < 64; i++) cache.analyze(request(i, "1.0"));
        int before = calls.get();
        start = System.nanoTime();
        for (int i = 0; i < 64; i++) cache.analyze(request(i, i == 7 ? "2.0" : "1.0"));
        double editMillis = (System.nanoTime() - start) / 1e6;
        if (calls.get() - before != 1)
            throw new AssertionError("Unrelated shaders recompiled");
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.printf(Locale.ROOT,
            "{\"files\":%d,\"globalsPerShader\":%d,\"sourceIndexMs\":%.3f,\"warmCompletionSamples\":%d,"
                + "\"warmCompletionMedianUs\":%.3f,\"warmCompletionP95Us\":%.3f,\"singleHeaderSweepMs\":%.3f,"
                + "\"shaderRequestsAfterEdit\":64,\"recompiledAfterEdit\":%d,\"heapUsedAtEndBytes\":%d}%n",
            trees.size(), DECLARATIONS, indexMillis, samples.length, samples[511] / 1e3, samples[972] / 1e3,
            editMillis, calls.get() - before, heap);
    }
}
