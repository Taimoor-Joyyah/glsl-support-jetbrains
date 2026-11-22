package com.glslsupport.ide;
import com.glslsupport.lang.ShaderConfig;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.*;
import com.intellij.psi.*;
import com.intellij.ui.content.ContentFactory;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public final class Variants implements ToolWindowFactory {
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        var panel = new Panel(project);
        window.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "", false));
        project.getMessageBus()
            .connect(window.getDisposable())
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                public void selectionChanged(@NotNull FileEditorManagerEvent e) {
                    panel.refresh();
                }
            });
        panel.refresh();
    }
    static final class Panel extends JPanel {
        final Project p;
        final JLabel path = new JLabel();
        final JComboBox<String> variant = new JComboBox<>(), stage = new JComboBox<>(), target = new JComboBox<>(),
                                profile = new JComboBox<>(new String[] {"", "core", "compatibility", "es"});
        final JSpinner version = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
        final SettingsUI.Grid macros = new SettingsUI.Grid("Definition", "Value");
        boolean refreshing;
        PsiFile file;
        Panel(Project p) {
            super(new BorderLayout(4, 6));
            this.p = p;
            stage.addItem("Inherit");
            for (var s : ShaderConfig.Stage.values()) stage.addItem(s.name());
            target.addItem("Inherit");
            for (var t : SettingsUI.TARGETS) target.addItem(t);
            var top = SettingsUI.form();
            top.add(path);
            var bar = SettingsUI.row("Active variant", variant);
            for (String label : java.util.List.of("Add", "Remove", "Apply")) {
                var b = new JButton(label);
                bar.add(b);
                b.addActionListener(e -> {
                    if (file == null)
                        return;
                    var cfg = Settings.of(p);
                    var f = options();
                    if (label.equals("Add")) {
                        String n = Messages.showInputDialog(p, "Variant name", "Add Shader Variant", null);
                        if (n != null && !n.isBlank() && f.variants.stream().noneMatch(v -> v.name.equals(n))) {
                            var v = new Settings.Variant();
                            v.id = UUID.randomUUID().toString();
                            v.name = n;
                            f.variants.add(v);
                            f.active = v.id;
                        }
                    } else if (label.equals("Remove") && f.variants.size() > 1) {
                        f.variants.removeIf(v -> v.id.equals(f.active));
                        f.active = f.variants.getFirst().id;
                    } else if (label.equals("Apply"))
                        save();
                    cfg.changed();
                    DaemonCodeAnalyzer.getInstance(p).restart(file, "GLSL variant changed");
                    refresh();
                });
            }
            top.add(bar);
            top.add(SettingsUI.row("Stage", stage));
            top.add(SettingsUI.row("Compiler target", target));
            top.add(SettingsUI.row("Version override (0 inherits)", version));
            top.add(SettingsUI.row("Profile", profile));
            add(top, BorderLayout.NORTH);
            add(macros, BorderLayout.CENTER);
            variant.addActionListener(e -> {
                if (refreshing || file == null)
                    return;
                var f = options();
                int i = variant.getSelectedIndex();
                if (i >= 0 && i < f.variants.size()) {
                    f.active = f.variants.get(i).id;
                    Settings.of(p).changed();
                    DaemonCodeAnalyzer.getInstance(p).restart(file, "GLSL variant changed");
                    refresh();
                }
            });
        }
        Settings.FileOptions options() {
            var f = Settings.of(p).getState().files.computeIfAbsent(
                Settings.relative(p, ProjectAccess.key(file)), k -> new Settings.FileOptions());
            if (f.variants.isEmpty())
                f.variants.add(new Settings.Variant());
            return f;
        }
        void save() {
            macros.commit();
            int i = variant.getSelectedIndex();
            if (i < 0)
                return;
            var v = options().variants.get(i);
            v.stage = stage.getSelectedIndex() == 0 ? "" : stage.getSelectedItem().toString();
            v.target =
                target.getSelectedIndex() == 0 ? -1 : SettingsUI.target(target.getSelectedItem().toString());
            v.profile = profile.getSelectedItem().toString();
            v.version = (Integer) version.getValue();
            v.macros.clear();
            for (var row : macros.rows()) v.macros.put(row[0], row[1]);
        }
        void refresh() {
            refreshing = true;
            try {
                var selected = FileEditorManager.getInstance(p).getSelectedFiles();
                file = selected.length == 0 ? null : PsiManager.getInstance(p).findFile(selected[0]);
                variant.removeAllItems();
                if (!(file instanceof GlslLanguage.File)) {
                    file = null;
                    path.setText("Open a GLSL shader to edit its variants");
                    macros.rows(java.util.List.of());
                    return;
                }
                path.setText(Settings.relative(p, ProjectAccess.key(file)));
                var f = options();
                int pos = 0;
                for (int i = 0; i < f.variants.size(); i++) {
                    var v = f.variants.get(i);
                    variant.addItem(v.name);
                    if (v.id.equals(f.active))
                        pos = i;
                }
                variant.setSelectedIndex(pos);
                var v = f.variants.get(pos);
                stage.setSelectedItem(v.stage.isEmpty() ? "Inherit" : v.stage);
                target.setSelectedItem(v.target < 0 ? "Inherit" : SettingsUI.target(v.target));
                profile.setSelectedItem(v.profile);
                version.setValue(v.version);
                macros.rows(
                    v.macros.entrySet().stream().map(e -> new String[] {e.getKey(), e.getValue()}).toList());
            } finally {
                refreshing = false;
            }
        }
    }
}
