package com.glslsupport.tests;

import static com.glslsupport.tests.IdeSmoke.check;

import com.glslsupport.bridge.NativeCompiler;
import com.glslsupport.ide.*;
import com.glslsupport.lang.ShaderConfig;
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl;
import com.intellij.lang.annotation.AnnotationSession;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.util.xmlb.XmlSerializer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

final class EditorUpgrade {
    static void verify(Project p, Path root) throws Exception {
        highlighting(p);
        check(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("GlslSupport.OpenReference")
                != null,
            "Open OpenGL Reference action");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var file = PsiManager.getInstance(p).findFile(Objects.requireNonNull(
                LocalFileSystem.getInstance().findFileByNioFile(root.resolve("format.frag"))));
            var holder = new com.intellij.codeInspection.ProblemsHolder(
                com.intellij.codeInspection.InspectionManager.getInstance(p), file, false);
            new Inspections().buildVisitor(holder, false).visitFile(file);
            var problem = holder.getResults()
                              .stream()
                              .filter(d -> d.getDescriptionTemplate().contains("#version"))
                              .findFirst()
                              .orElseThrow();
            WriteCommandAction.runWriteCommandAction(p, () -> new Intentions.Version().applyFix(p, problem));
            check(file.getText().startsWith("#version 460 core"), "missing version inspection quick fix");
        });
        configuration(p, root);
        var fs = LocalFileSystem.getInstance();
        for (String path : List.of("calls.cpp", "calls.py")) {
            var vf = Objects.requireNonNull(fs.refreshAndFindFileByNioFile(root.resolve(path)));
            ReadAction.run(() -> {
                var psi = PsiManager.getInstance(p).findFile(vf);
                String text = psi.getText();
                int a = text.indexOf(path.endsWith("cpp") ? "glad_glDrawArrays" : "GL.glBindTexture");
                String label = path.endsWith("cpp") ? "glad_glDrawArrays" : "GL.glBindTexture",
                       expected = path.endsWith("cpp") ? "glDrawArrays" : "glBindTexture";
                check(Objects.equals(
                          ReferenceDocs.editorSymbol(psi, text, text.length(), a, a + label.length()), expected),
                    "selected API in " + path);
                check(
                    Objects.equals(ReferenceDocs.editorSymbol(psi, text, a + label.length() - 2, 0, 0), expected),
                    "caret API in " + path);
                check(ReferenceDocs.editorSymbol(psi, text, text.length(), 0, text.length()) == null,
                    "unrelated selection in " + path);
            });
        }
        var v = Objects.requireNonNull(fs.refreshAndFindFileByNioFile(root.resolve("swizzle.vert")));
        var file = ReadAction.compute(() -> PsiManager.getInstance(p).findFile(v));
        var edt = ApplicationManager.getApplication();
        var manager = PsiDocumentManager.getInstance(p);
        var editor = new AtomicReference<Editor>();
        edt.invokeAndWait(()
                              -> editor.set(FileEditorManager.getInstance(p).openTextEditor(
                                  new OpenFileDescriptor(p, v), false)));
        check(editor.get() != null, "open shader in text editor");
        var doc = editor.get().getDocument();
        var annotator = new Diagnostics();
        var bad = ReadAction.compute(() -> annotator.collectInformation(file, editor.get(), true));
        var report = annotator.doAnnotate(bad);
        check(report != null
                && report.issues().stream().anyMatch(i
                    -> i.message().contains("swizzle") && i.start() == doc.getText().indexOf("v.z") + 2
                        && i.end() == i.start() + 1),
            "native swizzle range " + report);
        edt.invokeAndWait(() -> WriteCommandAction.runWriteCommandAction(p, () -> {
            int at = doc.getText().indexOf("v.z") + 2;
            doc.replaceString(at, at + 1, "x");
            manager.commitDocument(doc);
        }));
        check(ReadAction.compute(() -> annotator.collectInformation(file, editor.get(), true)) == null,
            "live edit debounce");
        long limit = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        Diagnostics.Input next = null;
        while (next == null && System.nanoTime() < limit) {
            Thread.sleep(100);
            next = ReadAction.compute(() -> annotator.collectInformation(file, editor.get(), true));
        }
        check(next != null && annotator.doAnnotate(next).issues().isEmpty(),
            "corrected unsaved swizzle revalidated");
        edt.invokeAndWait(() -> FileEditorManager.getInstance(p).closeFile(v));
        var headerV = Objects.requireNonNull(fs.refreshAndFindFileByNioFile(root.resolve("swizzle.glslh")));
        var ownerV = Objects.requireNonNull(fs.refreshAndFindFileByNioFile(root.resolve("header-owner.frag")));
        ReadAction.run(() -> {
            var owner = PsiManager.getInstance(p).findFile(ownerV);
            var header = PsiManager.getInstance(p).findFile(headerV);
            check(annotator.collectInformation(owner) != null, "register header translation unit");
            var headerReport = annotator.doAnnotate(annotator.collectInformation(header));
            check(headerReport != null
                    && headerReport.issues().stream().anyMatch(
                        i -> i.message().contains("swizzle") && i.start() == header.getText().indexOf(".z") + 1),
                "native error highlighted in included header " + headerReport);
        });
        var colorFile = Objects.requireNonNull(fs.findFileByNioFile(root.resolve("tools.vert")));
        edt.invokeAndWait(() -> {
            var psi = PsiManager.getInstance(p).findFile(colorFile);
            var document = Objects.requireNonNull(manager.getDocument(psi));
            var picker = new Calls.Colors();
            String initial = document.getText();
            int at = initial.indexOf("vec3(0.1");
            int close = initial.indexOf(')', at);
            var anchor = psi.findElementAt(at).getParent();
            check(picker.getColorFrom(anchor) != null, "initial picker value");
            for (int i = 0; i < 24; i++) {
                var chosen = new Color(i * 9, 255 - i * 10, i * 11);
                picker.setColorTo(anchor, chosen);
                if (i % 3 == 0)
                    manager.commitDocument(document);
                String live = document.getText();
                check(
                    live.startsWith(initial.substring(0, at) + "vec3(") && live.endsWith(initial.substring(close)),
                    "color drag preserved surrounding source");
                check(chosen.equals(picker.getColorFrom(anchor)), "color drag round trip " + i);
            }
        });
    }
    private static void highlighting(Project p) {
        String code = "#include <folder/a.glslh>\n/** @param v value */\nlayout(location=0) out vec4 frag;\n"
            + "struct Light{vec3 tint;};\nvoid main(){vec3 v=normalize(vec3(1));frag.rgb=v.xyz;\n#if 0\nvec4 "
            + "off=vec4(1);\n#endif\nreturn; frag=vec4(0);}";
        var colors = new Appearance.Colors();
        check(
            colors.getAttributeDescriptors().length >= 50 && colors.getIcon() != null, "shader palette and icon");
        var highlighter = colors.getHighlighter();
        var lex = highlighter.getHighlightingLexer();
        lex.start(code);
        var keyAt = new HashMap<Integer, String>();
        while (lex.getTokenType() != null) {
            var keys = highlighter.getTokenHighlights(lex.getTokenType());
            if (keys.length > 0)
                keyAt.put(lex.getTokenStart(), keys[0].getExternalName());
            int from = lex.getTokenStart(), to = lex.getTokenEnd();
            var restart = highlighter.getHighlightingLexer();
            restart.start(code, from, code.length(), lex.getState());
            check(restart.getTokenType() == lex.getTokenType() && restart.getTokenEnd() == to,
                "incremental color lexer at " + from);
            lex.advance();
        }
        check("GLSLSUPPORT_HEADER".equals(keyAt.get(code.indexOf('<')))
                && "GLSLSUPPORT_DOC_TAG".equals(keyAt.get(code.indexOf("@param")))
                && "GLSLSUPPORT_BUILTIN_FUNC".equals(keyAt.get(code.indexOf("normalize")))
                && "GLSLSUPPORT_TYPE".equals(keyAt.get(code.indexOf("vec4")))
                && "GLSLSUPPORT_PERIOD".equals(keyAt.get(code.indexOf(".rgb"))),
            "token color roles " + keyAt);
        ReadAction.run(() -> {
            var psi = PsiFileFactory.getInstance(p).createFileFromText("colors.frag", GlslLanguage.INSTANCE, code);
            var holder = new AnnotationHolderImpl(new AnnotationSession(psi), false);
            holder.runAnnotatorWithContext(psi, new Appearance.Semantic());
            for (var entry :
                Map.of("location=", "LAYOUT_ITEM", "Light{", "STRUCT", "rgb=", "SWIZZLE", "xyz;", "SWIZZLE")
                    .entrySet())
                check(
                    holder.stream().anyMatch(a
                        -> a.getStartOffset() == code.indexOf(entry.getKey()) && a.getTextAttributes() != null
                            && a.getTextAttributes().getExternalName().equals("GLSLSUPPORT_" + entry.getValue())),
                    "semantic color " + entry);
            var scheme = EditorColorsManager.getInstance().getGlobalScheme();
            for (int at : List.of(code.indexOf("vec4 off"), code.lastIndexOf("vec4(0)"))) {
                var expected = Appearance
                                   .dim(scheme.getAttributes(TextAttributesKey.find("GLSLSUPPORT_TYPE")),
                                       scheme.getDefaultForeground(), scheme.getDefaultBackground())
                                   .getForegroundColor();
                check(holder.stream().anyMatch(a
                          -> a.getStartOffset() == at && a.getEnforcedTextAttributes() != null
                              && expected.equals(a.getEnforcedTextAttributes().getForegroundColor())),
                    "dimmed original type at " + at);
            }
        });
    }
    private static void configuration(Project p, Path root) throws Exception {
        var component = JDOMUtil.load(root.resolve("settings-example.xml")).getChild("component");
        var d = XmlSerializer.deserialize(component, Settings.Data.class);
        var serialized = XmlSerializer.serialize(d);
        var round = XmlSerializer.deserialize(serialized, Settings.Data.class);
        check(round.target == 0 && round.includes.size() == 2 && round.targets.get("shaders/vulkan") == 13
                && round.files.get("tools.vert").variants.getFirst().macros.get("DAYLIGHT").equals("1")
                && round.pipelineDefinitions.size() == 2
                && round.pipelineDefinitions.getFirst().definitions.get("LIGHT_COUNT").equals("4")
                && round.pipelineDefinitions.getFirst().stages.getFirst().variant.equals("day"),
            "documented XML round trip " + JDOMUtil.writeElement(serialized));
        var settings = Settings.of(p);
        var pipeline = round.pipelineDefinitions.getFirst();
        settings.getState().pipelineDefinitions.add(pipeline);
        var compiler = p.getService(CompilerSupport.class);
        var request = ReadAction.compute(() -> CompilerSupport.pipeline(p, "Forward"));
        check(request.vulkan() == 13 && request.units().size() == 2
                && request.units().stream().allMatch(u -> "4".equals(u.config().defines().get("LIGHT_COUNT"))),
            "pipeline configuration in compiler snapshot");
        var result = compiler.analyze(request);
        check(result.success(), "configured pipeline link " + result.log());
        pipeline.definitions.put("BROKEN", "1");
        check(!compiler.analyze(ReadAction.compute(() -> CompilerSupport.pipeline(p, "Forward"))).success(),
            "pipeline-level definitions reach glslang");
        pipeline.definitions.remove("BROKEN");
        check(new Settings.Variant().profile.equals("core"), "new variant profile");
        var completions = new LinkedHashMap<com.intellij.ui.TextFieldWithAutoCompletion<?>, String>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            var ui = new SettingsUI(p);
            var tabs = (JTabbedPane) ui.createComponent();
            var panel = tabs.getComponentAt(tabs.indexOfTab("Pipelines"));
            var lists = children(panel, JList.class);
            var list = lists.stream().filter(l -> l.getModel().getSize() == 2).findFirst().orElseThrow();
            list.setSelectedValue("Forward", true);
            var tables = children(panel, JTable.class);
            var stages = tables.stream().filter(t -> t.getColumnCount() == 3).findFirst().orElseThrow();
            check(stages.getRowCount() == 5 && stages.getValueAt(0, 1).equals("tools.vert"),
                "editable graphics stage slots");
            check(stages.getCellEditor(0, 1) != null
                    && children(panel, JButton.class).stream().anyMatch(b -> "Browse…".equals(b.getText())),
                "shader completion and browser controls");
            var defs = tables.stream().filter(t -> t.getColumnCount() == 2).findFirst().orElseThrow();
            completions.put(completionField(stages, 0, 1), "tools.vert");
            completions.put(completionField(stages, 0, 2), "Night");
            completions.put(completionField(defs, 0, 0), "BROKEN");
            defs.setValueAt("8", 0, 1);
            check(ui.isModified(), "pipeline modification visible");
            try {
                ui.apply();
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
            check("8".equals(settings.pipelines().get("Forward").definitions.get("LIGHT_COUNT"))
                    && !ui.isModified(),
                "settings save");
            defs.setValueAt("16", 0, 1);
            ui.reset();
            check("8".equals(settings.pipelines().get("Forward").definitions.get("LIGHT_COUNT"))
                    && !ui.isModified(),
                "cancel/reset leaves saved data");
        });
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        var missing = new ArrayList<String>();
        do {
            Thread.sleep(80);
            missing.clear();
            ApplicationManager.getApplication().invokeAndWait(() -> completions.forEach((field, expected) -> {
                var psi = PsiDocumentManager.getInstance(p).getPsiFile(field.getDocument());
                var provider = com.intellij.util.textCompletion.TextCompletionUtil.getProvider(psi);
                if (!(provider instanceof com.intellij.ui.TextFieldWithAutoCompletionListProvider<?> list)
                    || !list.getItems("", true, null).contains(expected))
                    missing.add(expected);
            }));
        } while (!missing.isEmpty() && System.nanoTime() < until);
        check(missing.isEmpty(), "live project file, variant and macro suggestions missing " + missing);
    }
    private static com.intellij.ui.TextFieldWithAutoCompletion<?> completionField(JTable table, int row, int col) {
        return (com.intellij.ui.TextFieldWithAutoCompletion<?>) table.getCellEditor(row, col)
            .getTableCellEditorComponent(table, table.getValueAt(row, col), true, row, col);
    }
    private static <T extends Component> List<T> children(Component root, Class<T> type) {
        var result = new ArrayList<T>();
        if (type.isInstance(root))
            result.add(type.cast(root));
        if (root instanceof Container c)
            for (var child : c.getComponents()) result.addAll(children(child, type));
        return result;
    }
}
