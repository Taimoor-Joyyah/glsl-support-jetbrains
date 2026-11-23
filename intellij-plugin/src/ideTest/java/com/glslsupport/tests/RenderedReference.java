package com.glslsupport.tests;

import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import java.net.URI;
import java.util.concurrent.*;
import javax.swing.JComponent;

/** Only loaded by the graphical test, which explicitly requires the IDE's embedded browser. */
final class RenderedReference {
    private static org.cef.browser.CefBrowser browser(JComponent panel) {
        return IdeSmoke.onEdt(() -> {
            var component = ReferenceWindow.find(panel, JComponent.class,
                c -> c.getClientProperty(JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP) instanceof JBCefBrowser);
            if (component == null)
                throw new AssertionError("The IDE did not create its embedded Chromium viewer");
            return ((JBCefBrowser) component.getClientProperty(JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP))
                .getCefBrowser();
        });
    }
    static void assertBackground(JComponent panel, java.awt.Color color) throws Exception {
        var browser = browser(panel);
        String rgb = "rgb(" + color.getRed() + ", " + color.getGreen() + ", " + color.getBlue() + ")";
        try (var client = browser.getDevToolsClient()) {
            var observed = new java.util.concurrent.atomic.AtomicReference<String>();
            try {
                IdeSmoke.await(() -> {
                    String state = client.executeDevToolsMethod("Runtime.evaluate",
                        "{\"expression\":\"JSON.stringify([document.body && getComputedStyle(document.body).backgroundColor,location.pathname,getComputedStyle(document.documentElement).getPropertyValue('--gl-bg')])\",\"returnByValue\":true}")
                        .completeOnTimeout("", 2, TimeUnit.SECONDS).join();
                    observed.set(state);
                    return state.contains(rgb);
                }, "rendered IDE documentation background " + rgb);
            } catch (AssertionError ex) {
                throw new AssertionError("Expected " + rgb + " in Chromium, observed " + observed.get(), ex);
            }
        }
    }
    static void assertPage(JComponent panel, String file, String expected) throws Exception {
        var browser = browser(panel);
        IdeSmoke.await(() -> {
            String address = browser.getURL();
            if (address == null || !address.startsWith("http://127.0.0.1:")
                || !URI.create(address).getPath().endsWith("/" + file + ".xhtml"))
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
