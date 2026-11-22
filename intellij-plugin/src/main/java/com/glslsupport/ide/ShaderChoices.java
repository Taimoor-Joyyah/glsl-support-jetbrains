package com.glslsupport.ide;

import com.glslsupport.lang.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.search.*;
import com.intellij.ui.TextFieldWithAutoCompletion;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.awt.Component;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.table.TableCellEditor;

final class ShaderChoices {
    final Project project;
    ShaderChoices(Project project) {
        this.project = project;
    }
    List<String> files(String stage) {
        var files = FileTypeIndex.getFiles(GlslLanguage.Type.INSTANCE, GlobalSearchScope.projectScope(project))
                        .stream()
                        .map(v -> Settings.relative(project, v.getPath()))
                        .sorted()
                        .toList();
        var required = Settings.stage(stage);
        return files.stream()
            .filter(f
                -> !f.endsWith(".glslh")
                    && ShaderConfig.Stage.fromPath(f).map(s -> required == null || s == required).orElse(true))
            .toList();
    }
    VirtualFile resolve(String path) {
        if (path.isBlank())
            return null;
        var full = Workspace.resolvePath(Objects.requireNonNullElse(project.getBasePath(), ""), path);
        return full == null ? null : LocalFileSystem.getInstance().findFileByPath(full);
    }
    String browse(Component owner, String initial, FileChooserDescriptor descriptor) {
        var start = resolve(initial);
        if (start == null && project.getBasePath() != null)
            start = LocalFileSystem.getInstance().findFileByPath(project.getBasePath());
        var file = FileChooser.chooseFile(descriptor, owner, project, start);
        return file == null ? null : Settings.relative(project, file.getPath());
    }
    <T> void complete(
        JTable table, int column, IntFunction<T> context, Function<T, Collection<String>> candidates) {
        class Editor extends AbstractCellEditor implements TableCellEditor {
            TextFieldWithAutoCompletion<String> field;
            public Component getTableCellEditorComponent(
                JTable t, Object value, boolean selected, int row, int col) {
                T query = context.apply(row);
                field = TextFieldWithAutoCompletion.create(project, List.of(), true, Objects.toString(value, ""));
                var current = field;
                ReadAction.nonBlocking(() -> candidates.apply(query))
                    .inSmartMode(project)
                    .withDocumentsCommitted(project)
                    .expireWith(project)
                    .coalesceBy(this)
                    .finishOnUiThread(ModalityState.current(),
                        items -> {
                            if (field == current)
                                current.setVariants(items);
                        })
                    .submit(AppExecutorUtil.getAppExecutorService());
                field.setOneLineMode(true);
                field.setToolTipText("Type to complete or press Ctrl+Space");
                return field;
            }
            public Object getCellEditorValue() {
                return field.getText().strip();
            }
        }
        table.getColumnModel().getColumn(column).setCellEditor(new Editor());
    }
    List<String> variants(String path) {
        var file = Settings.of(project).getState().files.get(path);
        return file == null ? List.of("Default") : file.variants.stream().map(v -> v.name).toList();
    }
    String variantValue(String path, String value, boolean id) {
        if (value.isBlank())
            return "";
        var file = Settings.of(project).getState().files.get(path);
        if (file == null)
            return value;
        for (var v : file.variants)
            if (v.id.equals(value) || v.name.equals(value))
                return id ? v.id : v.name;
        return value;
    }
    int suggestedTarget(String path) {
        var file = resolve(path);
        var settings = Settings.of(project);
        int inherited = settings.getState().target;
        if (file != null && !file.isDirectory()) {
            var psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
            if (psi != null) {
                var base = settings.effective(psi, null);
                inherited = base.vulkan();
                String text = psi.getText();
                if (ES_OR_COMPAT.matcher(text).find())
                    return 0;
                if (inherited == 0 && (VULKAN_LAYOUT.matcher(text).find() || switch (base.stage()) {
                        case RAYGEN, ANY_HIT, CLOSEST_HIT, INTERSECTION, MISS, CALLABLE -> true;
                        default -> false;
                    }))
                    return 12;
            }
        }
        return inherited;
    }
    private static final Pattern ES_OR_COMPAT =
        Pattern.compile("(?m)^\\s*#\\s*version\\s+(?:100\\b|\\d+\\s+(?:es|compatibility)\\b)");
    private static final Pattern VULKAN_LAYOUT =
        Pattern.compile("\\blayout\\s*\\([^)]*\\b(?:set\\s*=|push_constant\\b|input_attachment_index\\s*=)");
    private static final Pattern DIRECTIVE =
        Pattern.compile("#\\s*(define|ifdef|ifndef|if|elif)\\b\\s*(.*)", Pattern.DOTALL);
    private static final Pattern WORD = Pattern.compile("[A-Za-z_]\\w*");
    Collection<String> definitions(Collection<String> assigned) {
        var names = new TreeSet<String>();
        var settings = Settings.of(project);
        names.addAll(settings.getState().stageMacros.keySet());
        var visited = new HashSet<String>();
        for (String path : assigned) {
            var file = resolve(path);
            if (file == null)
                continue;
            var psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
            if (psi == null)
                continue;
            for (var v : settings.variants(psi)) names.addAll(v.macros.keySet());
            ProjectAccess.graph(psi).files.forEach((key, model) -> {
                if (!visited.add(key))
                    return;
                for (var token : model.code)
                    if (token.kind() == Lexer.Kind.DIRECTIVE) {
                        var m = DIRECTIVE.matcher(token.text());
                        if (!m.matches())
                            continue;
                        if (m.group(1).equals("if") || m.group(1).equals("elif")) {
                            for (var t : Lexer.tokens(m.group(2)))
                                if (t.kind() == Lexer.Kind.WORD && !t.text().equals("defined"))
                                    names.add(t.text());
                        } else {
                            var id = WORD.matcher(m.group(2));
                            if (id.lookingAt())
                                names.add(id.group());
                        }
                    }
            });
        }
        return names;
    }
}
