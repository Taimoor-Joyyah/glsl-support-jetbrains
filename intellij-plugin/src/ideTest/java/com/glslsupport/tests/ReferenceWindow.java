package com.glslsupport.tests;

import static com.glslsupport.tests.IdeSmoke.await;
import static com.glslsupport.tests.IdeSmoke.check;
import static com.glslsupport.tests.IdeSmoke.onEdt;

import com.glslsupport.ide.ReferenceDocs;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.text.html.HTMLDocument;

/** Exercise the installed tool window, its controls and the document actually rendered. */
final class ReferenceWindow {
    private record Controls(JComponent panel, JTextField query, JButton search, JCheckBox follow, JList<?> list) {}

    static void verify(Project project, Path root, boolean graphical) throws Exception {
        var manager = ToolWindowManager.getInstance(project);
        var app = ApplicationManager.getApplication();
        var searchAction = ActionManager.getInstance().getAction("GlslSupport.SearchReference");
        check(searchAction != null, "manual search is available in Find Action");
        var window = onEdt(() -> {
            var docs = manager.getToolWindow("OpenGL Docs");
            if (graphical) {
                check(docs != null, "OpenGL Docs registered in the IDE");
                var context = SimpleDataContext.getProjectContext(project);
                searchAction.actionPerformed(
                    AnActionEvent.createEvent(searchAction, context, null, "test", ActionUiKind.NONE, null));
            } else {
                if (docs == null)
                    docs = manager.registerToolWindow(RegisterToolWindowTask.notClosable("OpenGL Docs"));
                if (docs.getContentManager().getContentCount() == 0)
                    new ReferenceDocs().createToolWindowContent(project, docs);
            }
            return docs;
        });
        await(() -> onEdt(() -> window.getContentManager().getContentCount() > 0), "tool window content");
        Controls ui = onEdt(() -> {
            var panel = window.getContentManager().getContent(0).getComponent();
            return new Controls(panel, find(panel, JTextField.class, x -> true),
                find(panel, JButton.class, b -> "Search".equals(b.getText())),
                find(panel, JCheckBox.class, b -> "Follow editor".equals(b.getText())),
                find(panel, JList.class, x -> true));
        });
        app.invokeAndWait(() -> {
            ui.query.setText("  GLGETFLOATV  ");
            check(ui.search.isEnabled(), "exact reference has a match");
            ui.search.doClick(0);
            check("glGetFloatv".equals(ui.list.getSelectedValue()) && !ui.follow.isSelected(), "manual alias");
        });
        assertPage(ui, "glGet", "glGetFloatv", graphical);
        app.invokeAndWait(() -> {
            ui.query.setText("bindtexture");
            ui.query.postActionEvent();
            check("glBindTexture".equals(ui.list.getSelectedValue()), "Enter opens partial match");
        });
        assertPage(ui, "glBindTexture", "bind a named texture", graphical);
        app.invokeAndWait(() -> {
            ui.query.setText("not_a_documented_symbol");
            ui.query.postActionEvent();
            check(ui.list.getModel().getSize() == 0 && !ui.search.isEnabled(), "unmatched input");
            ui.query.setText("uniform2");
            ui.list.setSelectedValue("glUniform2f", true);
            check("glUniform2f".equals(ui.list.getSelectedValue()), "select among manual results");
        });
        assertPage(ui, "glUniform", "glUniform2f", graphical);
        if (graphical) {
            var vf = Objects.requireNonNull(
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("calls.cpp")));
            app.invokeAndWait(() -> {
                var editor = FileEditorManager.getInstance(project).openTextEditor(
                    new OpenFileDescriptor(project, vf), true);
                check(editor != null, "C++ editor opened");
                editor.getSelectionModel().setSelection(0, "glad_glDrawArrays".length());
                check(!ui.follow.isSelected(), "manual page stays pinned when the editor changes");
            });
            assertPage(ui, "glUniform", "glUniform2f", true);
            app.invokeAndWait(() -> ui.follow.doClick(0));
            assertPage(ui, "glDrawArrays", "render primitives", true);
            var py = Objects.requireNonNull(
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("calls.py")));
            app.invokeAndWait(() -> {
                var editor = FileEditorManager.getInstance(project).openTextEditor(
                    new OpenFileDescriptor(project, py), true);
                editor.getSelectionModel().setSelection(0, "GL.glBindTexture".length());
            });
            assertPage(ui, "glBindTexture", "bind a named texture", true);
        }
    }

    private static void assertPage(Controls ui, String file, String text, boolean graphical) throws Exception {
        if (graphical) {
            RenderedReference.assertPage(ui.panel, file, text);
            return;
        }
        check(onEdt(() -> {
            var html = find(ui.panel, JEditorPane.class, p -> !p.isEditable());
            var doc = (HTMLDocument) html.getDocument();
            return doc.getBase() != null && doc.getBase().getPath().endsWith("/" + file + ".xhtml")
                && html.getText().contains(text);
        }),
            "Swing displayed local " + file + " with " + text);
    }

    static <T extends Component> T find(Component root, Class<T> type, Predicate<T> match) {
        if (type.isInstance(root) && match.test(type.cast(root)))
            return type.cast(root);
        if (root instanceof Container container)
            for (var child : container.getComponents()) {
                var found = find(child, type, match);
                if (found != null)
                    return found;
            }
        return null;
    }
}
