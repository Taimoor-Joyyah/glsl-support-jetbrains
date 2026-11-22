package com.glslsupport.ide;

import com.glslsupport.lang.ShaderConfig;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.options.*;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public final class SettingsUI implements Configurable {
    static final String[] TARGETS = {
        "OpenGL", "Vulkan 1.0", "Vulkan 1.1", "Vulkan 1.2", "Vulkan 1.3", "Vulkan 1.4"};
    static int target(String text) {
        for (int i = 1; i < TARGETS.length; i++)
            if (TARGETS[i].equals(text))
                return 9 + i;
        return 0;
    }
    static String target(int value) {
        return value >= 10 && value <= 14 ? TARGETS[value - 9] : TARGETS[0];
    }
    static final class Grid extends JPanel {
        final DefaultTableModel data;
        final JTable table;
        Grid(String... columns) {
            super(new BorderLayout(0, 4));
            data = new DefaultTableModel(columns, 0);
            table = new com.intellij.ui.table.JBTable(data);
            table.setFillsViewportHeight(true);
            add(new JBScrollPane(table));
            var buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 0));
            for (String label : List.of("Add", "Remove", "Up", "Down")) {
                var b = new JButton(label);
                b.addActionListener(e -> {
                    commit();
                    int row = table.getSelectedRow();
                    switch (label) {
                        case "Add" -> {
                            data.addRow(new String[columns.length]);
                            table.setRowSelectionInterval(data.getRowCount() - 1, data.getRowCount() - 1);
                        }
                        case "Remove" -> {
                            if (row >= 0)
                                data.removeRow(row);
                        }
                        default -> {
                            int next = row + (label.equals("Up") ? -1 : 1);
                            if (row >= 0 && next >= 0 && next < data.getRowCount()) {
                                data.moveRow(row, row, next);
                                table.setRowSelectionInterval(next, next);
                            }
                        }
                    }
                });
                buttons.add(b);
            }
            add(buttons, BorderLayout.SOUTH);
        }
        void commit() {
            if (table.isEditing())
                table.getCellEditor().stopCellEditing();
        }
        String get(int r, int c) {
            return Objects.toString(data.getValueAt(r, c), "").strip();
        }
        void rows(Collection<String[]> rows) {
            data.setRowCount(0);
            for (var row : rows) data.addRow(row);
        }
        List<String[]> rows() {
            var values = new ArrayList<String[]>();
            for (int i = 0; i < data.getRowCount(); i++) {
                String[] r = new String[data.getColumnCount()];
                for (int j = 0; j < r.length; j++) r[j] = get(i, j);
                if (!r[0].isBlank())
                    values.add(r);
            }
            return values;
        }
    }
    private final Project project;
    private final JComboBox<String> target = new JComboBox<>(TARGETS);
    private final JComboBox<ShaderConfig.Stage> stage = new JComboBox<>(ShaderConfig.Stage.values());
    final Grid rules = new Grid("Folder or file", "Target"), includes = new Grid("Include directory"),
               macros = new Grid("Macro", "Shader stage"),
               pipelines = new Grid("Pipeline name", "Stage file or file#variant");
    private final Map<String, JCheckBox> flags = new LinkedHashMap<>();
    private String original;
    public SettingsUI(Project project) {
        this.project = project;
    }
    public String getDisplayName() {
        return "GLSL Support";
    }
    static JPanel form() {
        var p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }
    static JPanel row(String label, JComponent control) {
        var p = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 4));
        p.add(new JBLabel(label));
        p.add(control);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }
    public JComponent createComponent() {
        var tabs = new JBTabbedPane();
        var general = new JPanel(new BorderLayout(6, 8));
        var head = form();
        head.add(row("Default target", target));
        head.add(row("Fallback shader stage", stage));
        general.add(head, BorderLayout.NORTH);
        general.add(rules, BorderLayout.CENTER);
        rules.table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(TARGETS)));
        var stageNames = Arrays.stream(ShaderConfig.Stage.values()).map(Enum::name).toArray(String[] ::new);
        macros.table.getColumnModel().getColumn(1).setCellEditor(
            new DefaultCellEditor(new JComboBox<>(stageNames)));
        tabs.addTab("Targets", general);
        tabs.addTab("Includes", includes);
        tabs.addTab("Stage macros", macros);
        tabs.addTab("Pipelines", pipelines);
        var editor = form();
        for (String label : List.of("Compiler diagnostics", "Built-in completion", "Semantic highlighting",
                 "Parameter hints", "Signature help", "Color previews", "Code formatting", "Selection formatting",
                 "On-type formatting")) {
            var box = new JCheckBox(label);
            flags.put(label, box);
            editor.add(box);
        }
        tabs.addTab("Editor", editor);
        reset();
        return tabs;
    }
    private String snapshot() {
        return Arrays.deepToString(new Object[] {target.getSelectedItem(), stage.getSelectedItem(),
            rules.rows().toArray(), includes.rows().toArray(), macros.rows().toArray(), pipelines.rows().toArray(),
            flags.values().stream().map(AbstractButton::isSelected).toArray()});
    }
    public boolean isModified() {
        return rules.table.isEditing() || includes.table.isEditing() || macros.table.isEditing()
            || pipelines.table.isEditing() || !Objects.equals(snapshot(), original);
    }
    public void reset() {
        var d = Settings.of(project).getState();
        target.setSelectedItem(target(d.target));
        stage.setSelectedItem(Settings.stage(d.fallback));
        rules.rows(
            d.targets.entrySet().stream().map(e -> new String[] {e.getKey(), target(e.getValue())}).toList());
        includes.rows(d.includes.stream().map(v -> new String[] {v}).toList());
        macros.rows(d.stageMacros.entrySet().stream().map(e -> new String[] {e.getKey(), e.getValue()}).toList());
        var pipes = new ArrayList<String[]>();
        d.pipelines.forEach((n, v) -> v.forEach(f -> pipes.add(new String[] {n, f})));
        pipelines.rows(pipes);
        boolean[] values = {
            d.diagnostics, d.builtins, d.semantic, d.inlays, d.signatures, d.colors, d.format, d.range, d.onType};
        int i = 0;
        for (var flag : flags.values()) flag.setSelected(values[i++]);
        original = snapshot();
    }
    public void apply() throws ConfigurationException {
        for (var grid : List.of(rules, includes, macros, pipelines)) grid.commit();
        var s = Settings.of(project);
        var d = s.getState();
        var newMacros = new LinkedHashMap<String, String>();
        for (var r : macros.rows()) {
            if (Settings.stage(r[1]) == null)
                throw new ConfigurationException("Select a stage for macro " + r[0]);
            newMacros.put(r[0], r[1]);
        }
        var newPipes = new LinkedHashMap<String, List<String>>();
        for (var r : pipelines.rows()) {
            if (r[1].isBlank())
                throw new ConfigurationException("Select a shader for pipeline " + r[0]);
            newPipes.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r[1]);
        }
        d.target = target((String) target.getSelectedItem());
        d.fallback = Objects.requireNonNull(stage.getSelectedItem()).toString();
        d.targets.clear();
        for (var r : rules.rows()) d.targets.put(r[0], target(r[1]));
        d.includes = new ArrayList<>(includes.rows().stream().map(r -> r[0]).toList());
        d.stageMacros = newMacros;
        d.pipelines = newPipes;
        var f = flags.values().stream().map(AbstractButton::isSelected).toList();
        d.diagnostics = f.get(0);
        d.builtins = f.get(1);
        d.semantic = f.get(2);
        d.inlays = f.get(3);
        d.signatures = f.get(4);
        d.colors = f.get(5);
        d.format = f.get(6);
        d.range = f.get(7);
        d.onType = f.get(8);
        s.changed();
        DaemonCodeAnalyzer.getInstance(project).restart("GLSL project settings changed");
        original = snapshot();
    }
}
