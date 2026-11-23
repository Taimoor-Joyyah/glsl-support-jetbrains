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

public final class ReferenceDocs implements ToolWindowFactory, DumbAware {
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
    private static String documented(String candidate) {
        if (candidate == null)
            return null;
        if (INDEX.contains(candidate))
            return candidate;
        if (candidate.startsWith("glad_") && INDEX.contains(candidate.substring(5)))
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
        String current = "";
        boolean updatingList;
        Browser(Disposable parent) {
            super(new BorderLayout(4, 4));
            html.setEditable(false);
            search.getEmptyText().setText("Search OpenGL or GLSL symbols");
            search.getAccessibleContext().setAccessibleName("Search OpenGL Docs");
            var bar = new JPanel(new BorderLayout(4, 0));
            bar.add(search, BorderLayout.CENTER);
            bar.add(submit, BorderLayout.EAST);
            add(bar, BorderLayout.NORTH);
            list.getEmptyText().setText("No matching reference pages");
            var side = new JPanel(new BorderLayout(4, 4));
            side.add(follow, BorderLayout.NORTH);
            side.add(new JBScrollPane(list));
            web = webViewer(parent);
            JComponent viewer = web == null ? new JBScrollPane(html) : web.component();
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
            if (!query.isEmpty())
                follow.setSelected(false);
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

        private Path displayedFile() {
            if (web == null)
                return null;
            String address = web.address();
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
            Path displayed = current.equals(name) ? displayedFile() : null;
            if (current.equals(name)
                && (displayed == null
                    || ROOT.resolve(ALIASES.getOrDefault(name, name) + ".xhtml").equals(displayed)))
                return true;
            if (web != null) {
                web.load(url);
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
