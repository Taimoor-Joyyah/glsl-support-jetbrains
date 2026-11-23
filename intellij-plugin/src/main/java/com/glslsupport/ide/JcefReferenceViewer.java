package com.glslsupport.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import java.net.URL;
import javax.swing.JComponent;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

/** Keep JCEF types out of the tool window's fields and method signatures. */
final class JcefReferenceViewer implements ReferenceDocs.WebViewer {
    private final JBCefBrowser browser;
    private volatile String updateColors = "";

    private JcefReferenceViewer(Disposable parent) {
        browser = new JBCefBrowser();
        Disposer.register(parent, browser);
        var handler = new CefLoadHandlerAdapter() {
            @Override public void onLoadEnd(CefBrowser cef, CefFrame frame, int status) {
                if (frame.isMain() && !updateColors.isEmpty())
                    frame.executeJavaScript(updateColors, frame.getURL(), 0);
            }
        };
        var client = browser.getJBCefClient();
        var cef = browser.getCefBrowser();
        client.addLoadHandler(handler, cef);
        Disposer.register(parent, () -> client.removeLoadHandler(handler, cef));
    }

    static ReferenceDocs.WebViewer create(Disposable parent) {
        return JBCefApp.isSupported() ? new JcefReferenceViewer(parent) : null;
    }

    public JComponent component() {
        return browser.getComponent();
    }

    public void load(URL page) {
        browser.loadURL(page.toExternalForm());
    }
    public void setTheme(ReferenceAccess.Theme theme) {
        updateColors = "if(document.documentElement) document.documentElement.style.cssText += ';"
            + theme.declarations() + "';";
        String url = address();
        if (url != null && !url.isEmpty()) browser.getCefBrowser().executeJavaScript(updateColors, url, 0);
    }

    public String address() {
        return browser.getCefBrowser().getURL();
    }
}
