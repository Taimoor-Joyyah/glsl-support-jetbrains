package com.glslsupport.tests;

import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.*;
import javax.swing.JComponent;

/** Only loaded by the graphical test, which explicitly requires the IDE's embedded browser. */
final class RenderedReference {
    static void assertPage(JComponent panel, String file, String expected) throws Exception {
        var browser = IdeSmoke.onEdt(() -> {
            var component = ReferenceWindow.find(panel, JComponent.class,
                c -> c.getClientProperty(JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP) instanceof JBCefBrowser);
            if (component == null)
                throw new AssertionError("The IDE did not create its embedded Chromium viewer");
            return ((JBCefBrowser) component.getClientProperty(JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP))
                .getCefBrowser();
        });
        IdeSmoke.await(() -> {
            String address = browser.getURL();
            if (address == null || !address.startsWith("file:")
                || !Path.of(URI.create(address)).getFileName().toString().equals(file + ".xhtml"))
                return false;
            var text = new CompletableFuture<String>();
            browser.getText(text::complete);
            try {
                return text.get(2, TimeUnit.SECONDS).contains(expected);
            } catch (TimeoutException e) {
                return false;
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }, "embedded browser to display " + file + " with " + expected);
    }
}
