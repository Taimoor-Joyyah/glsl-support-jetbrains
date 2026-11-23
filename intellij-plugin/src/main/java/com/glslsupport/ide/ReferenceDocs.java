package com.glslsupport.ide;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.psi.*;
import com.intellij.ui.components.*;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLDocument;
import org.jetbrains.annotations.NotNull;

public final class ReferenceDocs implements ToolWindowFactory, DumbAware {
    public static List<String> entries() { return ReferenceAccess.instance().library.names(); }
    public static URL page(String name) {
        if (name == null || ReferenceAccess.instance().library.filename(name) == null) return null;
        try { return ReferenceAccess.instance().server().page(name).toURL(); }
        catch (java.net.MalformedURLException ex) { throw new IllegalStateException(ex); }
    }
    private static String documented(String candidate) {
        if (candidate == null)
            return null;
        var library = ReferenceAccess.instance().library;
        if (library.filename(candidate) != null)
            return candidate;
        if (candidate.startsWith("glad_") && library.filename(candidate.substring(5)) != null)
            return candidate.substring(5);
        return null;
    }
    /** Works with any host language using the platform PSI and the local reference index. */
    public static String editorSymbol(
        PsiFile file, CharSequence text, int caret, int selectionStart, int selectionEnd) {
        int start = Math.clamp(caret, 0, text.length()), end = start;
        if (selectionStart >= 0 && selectionEnd > selectionStart && selectionEnd <= text.length()) {
            start = selectionStart;
            end = selectionEnd;
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            if (end <= start || end - start > 256)
                return null;
            String selected = text.subSequence(start, end).toString();
            if (!selected.matches("[A-Za-z_]\\w*(?:(?:::|\\.|->)[A-Za-z_]\\w*)*"))
                return null;
            start = end;
            while (start > selectionStart && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        } else {
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        }
        if (end <= start || end - start > 150)
            return null;
        var literal = documented(text.subSequence(start, end).toString());
        if (literal != null || file == null || DumbService.isDumb(file.getProject()))
            return literal;
        var ref = file.findReferenceAt(start);
        if (ref == null)
            return null;
        var hit = ref.resolve();
        if (hit instanceof PsiNamedElement named) {
            String candidate = documented(named.getName());
            if (candidate != null)
                return candidate;
        }
        if (hit != null && hit.getNavigationElement() instanceof PsiNamedElement declaration)
            return documented(declaration.getName());
        return null;
    }
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        var parent = window.getDisposable();
        var browser = new Browser(parent);
        var alarm = new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, parent);
        var generation = new java.util.concurrent.atomic.AtomicLong();
        Runnable follow = () -> {
            long request = generation.incrementAndGet();
            alarm.cancelAllRequests();
            if (!window.isVisible() || !browser.follow.isSelected())
                return;
            alarm.addRequest(() -> {
                var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null || editor.isDisposed())
                    return;
                var doc = editor.getDocument();
                var selection = editor.getSelectionModel();
                int a = selection.getSelectionStart(), b = selection.getSelectionEnd(),
                    pos = editor.getCaretModel().getOffset();
                ReadAction
                    .nonBlocking(()
                                     -> editorSymbol(PsiDocumentManager.getInstance(project).getPsiFile(doc),
                                         doc.getImmutableCharSequence(), pos, a, b))
                    .withDocumentsCommitted(project)
                    .expireWith(parent)
                    .coalesceBy(browser)
                    .finishOnUiThread(ModalityState.any(),
                        name -> {
                            if (name != null && generation.get() == request && window.isVisible()
                                && browser.follow.isSelected())
                                browser.open(name);
                        })
                    .submit(AppExecutorUtil.getAppExecutorService());
            }, 100);
        };
        browser.follow.addActionListener(e -> {
            if (browser.follow.isSelected())
                browser.search.setText("");
            follow.run();
        });
        var events = EditorFactory.getInstance().getEventMulticaster();
        events.addCaretListener(new CaretListener() {
            public void caretPositionChanged(@NotNull CaretEvent e) {
                if (e.getEditor().getProject() == project)
                    follow.run();
            }
        }, parent);
        events.addSelectionListener(new SelectionListener() {
            public void selectionChanged(@NotNull SelectionEvent e) {
                if (e.getEditor().getProject() == project)
                    follow.run();
            }
        }, parent);
        project.getMessageBus().connect(parent).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                    follow.run();
                }
            });
        browser.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0)
                ApplicationManager.getApplication().invokeLater(follow, project.getDisposed());
        });
        window.getContentManager().addContent(ContentFactory.getInstance().createContent(browser, "", false));
        follow.run();
    }
    public static final class Open extends com.intellij.openapi.project.DumbAwareAction {
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
        private static String selected(AnActionEvent e) {
            var editor = e.getData(CommonDataKeys.EDITOR);
            if (editor == null)
                return null;
            var selection = editor.getSelectionModel();
            return editorSymbol(e.getData(CommonDataKeys.PSI_FILE),
                editor.getDocument().getImmutableCharSequence(), editor.getCaretModel().getOffset(),
                selection.getSelectionStart(), selection.getSelectionEnd());
        }
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null && selected(e) != null);
        }
        public void actionPerformed(@NotNull AnActionEvent e) {
            var p = e.getProject();
            if (p == null)
                return;
            String name = selected(e);
            if (name == null)
                return;
            show(p, false, browser -> {
                browser.search.setText("");
                browser.open(name);
            });
        }
    }
    public static final class Search extends com.intellij.openapi.project.DumbAwareAction {
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null);
        }
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null)
                show(e.getProject(), true, browser -> {
                    browser.follow.setSelected(false);
                    browser.showIndex(true);
                    browser.search.requestFocusInWindow();
                    browser.search.selectAll();
                });
        }
    }
    private static void show(Project project, boolean focus, java.util.function.Consumer<Browser> action) {
        var window = ToolWindowManager.getInstance(project).getToolWindow("OpenGL Docs");
        if (window == null)
            return;
        Runnable ready = () -> {
            for (var content : window.getContentManager().getContents())
                if (content.getComponent() instanceof Browser browser)
                    action.accept(browser);
        };
        if (focus)
            window.activate(ready);
        else
            window.show(ready);
    }
    interface WebViewer {
        JComponent component();
        void load(URL page);
        void setTheme(ReferenceAccess.Theme theme);
        String address();
    }
    private static WebViewer webViewer(Disposable parent) {
        try {
            return JcefReferenceViewer.create(parent);
        } catch (LinkageError | IllegalStateException ex) {
            Logger.getInstance(ReferenceDocs.class).info("Using the Swing OpenGL reference viewer", ex);
            return null;
        }
    }
    static final class Browser extends JPanel {
        final List<String> names = entries();
        final JBTextField search = new JBTextField();
        final JButton submit = new JButton("Search");
        final JCheckBox follow = new JBCheckBox("Follow editor", true);
        final DefaultListModel<String> results = new DefaultListModel<>();
        final JBList<String> list = new JBList<>(results);
        final JEditorPane html = new JEditorPane("text/html", "");
        final WebViewer web;
        final JPanel side = new JPanel(new BorderLayout());
        final JToggleButton index = new JToggleButton("Index");
        final JLabel status = new JBLabel();
        final JSplitPane split;
        final java.util.concurrent.ExecutorService loader;
        final ReferenceAccess access = ReferenceAccess.instance();
        final JButton offline = new JButton("Download all pages");
        int dividerWidth = 185;
        volatile long sequence;
        String current = "", loaded = "";
        boolean updatingList, loading;
        volatile boolean disposed;
        Browser(Disposable parent) {
            super(new BorderLayout(4, 4));
            loader = AppExecutorUtil.createBoundedApplicationPoolExecutor("OpenGL reference pages",
                AppExecutorUtil.getAppExecutorService(), 2, parent);
            html.setEditable(false);
            search.getEmptyText().setText("Search OpenGL or GLSL symbols");
            search.getAccessibleContext().setAccessibleName("Search OpenGL Docs");
            var bar = new JPanel(new BorderLayout(4, 0));
            bar.add(index, BorderLayout.WEST);
            bar.add(search, BorderLayout.CENTER);
            bar.add(submit, BorderLayout.EAST);
            add(bar, BorderLayout.NORTH);
            list.getEmptyText().setText("No matching reference pages");
            side.add(new JBScrollPane(list));
            web = webViewer(parent);
            split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            split.setRightComponent(web == null ? new JBScrollPane(html) : web.component());
            split.setResizeWeight(0);
            add(split, BorderLayout.CENTER);
            showIndex(false);
            index.addActionListener(e -> showIndex(index.isSelected()));
            index.setToolTipText("Show or hide the reference index");
            var standalone = new JButton("Open in Browser");
            standalone.addActionListener(e -> {
                var address = displayed();
                if (address != null) BrowserUtil.browse(address.toString());
            });
            var controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            controls.add(follow);
            controls.add(standalone);
            controls.add(offline);
            var footer = new JPanel(new BorderLayout());
            footer.add(controls, BorderLayout.NORTH);
            footer.add(status, BorderLayout.SOUTH);
            add(footer, BorderLayout.SOUTH);
            if (access.library.complete()) offline.setText("All pages saved");
            offline.addActionListener(e -> download());
            access.theme = ReferenceAccess.Theme.current();
            applyTheme();
            ApplicationManager.getApplication().getMessageBus().connect(parent).subscribe(
                com.intellij.openapi.editor.colors.EditorColorsManager.TOPIC, scheme ->
                    SwingUtilities.invokeLater(() -> {
                        if (disposed) return;
                        access.theme = ReferenceAccess.Theme.current();
                        applyTheme();
                    }));
            com.intellij.openapi.util.Disposer.register(parent, () -> { disposed = true; sequence++; });
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addListSelectionListener(e -> {
                if (!updatingList && !e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                    follow.setSelected(false);
                    open(list.getSelectedValue());
                }
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
            search.addActionListener(e -> search());
            submit.addActionListener(e -> search());
            html.addHyperlinkListener(e -> {
                if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED)
                    return;
                var href = e.getDescription();
                if (href == null)
                    return;
                if (href.startsWith("specs/") || href.equals("sources.txt")) {
                    URL url = e.getURL();
                    if (url != null && access.server().owns(URI.create(url.toExternalForm()))) BrowserUtil.browse(url);
                    return;
                }
                if (href.startsWith("#")) {
                    html.scrollToReference(href.substring(1));
                    return;
                }
                var path = href.split("#", 2);
                String file = path[0].substring(path[0].lastIndexOf('/') + 1);
                file = file.replaceFirst("\\.(xml|xhtml|html)$", "");
                open(file, path.length == 2 ? path[1] : null);
            });
            filter();
            open("OpenGL46");
        }
        void filter() {
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            if (!query.isEmpty()) {
                follow.setSelected(false);
                showIndex(true);
            }
            updatingList = true;
            try {
                results.clear();
                results.addAll(
                    names.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(query)).toList());
                list.setSelectedValue(current, true);
            } finally {
                updatingList = false;
            }
            submit.setEnabled(!query.isEmpty() && !results.isEmpty());
        }

        private void search() {
            if (!submit.isEnabled())
                return;
            String query = search.getText().strip();
            String match = results.firstElement();
            for (int i = 0; i < results.size(); i++)
                if (results.get(i).equalsIgnoreCase(query)) {
                    match = results.get(i);
                    break;
                }
            follow.setSelected(false);
            open(match);
        }

        void showIndex(boolean visible) {
            if (visible && split.getLeftComponent() == side) return;
            if (split.getLeftComponent() == side && !visible) dividerWidth = Math.max(120, split.getDividerLocation());
            index.setSelected(visible);
            split.setLeftComponent(visible ? side : null);
            split.setDividerSize(visible ? UIManager.getInt("SplitPane.dividerSize") : 0);
            if (visible) split.setDividerLocation(dividerWidth);
        }
        private URI displayed() {
            if (web != null && web.address() != null) {
                try {
                    var address = URI.create(web.address());
                    if (access.server().owns(address)) return address;
                } catch (IllegalArgumentException ignored) {}
            }
            return access.server().page(current);
        }
        private void download() {
            offline.setEnabled(false);
            var task = new com.intellij.openapi.progress.Task.Backgroundable(null, "Download OpenGL reference", true) {
                public void run(com.intellij.openapi.progress.ProgressIndicator progress) {
                    progress.setIndeterminate(false);
                    try {
                        access.library.downloadAll(percent -> {
                            progress.checkCanceled();
                            progress.setFraction(percent / 100.0);
                        });
                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                }
                public void onSuccess() {
                    if (disposed) return;
                    offline.setText("All pages saved");
                    status.setText("The complete reference is available offline.");
                }
                public void onThrowable(@NotNull Throwable error) {
                    if (!disposed) status.setText("Download interrupted. Reconnect and try again to finish saving the library.");
                    Logger.getInstance(ReferenceDocs.class).info("OpenGL reference download interrupted", error);
                }
                public void onFinished() { if (!disposed) offline.setEnabled(true); }
            };
            com.intellij.openapi.progress.ProgressManager.getInstance().run(task);
        }
        private void applyTheme() {
            var theme = access.theme;
            if (web != null) {
                web.setTheme(theme);
                return;
            }
            html.setBackground(Color.decode(theme.background()));
            html.setForeground(Color.decode(theme.foreground()));
            var css = ((HTMLDocument) html.getDocument()).getStyleSheet();
            css.addRule("body { background-color: " + theme.background() + "; color: " + theme.foreground() + "; }");
            css.addRule("a { color: " + theme.link() + "; }");
            for (String selector : List.of("pre","div.funcsynopsis","div.funcsynopsis td","div.refsect1 td","div.refsect2 table"))
                css.addRule(selector + " { background-color: " + theme.panel() + "; color: " + theme.foreground() + "; }");
            html.repaint();
        }
        boolean open(String name) { return open(name, null); }
        boolean open(String name, String anchor) {
            URL url = page(name);
            if (url == null) return false;
            if (current.equals(name) && anchor == null && (loading ||
                name.equals(loaded) && url.toExternalForm().equals(String.valueOf(displayed())))) return true;
            current = name;
            loaded = "";
            loading = true;
            long request = ++sequence;
            status.setText("Loading " + name + "…");
            loader.execute(() -> {
                if (disposed || request != sequence) return;
                try {
                    var file = access.library.get(access.library.filename(name));
                    HTMLDocument doc = null;
                    if (web == null) {
                        var kit = new javax.swing.text.html.HTMLEditorKit();
                        doc = (HTMLDocument) kit.createDefaultDocument();
                        doc.setBase(url);
                        try (var reader = Files.newBufferedReader(file)) { kit.read(reader, doc, 0); }
                    }
                    var prepared = doc;
                    SwingUtilities.invokeLater(() -> {
                        if (disposed || request != sequence) return;
                        loading = false;
                        if (web != null) {
                            try { web.load(anchor == null ? url : URI.create(url.toExternalForm() + "#" + anchor).toURL()); }
                            catch (java.net.MalformedURLException ex) { throw new IllegalStateException(ex); }
                        } else {
                            html.setDocument(prepared);
                            html.setCaretPosition(0);
                            applyTheme();
                            if (anchor != null) html.scrollToReference(anchor);
                        }
                        loaded = name;
                        status.setText("");
                    });
                } catch (Exception failure) {
                    Logger.getInstance(ReferenceDocs.class).info("Could not load OpenGL reference " + name, failure);
                    SwingUtilities.invokeLater(() -> {
                        if (disposed || request != sequence) return;
                        loading = false;
                        status.setText(name + " is unavailable offline. Reconnect and press Search to retry.");
                    });
                }
            });
            int index = results.indexOf(name);
            if (index >= 0 && list.getSelectedIndex() != index) {
                updatingList = true;
                try {
                    list.setSelectedIndex(index);
                    list.ensureIndexIsVisible(index);
                } finally {
                    updatingList = false;
                }
            }
            return true;
        }
    }
}
