package com.glslsupport.bridge;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** A project-scoped, byte-bounded cache keyed by immutable compiler inputs. */
public final class AnalysisCache {
    private record Saved(NativeCompiler.Result result, long bytes) {}
    private final LinkedHashMap<NativeCompiler.Request, Saved> results = new LinkedHashMap<>(16, .75f, true);
    private final ConcurrentMap<NativeCompiler.Request, CompletableFuture<NativeCompiler.Result>> running =
        new ConcurrentHashMap<>();
    private final Function<NativeCompiler.Request, NativeCompiler.Result> compiler;
    private final long limit;
    private long used;
    public AnalysisCache(long limit, Function<NativeCompiler.Request, NativeCompiler.Result> compiler) {
        this.limit = limit;
        this.compiler = compiler;
    }
    public NativeCompiler.Result analyze(NativeCompiler.Request request) {
        var future = new CompletableFuture<NativeCompiler.Result>();
        CompletableFuture<NativeCompiler.Result> existing;
        synchronized (results) {
            var found = results.get(request);
            if (found != null)
                return found.result;
            existing = running.putIfAbsent(request, future);
        }
        if (existing != null)
            return existing.join();
        try {
            var result = compiler.apply(request);
            long bytes = 4L * result.words().length
                + 2L * (result.log().length() + result.layout().length() + result.assembly().length());
            bytes += 2L * result.linkLog().length();
            for (var stage : result.stageLogs()) bytes += 2L * stage.length();
            for (var e : result.trace())
                bytes += 96L + 2L * (e.file().length() + e.kind().length() + e.name().length() + e.value().length());
            for (var s : request.units()) bytes += 2L * s.text().length();
            for (var s : request.headers().values()) bytes += 2L * s.length();
            synchronized (results) {
                if (bytes <= limit) {
                    var old = results.put(request, new Saved(result, bytes));
                    used += bytes - (old == null ? 0 : old.bytes);
                    while (used > limit || results.size() > 128) {
                        var first = results.pollFirstEntry();
                        used -= first.getValue().bytes;
                    }
                }
            }
            future.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            running.remove(request, future);
        }
    }
}
