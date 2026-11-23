package com.glslsupport.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import java.net.URL;
import javax.swing.JComponent;

/** Keep JCEF types out of the tool window's fields and method signatures. */
final class JcefReferenceViewer implements ReferenceDocs.WebViewer {
    private final JBCefBrowser browser;

    private JcefReferenceViewer(Disposable parent) {
        browser = new JBCefBrowser();
        Disposer.register(parent, browser);
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

    public String address() {
        return browser.getCefBrowser().getURL();
    }
}
