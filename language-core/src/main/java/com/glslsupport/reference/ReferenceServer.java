package com.glslsupport.reference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Serves relative XHTML links and MathJax requests through the same cache, even in an external browser. */
public final class ReferenceServer implements AutoCloseable {
    private final HttpServer server;
    private final URI base;
    private final ExecutorService workers;
    private final ReferenceLibrary library;
    private final Supplier<String> palette;

    public ReferenceServer(ReferenceLibrary library, Supplier<String> palette) throws IOException {
        this.library = library;
        this.palette = palette;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        String path = "/" + UUID.randomUUID() + "/";
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("GLSL reference ", 0).factory());
        server.setExecutor(workers);
        server.createContext(path, this::serve);
        server.start();
    }
    public URI page(String symbol) {
        String filename = library.filename(symbol);
        return filename == null ? null : base.resolve(filename);
    }
    public boolean owns(URI uri) {
        return uri != null && Objects.equals(uri.getScheme(), base.getScheme())
            && Objects.equals(uri.getRawAuthority(), base.getRawAuthority()) && uri.getPath().startsWith(base.getPath());
    }
    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath().substring(base.getPath().length());
            boolean head = exchange.getRequestMethod().equals("HEAD");
            if (!head && !exchange.getRequestMethod().equals("GET")) { exchange.sendResponseHeaders(405, -1); return; }
            if (!library.contains(path)) { exchange.sendResponseHeaders(404, -1); return; }
            Path file;
            try { file = library.get(path); }
            catch (IOException failure) {
                String message = "This page has not been saved for offline use. Reconnect and reload to try again.";
                reply(exchange, head, 503, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
                return;
            }
            String contentType = switch (path.substring(path.lastIndexOf('.') + 1)) {
                case "xhtml" -> "application/xhtml+xml; charset=utf-8";
                case "js" -> "application/javascript; charset=utf-8";
                case "css" -> "text/css; charset=utf-8";
                case "pdf" -> "application/pdf";
                case "svg" -> "image/svg+xml";
                default -> "text/plain; charset=utf-8";
            };
            if (path.equals("opengl-man.css")) {
                reply(exchange, head, 200, contentType, (Files.readString(file) + "\n" + palette.get()).getBytes(StandardCharsets.UTF_8));
            } else {
                headers(exchange, contentType, Files.size(file));
                exchange.sendResponseHeaders(200, head ? -1 : Files.size(file));
                if (!head) Files.copy(file, exchange.getResponseBody());
            }
        }
    }
    private static void headers(HttpExchange x, String type, long size) {
        var headers = x.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Content-Length", Long.toString(size));
        headers.set("Cache-Control", "no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
    }
    private static void reply(HttpExchange x, boolean head, int code, String type, byte[] data) throws IOException {
        headers(x, type, data.length);
        x.sendResponseHeaders(code, head ? -1 : data.length);
        if (!head) x.getResponseBody().write(data);
    }
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
