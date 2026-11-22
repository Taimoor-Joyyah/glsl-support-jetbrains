package com.glslsupport.lang;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class ReferenceTest {
    static final Path PAGES = Path.of("intellij-plugin/src/main/resources/reference_pages");
    static final Path FIXTURES = Path.of("test-data/reference");

    @Test
    void indexContainsEveryNewCoreRegistryEntryAndShaderBuiltIn() throws Exception {
        var indexed = Files.readAllLines(PAGES.resolve("index.txt"));
        var all = new HashSet<>(indexed);
        assertEquals(all.size(), indexed.size());
        var aliases = new HashMap<String, String>();
        for (var row : Files.readAllLines(PAGES.resolve("aliases.tsv"))) {
            var pair = row.split("\t");
            assertNull(aliases.put(pair[0], pair[1]), pair[0]);
            assertTrue(all.contains(pair[0]) && all.contains(pair[1]), row);
        }
        assertEquals("glGet", aliases.get("glGetIntegerv"));
        for (var name : indexed)
            assertTrue(Files.isRegularFile(PAGES.resolve(aliases.getOrDefault(name, name) + ".xhtml")), name);
        var required = new ArrayList<>(Files.readAllLines(Path.of("test-data/reference/gl46-registry.txt")));
        assertEquals(26, required.size());
        for (var row : Files.readAllLines(Path.of("language-core/src/main/resources/glsl/core46.tsv"))) {
            var parts = row.split("\\t", -1);
            required.add(parts[1]);
            assertTrue(Catalog.has(parts[1], parts[0]));
            if (parts[0].equals("functions"))
                assertEquals(parts[3], Catalog.entries(parts[1]).getFirst().parameters());
        }
        required.addAll(List.of("OpenGL46", "GL_GEOMETRY_SHADER_INVOCATIONS"));
        for (String name : required) assertTrue(all.contains(name), name);
    }

    @Test
    void allPageSectionsAndLinksAreOfflineIncludingSpecifications() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var parser = factory.newDocumentBuilder();
        int count = 0, equations = 0, bootstraps = 0;
        try (var pages = Files.newDirectoryStream(PAGES, "*.xhtml")) {
            for (var page : pages) {
                var doc = parser.parse(page.toFile());
                var tags = doc.getElementsByTagName("*");
                boolean policy = false;
                for (int i = 0; i < tags.getLength(); i++) {
                    var el = (Element) tags.item(i);
                    var tag = el.getLocalName();
                    if (tag.matches("h[1-6]")) {
                        var heading = el.getTextContent().strip().toLowerCase(Locale.ROOT);
                        assertFalse(
                            Set.of("version support", "copyright", "copyright and license").contains(heading),
                            page.toString());
                    }
                    assertFalse(
                        Set.of("versions", "copyright").contains(el.getAttribute("id").toLowerCase(Locale.ROOT)),
                        page.toString());
                    if (tag.equals("meta") && "Content-Security-Policy".equals(el.getAttribute("http-equiv")))
                        policy = el.getAttribute("content").contains("default-src 'none'");
                    if (tag.equals("math"))
                        equations++;
                    for (var attr : List.of("src", "href", "data", "poster")) {
                        if (!el.hasAttribute(attr))
                            continue;
                        var link = URI.create(el.getAttribute(attr));
                        assertFalse(link.isAbsolute() || link.getRawAuthority() != null, page + " -> " + link);
                        if (link.getPath().isEmpty())
                            continue;
                        assertFalse(Path.of(link.getPath()).isAbsolute(), page + " -> " + link);
                        var target = PAGES.resolve(link.getPath()).normalize();
                        assertTrue(target.startsWith(PAGES) && Files.isRegularFile(target), page + " -> " + link);
                        if (tag.equals("script") && link.getPath().endsWith("/MathJax.js"))
                            bootstraps++;
                    }
                }
                assertTrue(policy, page.toString());
                count++;
            }
        }
        assertTrue(count >= 544 && bootstraps >= 499 && equations >= 700,
            "pages=" + count + ", MathJax pages=" + bootstraps + ", equations=" + equations);
    }

    @Test
    void publishedPagesAndAllLateLoadedAssetsMatchTheirSnapshot() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var sourceNames = new HashSet<String>();
        for (var row : Files.readAllLines(FIXTURES.resolve("khronos-snapshot.tsv"))) {
            var fields = row.split("\t");
            assertTrue(sourceNames.add(fields[0]), fields[0]);
            assertTrue(fields[1].matches("[0-9a-f]{64}"));
            assertEquals(fields[2],
                HexFormat.of().formatHex(digest.digest(Files.readAllBytes(PAGES.resolve(fields[0])))), fields[0]);
        }
        var notices = Files.readString(Path.of("licenses/opengl-reference-pages.txt"));
        for (var name : List.of(
                 "glMultiDrawArraysIndirectCount", "glMultiDrawElementsIndirectCount", "glPolygonOffsetClamp")) {
            var filename = name + ".xhtml";
            assertTrue(sourceNames.contains(filename) && notices.contains(filename), filename);
            var text = Files.readString(PAGES.resolve(filename));
            assertTrue(
                text.contains("DocBook XSL") && text.contains(">Parameters<") && text.contains(">Description<"),
                name);
        }
        var assets = new HashSet<String>();
        for (var row : Files.readAllLines(FIXTURES.resolve("offline-assets.tsv"))) {
            var fields = row.split("\t");
            assertTrue(assets.add(fields[0]), fields[0]);
            assertEquals(fields[1],
                HexFormat.of().formatHex(digest.digest(Files.readAllBytes(PAGES.resolve(fields[0])))), fields[0]);
        }
        assertTrue(assets.containsAll(List.of("mathjax/MathJax.js", "mathjax/config/TeX-AMS-MML_SVG.js",
            "mathjax/extensions/MathML/content-mathml.js", "mathjax/jax/output/SVG/jax.js",
            "mathjax/localization/fr/fr.js", "specs/GLSLangSpec.4.60.pdf", "specs/glspec46.core.pdf",
            "specs/ARB_clip_control.txt")));
    }
}
