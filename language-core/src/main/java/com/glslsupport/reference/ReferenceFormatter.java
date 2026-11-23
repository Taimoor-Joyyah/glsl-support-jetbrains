package com.glslsupport.reference;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Byte-preserving transformations for the pinned Khronos XHTML edition. */
final class ReferenceFormatter {
    private static final Pattern DIV = Pattern.compile("<(/?)div\\b[^>]*>");
    private static final Pattern OMIT = Pattern.compile(" id=\"(?:versions|copyright)\"", Pattern.CASE_INSENSITIVE);
    private static final String POLICY = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
        + "script-src 'self' file: 'unsafe-inline' 'unsafe-eval'; style-src 'self' file: 'unsafe-inline'; "
        + "font-src 'self' file: data:; img-src 'self' file: data:; connect-src 'self' file:; "
        + "base-uri 'none'; object-src 'none'\"/>";
    private static final String REGISTRY = "https://registry.khronos.org/OpenGL-Refpages/gl4/html/";

    static byte[] format(String filename, byte[] original) {
        String raw = new String(original, StandardCharsets.UTF_8);
        var result = new StringBuilder(raw.length());
        var tags = DIV.matcher(raw);
        int keep = 0, depth = 0;
        while (tags.find()) {
            boolean closing = !tags.group(1).isEmpty();
            if (depth == 0 && !closing && OMIT.matcher(tags.group()).find()) {
                result.append(raw, keep, tags.start());
                depth = 1;
            } else if (depth > 0 && !tags.group().endsWith("/>")) {
                depth += closing ? -1 : 1;
                if (depth == 0) keep = tags.end();
            }
        }
        String source = "<meta name=\"dcterms.source\" content=\"" + REGISTRY + filename + "\"/>";
        String footer = "<footer><p><small>Khronos reference, offline edition by GLSL Support, "
            + "2026-11-22. <a href=\"sources.txt\">Source and edition notes</a>.</small></p></footer>";
        return result.append(raw, keep, raw.length()).toString()
            .replace("https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.1/MathJax.js?config=TeX-AMS-MML_HTMLorMML",
                "mathjax/MathJax.js?config=TeX-AMS-MML_SVG")
            .replace("MathJax.Hub.Config({\n", "MathJax.Hub.Config({\n                showMathMenu: false,\n")
            .replace("tex2jax: {\n", "tex2jax: {\n                    skipTags: [\"script\",\"noscript\",\"style\",\"textarea\",\"pre\",\"annotation\",\"annotation-xml\"],\n")
            .replace("href=\"css/opengl-man.css\"", "href=\"opengl-man.css\"")
            .replace("https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf", "specs/glspec46.core.pdf")
            .replace("https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.html", "specs/GLSLangSpec.4.60.pdf")
            .replace("http://www.opengl.org/registry/\" target=\"_top\">http://www.opengl.org/registry/",
                "specs/ARB_clip_control.txt\">GL_ARB_clip_control (offline)")
            .replace("<head>", "<head>" + POLICY).replace("</head>", source + "</head>")
            .replace("<footer/>", footer).getBytes(StandardCharsets.UTF_8);
    }
}
