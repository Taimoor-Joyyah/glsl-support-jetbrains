import com.glslsupport.reference.ReferenceLibrary;
import java.io.*;
import java.nio.file.*;
import java.util.*;

class ReferenceCacheCheck {
    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[0]);
        Path pages = Path.of("intellij-plugin/src/main/resources/reference_pages");
        Path originals = Path.of("build/downloads/khronos-gl4");
        boolean live = args.length > 1 && args[1].startsWith("--live");
        boolean single = live && args[1].equals("--live");
        Set<String> fetched = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var library = new ReferenceLibrary(cache, (uri, limit) -> {
            boolean page = uri.getHost().equals("raw.githubusercontent.com")
                && uri.getPath().startsWith("/KhronosGroup/OpenGL-Refpages/");
            if (!page && !uri.getHost().equals("registry.khronos.org")) throw new AssertionError("Unexpected source " + uri);
            String filename = Path.of(uri.getPath()).getFileName().toString();
            if (page) fetched.add(filename);
            if (live) return ReferenceLibrary.download(uri, limit);
            return Files.readAllBytes((page ? originals : pages.resolve("specs")).resolve(filename));
        });
        if (single) library.get("glPolygonOffsetClamp.xhtml");
        else library.downloadAll(percent -> {});
        var offline = new ReferenceLibrary(cache, (uri, limit) -> { throw new AssertionError("Offline request " + uri); });
        if (single) {
            if (fetched.size() != 1 || Files.mismatch(offline.get("glPolygonOffsetClamp.xhtml"), pages.resolve("glPolygonOffsetClamp.xhtml")) != -1)
                throw new AssertionError("Live Khronos reference mismatch");
            offline.get("mathjax/extensions/MathML/content-mathml.js");
            System.out.println("Live Khronos XHTML verified and reopened with the network disconnected.");
        } else {
            if (fetched.size() != 499) throw new AssertionError("Expected 499 direct Khronos requests, saw " + fetched.size());
            for (String name : new TreeSet<>(offline.names())) {
                String target = offline.filename(name);
                if (Files.mismatch(offline.get(target), pages.resolve(target)) != -1) throw new AssertionError(target);
            }
            System.out.println(offline.get("OpenGL46.xhtml").getParent());
        }
    }
}
