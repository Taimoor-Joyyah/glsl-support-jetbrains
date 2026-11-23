package com.glslsupport.reference;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceLibraryTest {
    @TempDir Path cache;
    static final Path REPO = Path.of("").toAbsolutePath();
    static final Path PAGES = REPO.resolve("intellij-plugin/src/main/resources/reference_pages");
    final List<URI> requests = Collections.synchronizedList(new ArrayList<>());
    boolean corrupt, offline;

    private ReferenceLibrary library() throws IOException {
        return new ReferenceLibrary(cache, (uri, limit) -> {
            requests.add(uri);
            if (offline) throw new IOException("Network disconnected");
            if (uri.getHost().equals("registry.khronos.org"))
                return Files.readAllBytes(PAGES.resolve("specs").resolve(Path.of(uri.getPath()).getFileName()));
            assertEquals("raw.githubusercontent.com", uri.getHost());
            assertTrue(uri.getPath().startsWith("/KhronosGroup/OpenGL-Refpages/"), uri.toString());
            if (corrupt) return new byte[]{1};
            if (uri.toString().startsWith(ReferenceLibrary.KHRONOS.toString()))
                return Files.readAllBytes(Path.of("test-data/reference/upstream", ReferenceLibrary.KHRONOS.relativize(uri).getPath()));
            throw new IOException("Unexpected endpoint " + uri);
        });
    }
    @Test void supplementsAreAvailableOnAnEmptyCacheWithoutNetwork() throws Exception {
        offline = true;
        var library = library();
        for (String n : List.of("OpenGL46", "glSpecializeShader", "gl_DrawID"))
            assertTrue(Files.readString(library.get(library.filename(n))).contains("<body>"));
        assertNull(library.filename("no_such_entry"));
        assertThrows(FileNotFoundException.class, () -> library.get("../sources.txt"));
        assertTrue(requests.isEmpty());
    }
    @Test void concurrentAliasesFetchOnePageThenReopenOfflineAcrossSessions() throws Exception {
        var lib = library();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<Path>>();
            for (String n : List.of("glGet", "glGetIntegerv", "glGetFloatv", "glGetBooleanv"))
                jobs.add(pool.submit(() -> lib.get(lib.filename(n))));
            for (var f : jobs) assertEquals("glGet.xhtml", f.get().getFileName().toString());
        }
        assertEquals(List.of(ReferenceLibrary.KHRONOS.resolve("glGet.xhtml")), requests);
        requests.clear();
        offline = true;
        var reopened = library();
        assertTrue(Files.readString(reopened.get("glGet.xhtml")).contains("glGetIntegerv"));
        assertTrue(Files.size(reopened.get("mathjax/extensions/MathML/content-mathml.js")) > 0);
        assertTrue(requests.isEmpty());
    }
    @Test void fetchesOnlyOfficialKhronosAndReformatsBeforePublishingToCache() throws Exception {
        var lib = library();
        for (String n : List.of("glPolygonOffsetClamp.xhtml", "mix.xhtml", "glClipControl.xhtml")) {
            Path result = lib.get(n);
            assertArrayEquals(Files.readAllBytes(PAGES.resolve(n)), Files.readAllBytes(result), n);
            assertTrue(requests.contains(ReferenceLibrary.KHRONOS.resolve(n)));
        }
        offline = true;
        requests.clear();
        assertTrue(Files.size(library().get("mix.xhtml")) > 0);
        assertTrue(requests.isEmpty());
    }
    @Test void unavailableOrTruncatedPageIsRetriedAndDamagedCacheIsRepaired() throws Exception {
        var lib = library();
        lib.get("mix.xhtml");
        corrupt = true;
        assertThrows(IOException.class, () -> lib.get("glBindTexture.xhtml"));
        corrupt = false;
        Path file = lib.get("glBindTexture.xhtml");
        Files.writeString(file, "short file");
        assertTrue(Files.readString(lib.get("glBindTexture.xhtml")).contains("bind a named texture"));
    }
    @Test void cancellingBulkDownloadRetainsPagesAndCanResumeOfflineAfterCompletion() throws Exception {
        var lib = library();
        // Model an existing partial cache. The clean-cache integration suite fetches all 499 originals.
        Path location = lib.get("OpenGL46.xhtml").getParent();
        try (var pages = Files.newDirectoryStream(PAGES, "*.xhtml")) {
            for (var page : pages)
                if (!Files.exists(Path.of("test-data/reference/upstream").resolve(page.getFileName())))
                    Files.copy(page, location.resolve(page.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        assertThrows(CancellationException.class, () -> lib.downloadAll(p -> {
            if (p >= 10) throw new CancellationException();
        }));
        assertFalse(lib.complete());
        lib.downloadAll(p -> {});
        assertTrue(lib.complete());
        offline = true;
        requests.clear();
        var fresh = library();
        for (String name : new HashSet<>(fresh.names().stream().map(fresh::filename).toList())) fresh.get(name);
        assertTrue(Files.readString(fresh.get("specs/ARB_clip_control.txt")).contains("ARB_clip_control"));
        assertTrue(requests.isEmpty());
    }
    @Test void browserLinksFetchAnotherPageAndThemeChangesWithoutOverwritingCachedContent() throws Exception {
        var lib = library();
        var theme = new AtomicReference<>(":root{--gl-bg:#012345}");
        try (var server = new ReferenceServer(lib, theme::get)) {
            URI overview = server.page("OpenGL46");
            assertTrue(server.owns(overview));
            assertFalse(server.owns(URI.create("https://elsewhere.example/")));
            assertTrue(new String(ReferenceLibrary.download(overview, 100000)).contains("glPolygonOffsetClamp.xhtml"));
            var link = overview.resolve("glPolygonOffsetClamp.xhtml");
            assertTrue(new String(ReferenceLibrary.download(link, 100000)).contains("mathjax/MathJax.js"));
            var css = overview.resolve("opengl-man.css");
            assertTrue(new String(ReferenceLibrary.download(css, 50000)).contains(theme.get()));
            theme.set(":root{--gl-bg:#fedcba}");
            offline = true;
            requests.clear();
            assertTrue(new String(ReferenceLibrary.download(css, 50000)).contains(theme.get()));
            assertTrue(new String(ReferenceLibrary.download(link, 100000)).contains("glPolygonOffsetClamp"));
            assertThrows(IOException.class, () -> ReferenceLibrary.download(overview.resolve("missing.xhtml"), 1000));
            assertTrue(requests.isEmpty());
        }
    }
}
