package com.glslsupport.ide;

import com.glslsupport.reference.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.colors.*;
import com.intellij.ui.ColorUtil;
import java.awt.Color;
import java.io.*;
import java.net.*;

@Service(Service.Level.APP)
public final class ReferenceAccess implements Disposable {
    final ReferenceLibrary library;
    private ReferenceServer server;
    volatile Theme theme;

    public ReferenceAccess() {
        try {
            library = new ReferenceLibrary(PathManager.getSystemDir().resolve("glsl-support/reference"), ReferenceLibrary::download);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    static ReferenceAccess instance() { return ApplicationManager.getApplication().getService(ReferenceAccess.class); }
    synchronized ReferenceServer server() {
        if (server == null) {
            try { server = new ReferenceServer(library, () -> theme == null ? "" : theme.css()); }
            catch (IOException ex) { throw new UncheckedIOException(ex); }
        }
        return server;
    }
    public synchronized void dispose() {
        if (server != null) server.close();
    }
    record Theme(String background, String foreground, String link, String panel, String border, boolean dark) {
        static Theme current() {
            var scheme = EditorColorsManager.getInstance().getGlobalScheme();
            Color bg = scheme.getDefaultBackground(), fg = scheme.getDefaultForeground();
            var attributes = scheme.getAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES);
            Color link = attributes == null ? null : attributes.getForegroundColor();
            if (link == null) link = fg;
            return new Theme(hex(bg), hex(fg), hex(link), hex(ColorUtil.mix(bg, fg, .09)),
                hex(ColorUtil.mix(bg, fg, .42)), ColorUtil.isDark(bg));
        }
        private static String hex(Color c) { return "#" + ColorUtil.toHex(c); }
        String declarations() {
            return "color-scheme:" + (dark ? "dark" : "light") + "; --gl-bg:" + background
                + "; --gl-fg:" + foreground + "; --gl-link:" + link + "; --gl-active:" + link
                + "; --gl-panel:" + panel + "; --gl-border:" + border + ";";
        }
        String css() { return ":root { " + declarations() + " }"; }
    }
}
