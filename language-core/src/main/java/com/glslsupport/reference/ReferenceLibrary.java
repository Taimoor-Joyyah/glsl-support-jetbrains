package com.glslsupport.reference;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** The editor, browser and bulk download all read through the same versioned cache. */
public final class ReferenceLibrary {
    public static final URI KHRONOS = URI.create("https://raw.githubusercontent.com/KhronosGroup/OpenGL-Refpages/4bdf67a73d040f79bd7b087f5c1e588809e1f329/gl4/html/");
    private static final Map<String, String> SPECIFICATIONS = Map.of(
        "specs/glspec46.core.pdf", "https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf",
        "specs/GLSLangSpec.4.60.pdf", "https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf",
        "specs/ARB_clip_control.txt", "https://registry.khronos.org/OpenGL/extensions/ARB/ARB_clip_control.txt");

    @FunctionalInterface public interface Fetcher {
        byte[] fetch(URI address, int limit) throws IOException;
    }
    private record Entry(String kind, int size, String hash, String original) {}
    private final Map<String, Entry> files = new TreeMap<>();
    private final List<String> names;
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Path root;
    private final Fetcher fetcher;
    private final Object mathLock = new Object();
    private volatile boolean renderingReady;

    public ReferenceLibrary(Path cache, Fetcher fetcher) throws IOException {
        this.fetcher = fetcher;
        byte[] catalog = bundled("files.tsv");
        root = cache.resolve(hash(catalog).substring(0, 20));
        for (String line : new String(catalog, StandardCharsets.UTF_8).lines().toList()) {
            String[] row = line.split("\t", -1);
            files.put(row[0], new Entry(row[1], Integer.parseInt(row[2]), row[3], row[4]));
        }
        names = new String(bundled("index.txt"), StandardCharsets.UTF_8).lines().toList();
        for (String name : names) aliases.put(name, name);
        new String(bundled("aliases.tsv"), StandardCharsets.UTF_8).lines().forEach(line -> {
            String[] pair = line.split("\t");
            aliases.put(pair[0], pair[1]);
        });
    }
    public List<String> names() { return names; }
    public String filename(String symbol) {
        String target = aliases.get(symbol);
        return target == null ? null : target + ".xhtml";
    }
    public boolean contains(String path) { return files.containsKey(path); }
    public static byte[] download(URI address, int limit) throws IOException {
        var connection = address.toURL().openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "GLSL-Support-Reference/1.2");
        try (var in = connection.getInputStream()) {
            if (connection.getContentLengthLong() > limit) throw new IOException("Reference download is too large");
            return bounded(in, limit);
        } finally {
            if (connection instanceof HttpURLConnection http) http.disconnect();
        }
    }
    private static byte[] bounded(InputStream in, int limit) throws IOException {
        byte[] data = in.readNBytes(limit + 1);
        if (data.length > limit) throw new IOException("Unexpected reference resource length");
        return data;
    }
    private static byte[] bundled(String name) throws IOException {
        try (var stream = ReferenceLibrary.class.getResourceAsStream("/reference/" + name)) {
            if (stream == null) throw new FileNotFoundException(name);
            return stream.readAllBytes();
        }
    }
    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
    private static byte[] checked(byte[] data, String expected) throws IOException {
        if (!hash(data).equals(expected)) throw new IOException("Reference checksum mismatch");
        return data;
    }
    private boolean cached(String name) throws IOException {
        var file = root.resolve(name);
        var entry = files.get(name);
        return Files.isRegularFile(file) && Files.size(file) == entry.size
            && hash(Files.readAllBytes(file)).equals(entry.hash);
    }
    private void write(Path destination, byte[] data) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temp = Files.createTempFile(destination.getParent(), ".download-", ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }
    private byte[] remote(String name, Entry entry) throws IOException {
        if (entry.kind.equals("spec"))
            return checked(fetcher.fetch(URI.create(SPECIFICATIONS.get(name)), entry.size), entry.hash);
        byte[] raw = checked(fetcher.fetch(KHRONOS.resolve(name), 4 * 1024 * 1024), entry.original);
        return checked(ReferenceFormatter.format(name, raw), entry.hash);
    }
    /** Returns only a complete page. Its stylesheet, notices and deferred rendering assets are on disk. */
    public Path get(String name) throws IOException {
        Entry entry = files.get(name);
        if (entry == null) throw new FileNotFoundException("Unknown reference resource");
        if (name.endsWith(".xhtml")) {
            get("opengl-man.css");
            get("sources.txt");
            if (entry.kind.equals("upstream")) ensureMath();
        }
        synchronized (locks.computeIfAbsent(name, key -> new Object())) {
            if (!cached(name)) {
                byte[] data = switch (entry.kind) {
                    case "bundled", "math" -> checked(bundled(name), entry.hash);
                    default -> remote(name, entry);
                };
                write(root.resolve(name), data);
            }
            return root.resolve(name);
        }
    }
    private void ensureMath() throws IOException {
        if (renderingReady) return;
        synchronized (mathLock) {
            if (renderingReady) return;
            Path ready = root.resolve(".math-ready");
            if (!Files.isRegularFile(ready)) {
                for (var resource : files.entrySet())
                    if (resource.getValue().kind.equals("math")) get(resource.getKey());
                write(ready, new byte[0]);
            }
            renderingReady = true;
        }
    }
    public boolean complete() { return Files.isRegularFile(root.resolve(".complete")); }
    /** A cancelled bulk download keeps verified entries and can be resumed. */
    public synchronized void downloadAll(IntConsumer progress) throws IOException {
        progress.accept(0);
        ensureMath();
        List<String> documents = files.keySet().stream().filter(n -> !files.get(n).kind.equals("math")).toList();
        var workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("Khronos reference download ", 0).factory());
        var finished = new ExecutorCompletionService<Path>(workers);
        int next = 0, saved = 0;
        try {
            while (saved < documents.size()) {
                while (next < documents.size() && next - saved < 4) {
                    String name = documents.get(next++);
                    finished.submit(() -> get(name));
                }
                progress.accept(saved * 100 / documents.size());
                var done = finished.poll(200, TimeUnit.MILLISECONDS);
                if (done != null) {
                    done.get();
                    saved++;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Reference download interrupted");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException io) throw io;
            throw new IOException("Reference download failed", ex.getCause());
        } finally { workers.shutdownNow(); }
        write(root.resolve(".complete"), new byte[0]);
        progress.accept(100);
    }
}
