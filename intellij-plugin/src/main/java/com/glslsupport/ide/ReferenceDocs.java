package com.glslsupport.ide;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.*;
import com.intellij.ui.components.*;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLDocument;
import org.jetbrains.annotations.NotNull;

public final class ReferenceDocs implements ToolWindowFactory {
    private static final Path ROOT = Objects.requireNonNull(PathManager.getJarForClass(ReferenceDocs.class))
                                         .getParent()
                                         .resolveSibling("reference_pages");

    private static List<String> readLines(String file) {
        try {
            return Files.readAllLines(ROOT.resolve(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
    private static final List<String> NAMES = readLines("index.txt");
    private static final Set<String> INDEX = Set.copyOf(NAMES);
    private static final Map<String, String> ALIASES =
        readLines("aliases.tsv")
            .stream()
            .map(line -> line.split("\t", 2))
            .collect(Collectors.toUnmodifiableMap(a -> a[0], a -> a[1]));
    public static List<String> entries() {
        return NAMES;
    }
    public static URL page(String name) {
        if (name == null || !INDEX.contains(name))
            return null;
        try {
            return ROOT.resolve(ALIASES.getOrDefault(name, name) + ".xhtml").toUri().toURL();
        } catch (java.net.MalformedURLException ex) {
            throw new IllegalStateException(ex);
        }
    }
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        var browser = new Browser(window.getDisposable());
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            public void caretPositionChanged(@NotNull CaretEvent e) {
                if (!window.isVisible() || e.getEditor().getProject() != project)
                    return;
                var text = e.getEditor().getDocument().getCharsSequence();
                int left = e.getCaret().getOffset(), right = left;
                while (left > 0 && Character.isJavaIdentifierPart(text.charAt(left - 1))) left--;
                while (right < text.length() && Character.isJavaIdentifierPart(text.charAt(right))) right++;
                if (right - left > 1 && right - left < 100)
                    browser.open(text.subSequence(left, right).toString());
            }
        }, window.getDisposable());
        window.getContentManager().addContent(ContentFactory.getInstance().createContent(browser, "", false));
    }
    static final class Browser extends JPanel {
        final List<String> names = entries();
        final JBTextField search = new JBTextField();
        final DefaultListModel<String> results = new DefaultListModel<>();
        final JBList<String> list = new JBList<>(results);
        final JEditorPane html = new JEditorPane("text/html", "");
        final JBCefBrowser web;
        String current = "";
        Browser(Disposable parent) {
            super(new BorderLayout(4, 4));
            html.setEditable(false);
            search.getEmptyText().setText("Search OpenGL and GLSL reference");
            var side = new JPanel(new BorderLayout(4, 4));
            side.add(search, BorderLayout.NORTH);
            side.add(new JBScrollPane(list));
            JComponent viewer;
            if (JBCefApp.isSupported()) {
                web = new JBCefBrowser();
                Disposer.register(parent, web);
                viewer = web.getComponent();
            } else {
                web = null;
                viewer = new JBScrollPane(html);
            }
            var standalone = new JButton("Open in Browser");
            standalone.addActionListener(e -> {
                Path displayed = displayedFile();
                if (displayed != null) {
                    BrowserUtil.browse(displayed);
                    return;
                }
                var url = page(current);
                if (url != null)
                    BrowserUtil.browse(url);
            });
            side.add(standalone, BorderLayout.SOUTH);
            var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, side, viewer);
            split.setResizeWeight(.27);
            split.setDividerLocation(185);
            add(split);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting())
                    open(list.getSelectedValue());
            });
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void changedUpdate(DocumentEvent e) {
                    filter();
                }
                public void insertUpdate(DocumentEvent e) {
                    filter();
                }
                public void removeUpdate(DocumentEvent e) {
                    filter();
                }
            });
            search.addActionListener(e -> {
                if (!results.isEmpty())
                    list.setSelectedIndex(0);
            });
            html.addHyperlinkListener(e -> {
                if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED)
                    return;
                var href = e.getDescription();
                if (href == null)
                    return;
                if (href.startsWith("specs/") || href.equals("sources.txt")) {
                    var path = ROOT.resolve(href).normalize();
                    if ((path.startsWith(ROOT.resolve("specs")) || path.equals(ROOT.resolve("sources.txt")))
                        && Files.isRegularFile(path))
                        BrowserUtil.browse(path);
                    return;
                }
                if (href.startsWith("#")) {
                    html.scrollToReference(href.substring(1));
                    return;
                }
                var path = href.split("#", 2);
                String file = path[0].substring(path[0].lastIndexOf('/') + 1);
                file = file.replaceFirst("\\.(xml|xhtml|html)$", "");
                if (open(file) && path.length == 2)
                    html.scrollToReference(path[1]);
            });
            filter();
            open("OpenGL46");
        }
        void filter() {
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            results.clear();
            for (String name : names)
                if (name.toLowerCase(Locale.ROOT).contains(query))
                    results.addElement(name);
        }

        private Path displayedFile() {
            if (web == null)
                return null;
            String address = web.getCefBrowser().getURL();
            if (address == null || !address.startsWith("file:"))
                return null;
            try {
                var path = Path.of(URI.create(address.split("#", 2)[0])).normalize();
                return path.startsWith(ROOT) ? path : null;
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        boolean open(String name) {
            URL url = page(name);
            if (url == null)
                return false;
            if (current.equals(name)
                && (web == null
                    || ROOT.resolve(ALIASES.getOrDefault(name, name) + ".xhtml").equals(displayedFile())))
                return true;
            if (web != null) {
                web.loadURL(url.toExternalForm());
            } else {
                try (var in = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
                    var doc = (HTMLDocument) html.getEditorKit().createDefaultDocument();
                    doc.setBase(url);
                    html.getEditorKit().read(in, doc, 0);
                    html.setDocument(doc);
                    html.setCaretPosition(0);
                } catch (Exception ex) {
                    html.setText("Could not open " + name);
                    return false;
                }
            }
            current = name;
            int index = results.indexOf(name);
            if (index >= 0 && list.getSelectedIndex() != index) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
            }
            return true;
        }
    }
}
