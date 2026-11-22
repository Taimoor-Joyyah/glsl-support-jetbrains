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
        final JPanel rowButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 0));
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 3, 0));
        java.util.function.IntPredicate editableColumn = c -> true;
        Grid(String... columns) {
            super(new BorderLayout(0, 4));
            data = new DefaultTableModel(columns, 0) {
                public boolean isCellEditable(int r, int c) {
                    return editableColumn.test(c);
                }
            };
            table = new com.intellij.ui.table.JBTable(data);
            table.setFillsViewportHeight(true);
            add(new JBScrollPane(table));
            buttons.add(rowButtons);
            for (String label : List.of("Add", "Remove", "Up", "Down")) {
                var icon = switch (label) {
                    case "Add" -> com.intellij.icons.AllIcons.General.Add;
                    case "Remove" -> com.intellij.icons.AllIcons.General.Remove;
                    case "Up" -> com.intellij.icons.AllIcons.Actions.MoveUp;
                    default -> com.intellij.icons.AllIcons.Actions.MoveDown;
                };
                var b = new JButton(icon);
                b.setToolTipText(label);
                b.getAccessibleContext().setAccessibleName(label);
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
                rowButtons.add(b);
            }
            add(buttons, BorderLayout.SOUTH);
        }
        void button(String name, Runnable run) {
            var b = new JButton(name);
            b.addActionListener(e -> {
                commit();
                run.run();
            });
            buttons.add(b);
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
    private final JComboBox<String> delay = new JComboBox<>(new String[] {
        "After each edit", "5 seconds", "6 seconds", "7 seconds", "8 seconds", "9 seconds", "10 seconds"});
    private final Project project;
    private final JComboBox<String> target = new JComboBox<>(TARGETS);
    private final JComboBox<ShaderConfig.Stage> stage = new JComboBox<>(ShaderConfig.Stage.values());
    final Grid rules = new Grid("Folder or file", "Target"), includes = new Grid("Include directory"),
               macros = new Grid("Macro", "Shader stage");
    private PipelinePanel pipelines;
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
        var choices = new ShaderChoices(project);
        pipelines = new PipelinePanel(project, choices);
        choices.complete(rules.table, 0, row -> "", choices::files);
        browse(rules, choices, com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleFileOrDir());
        browse(includes, choices, com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleDir());
        rules.data.addTableModelListener(ev -> {
            int r = ev.getFirstRow();
            if (ev.getType() == javax.swing.event.TableModelEvent.UPDATE && ev.getColumn() == 0
                && r < rules.data.getRowCount() && !rules.get(r, 0).isEmpty() && rules.get(r, 1).isEmpty())
                rules.data.setValueAt(target(choices.suggestedTarget(rules.get(r, 0))), r, 1);
        });
        var general = new JPanel(new BorderLayout(6, 8));
        var head = form();
        head.add(row("Default target", target));
        head.add(row("Fallback shader stage", stage));
        general.add(head, BorderLayout.NORTH);
        general.add(rules, BorderLayout.CENTER);
        var targets = new JComboBox<>(TARGETS);
        com.intellij.ui.ComboboxSpeedSearch.installOn(targets);
        rules.table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(targets));
        var stageNames = Arrays.stream(ShaderConfig.Stage.values()).map(Enum::name).toArray(String[] ::new);
        macros.table.getColumnModel().getColumn(1).setCellEditor(
            new DefaultCellEditor(new JComboBox<>(stageNames)));
        tabs.addTab("Targets", GlslIcons.FILE, general);
        tabs.addTab("Includes", com.intellij.icons.AllIcons.Nodes.Folder, includes);
        tabs.addTab("Stage macros", com.intellij.icons.AllIcons.Nodes.Constant, macros);
        tabs.addTab("Pipelines", GlslIcons.PIPELINE, pipelines);
        var editor = form();
        for (String label : List.of("Compiler diagnostics", "Built-in completion", "Semantic highlighting",
                 "Parameter hints", "Signature help", "Color previews", "Code formatting", "Selection formatting",
                 "On-type formatting")) {
            var box = new JCheckBox(label);
            flags.put(label, box);
            editor.add(box);
        }
        editor.add(row("Validate after typing", delay));
        tabs.addTab("Editor", com.intellij.icons.AllIcons.General.Settings, editor);
        reset();
        return tabs;
    }
    private void browse(
        Grid grid, ShaderChoices choices, com.intellij.openapi.fileChooser.FileChooserDescriptor descriptor) {
        grid.button("Browse…", () -> {
            int r = grid.table.getSelectedRow();
            String path = choices.browse(grid, r < 0 ? "" : grid.get(r, 0), descriptor);
            if (path == null)
                return;
            if (r < 0) {
                grid.data.addRow(new String[grid.data.getColumnCount()]);
                r = grid.data.getRowCount() - 1;
            }
            grid.data.setValueAt(path, r, 0);
            grid.table.setRowSelectionInterval(r, r);
        });
    }
    private String snapshot() {
        return Arrays.deepToString(new Object[] {target.getSelectedItem(), stage.getSelectedItem(),
            delay.getSelectedIndex(), rules.rows().toArray(), includes.rows().toArray(), macros.rows().toArray(),
            flags.values().stream().map(AbstractButton::isSelected).toArray()});
    }
    public boolean isModified() {
        return rules.table.isEditing() || includes.table.isEditing() || macros.table.isEditing()
            || pipelines.modified() || !Objects.equals(snapshot(), original);
    }
    public void reset() {
        var d = Settings.of(project).getState();
        target.setSelectedItem(target(d.target));
        delay.setSelectedIndex(
            d.validationDelaySeconds == 0 ? 0 : Math.clamp(d.validationDelaySeconds, 5, 10) - 4);
        stage.setSelectedItem(Settings.stage(d.fallback));
        rules.rows(
            d.targets.entrySet().stream().map(e -> new String[] {e.getKey(), target(e.getValue())}).toList());
        includes.rows(d.includes.stream().map(v -> new String[] {v}).toList());
        macros.rows(d.stageMacros.entrySet().stream().map(e -> new String[] {e.getKey(), e.getValue()}).toList());
        pipelines.reset();
        boolean[] values = {
            d.diagnostics, d.builtins, d.semantic, d.inlays, d.signatures, d.colors, d.format, d.range, d.onType};
        int i = 0;
        for (var flag : flags.values()) flag.setSelected(values[i++]);
        original = snapshot();
    }
    public void apply() throws ConfigurationException {
        for (var grid : List.of(rules, includes, macros)) grid.commit();
        var s = Settings.of(project);
        var d = s.getState();
        var newMacros = new LinkedHashMap<String, String>();
        for (var r : macros.rows()) {
            if (Settings.stage(r[1]) == null)
                throw new ConfigurationException("Select a stage for macro " + r[0]);
            newMacros.put(r[0], r[1]);
        }
        for (var row : rules.rows())
            if (!Arrays.asList(TARGETS).contains(row[1]))
                throw new ConfigurationException("Choose a target for " + row[0]);
        pipelines.apply();
        d.target = target((String) target.getSelectedItem());
        d.validationDelaySeconds = delay.getSelectedIndex() == 0 ? 0 : 4 + delay.getSelectedIndex();
        d.fallback = Objects.requireNonNull(stage.getSelectedItem()).toString();
        d.targets.clear();
        for (var r : rules.rows()) d.targets.put(r[0], target(r[1]));
        d.includes = new ArrayList<>(includes.rows().stream().map(r -> r[0]).toList());
        d.stageMacros = newMacros;
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
