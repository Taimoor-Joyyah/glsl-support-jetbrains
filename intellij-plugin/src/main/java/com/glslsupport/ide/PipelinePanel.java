package com.glslsupport.ide;

import com.glslsupport.lang.ShaderConfig;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.*;
import com.intellij.ui.components.*;
import com.intellij.ui.content.ContentFactory;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public final class PipelinePanel extends JPanel {
    private static final String INHERIT = "Use stage targets", AUTO = "Auto";
    private static final Map<String, List<String>> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("Graphics", List.of("VERTEX", "TESS_CONTROL", "TESS_EVAL", "GEOMETRY", "FRAGMENT"));
        TYPES.put("Compute", List.of("COMPUTE"));
        TYPES.put("Mesh", List.of("TASK", "MESH", "FRAGMENT"));
        TYPES.put("Ray tracing", List.of("RAYGEN", "INTERSECTION", "ANY_HIT", "CLOSEST_HIT", "MISS", "CALLABLE"));
        TYPES.put("Custom", List.of());
    }
    private final Project project;
    private final ShaderChoices choices;
    private final Map<String, Settings.Pipeline> draft = new LinkedHashMap<>();
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JBList<String> list = new JBList<>(names);
    private final JComboBox<String> type = new JComboBox<>(TYPES.keySet().toArray(String[] ::new)),
                                    target = new JComboBox<>();
    private final SettingsUI.Grid stages = new SettingsUI.Grid("Stage", "Shader file", "Variant"),
                                  definitions = new SettingsUI.Grid("Definition", "Value");
    private final JPanel details = new JPanel(new BorderLayout(4, 4));
    private final JLabel status = new JBLabel();
    private String selected;
    private boolean loading;
    private List<?> original = List.of(), persisted = List.of();
    PipelinePanel(Project project, ShaderChoices choices) {
        super(new BorderLayout(4, 4));
        this.project = project;
        this.choices = choices;
        target.addItem(INHERIT);
        for (String s : SettingsUI.TARGETS) target.addItem(s);
        com.intellij.ui.ComboboxSpeedSearch.installOn(target);
        var left = new JPanel(new BorderLayout());
        left.add(new JBScrollPane(list));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 2));
        for (String label : List.of("Add", "Rename", "Remove")) {
            var b = new JButton(label);
            actions.add(b);
            b.addActionListener(e -> changeNames(label));
        }
        left.add(actions, BorderLayout.SOUTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        com.intellij.ui.ListSpeedSearch.installOn(list);
        list.getAccessibleContext().setAccessibleName("GLSL pipelines");
        list.getEmptyText().setText("No pipelines");
        list.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(
                JList<?> l, Object value, int i, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, i, sel, focus);
                setIcon(GlslIcons.PIPELINE);
                return this;
            }
        });
        var header = SettingsUI.form();
        header.add(SettingsUI.row("Pipeline type", type));
        var targetRow = SettingsUI.row("Compiler target", target);
        var suggest = new JButton("Suggest from shaders");
        targetRow.add(suggest);
        header.add(targetRow);
        suggest.addActionListener(e -> {
            stages.commit();
            var paths = capture().stages;
            if (paths.isEmpty()) {
                status.setText("Assign a shader to suggest a target.");
                return;
            }
            int recommended = paths.stream().mapToInt(b -> choices.suggestedTarget(b.file)).max().orElse(0);
            target.setSelectedItem(SettingsUI.target(recommended));
        });
        details.add(header, BorderLayout.NORTH);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stages, definitions);
        split.setResizeWeight(.62);
        stages.setBorder(BorderFactory.createTitledBorder("Stage assignments"));
        definitions.setBorder(BorderFactory.createTitledBorder("Pipeline definitions"));
        details.add(split);
        var panels = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, details);
        panels.setResizeWeight(.25);
        panels.setDividerLocation(190);
        left.setMinimumSize(new Dimension(90, 55));
        details.setMinimumSize(new Dimension(120, 95));
        panels.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                int orientation =
                    panels.getWidth() < 620 ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT;
                if (panels.getOrientation() != orientation) {
                    panels.setOrientation(orientation);
                    panels.setDividerLocation(.25);
                }
            }
        });
        add(panels);
        add(status, BorderLayout.SOUTH);
        stages.editableColumn = c -> c != 0 || "Custom".equals(type.getSelectedItem());
        var stageList = new ArrayList<String>();
        stageList.add(AUTO);
        for (var s : ShaderConfig.Stage.values()) stageList.add(s.name());
        stages.table.getColumnModel().getColumn(0).setCellEditor(
            new DefaultCellEditor(new JComboBox<>(stageList.toArray(String[] ::new))));
        choices.complete(stages.table, 1, row -> stages.get(row, 0), choices::files);
        choices.complete(stages.table, 2, row -> stages.get(row, 1), choices::variants);
        choices.complete(definitions.table, 0, row -> {
            var paths = new ArrayList<String>();
            for (int i = 0; i < stages.data.getRowCount(); i++)
                if (!stages.get(i, 1).isEmpty())
                    paths.add(stages.get(i, 1));
            return paths;
        }, choices::definitions);
        stages.button("Browse…", () -> {
            int row = stages.table.getSelectedRow();
            if (row < 0 && stages.data.getRowCount() > 0)
                row = 0;
            if (row < 0)
                return;
            String file = choices.browse(this, stages.get(row, 1),
                FileChooserDescriptorFactory.createSingleFileDescriptor(GlslLanguage.Type.INSTANCE));
            if (file != null)
                stages.data.setValueAt(file, row, 1);
        });
        type.addActionListener(e -> {
            if (loading || selected == null)
                return;
            stages.commit();
            definitions.commit();
            var previous = draft.get(selected);
            var next = capture();
            var allowed = TYPES.get(next.type);
            if (!allowed.isEmpty() && next.stages.stream().anyMatch(s -> !allowed.contains(s.stage))) {
                loading = true;
                type.setSelectedItem(previous.type);
                loading = false;
                status.setText("Clear incompatible stage assignments before changing the pipeline type.");
                return;
            }
            draft.put(selected, next);
            showPipeline(next);
        });
        list.addListSelectionListener(e -> {
            if (loading || e.getValueIsAdjusting())
                return;
            store();
            selected = list.getSelectedValue();
            showPipeline(draft.get(selected));
        });
        reset();
    }
    private void changeNames(String action) {
        if (!action.equals("Add") && selected == null)
            return;
        store();
        if (action.equals("Remove")) {
            String old = selected;
            select(null);
            draft.remove(old);
            names.removeElement(old);
            if (!names.isEmpty())
                select(names.firstElement());
            return;
        }
        String input = Messages.showInputDialog(project, "Pipeline name", action + " GLSL Pipeline",
            GlslIcons.PIPELINE, action.equals("Rename") ? selected : "", null);
        if (input == null || input.isBlank())
            return;
        String name = input.strip();
        if (draft.containsKey(name)) {
            status.setText("A pipeline with this name already exists.");
            return;
        }
        var p = action.equals("Add") ? new Settings.Pipeline() : draft.get(selected);
        if (action.equals("Rename")) {
            String old = selected;
            select(null);
            draft.remove(old);
            names.removeElement(old);
        }
        p.name = name;
        draft.put(name, p);
        names.addElement(name);
        select(name);
    }
    private void select(String name) {
        loading = true;
        list.setSelectedValue(name, true);
        if (name == null)
            list.clearSelection();
        loading = false;
        selected = name;
        showPipeline(draft.get(name));
    }
    private void showPipeline(Settings.Pipeline p) {
        loading = true;
        try {
            details.setVisible(p != null);
            status.setText(p == null ? "Add a pipeline to assign shader stages." : " ");
            if (p == null)
                return;
            type.setSelectedItem(p.type);
            target.setSelectedItem(p.target < 0 ? INHERIT : SettingsUI.target(p.target));
            var rows = new ArrayList<String[]>();
            var preset = TYPES.getOrDefault(p.type, List.of());
            if (preset.isEmpty()) {
                p.stages.forEach(
                    s -> rows.add(new String[] {s.stage.isBlank() ? AUTO : s.stage, s.file, s.variant}));
            } else {
                var remaining = new ArrayList<>(p.stages);
                for (String stage : preset) {
                    var binding = remaining.stream()
                                      .filter(s
                                          -> s.stage.equals(stage)
                                              || s.stage.isEmpty()
                                                  && ShaderConfig.Stage.fromPath(s.file)
                                                      .map(Enum::name)
                                                      .orElse("")
                                                      .equals(stage))
                                      .findFirst();
                    binding.ifPresent(remaining::remove);
                    rows.add(new String[] {
                        stage, binding.map(s -> s.file).orElse(""), binding.map(s -> s.variant).orElse("")});
                }
                for (var extra : remaining)
                    rows.add(new String[] {extra.stage.isEmpty() ? AUTO : extra.stage, extra.file, extra.variant});
            }
            for (var row : rows) row[2] = choices.variantValue(row[1], row[2], false);
            stages.rows(rows);
            stages.rowButtons.setVisible(preset.isEmpty());
            definitions.rows(
                p.definitions.entrySet().stream().map(e -> new String[] {e.getKey(), e.getValue()}).toList());
        } finally {
            loading = false;
        }
    }
    private Settings.Pipeline capture() {
        var p = new Settings.Pipeline();
        p.name = selected;
        p.type = (String) type.getSelectedItem();
        p.target = target.getSelectedIndex() == 0 ? -1 : SettingsUI.target((String) target.getSelectedItem());
        for (int r = 0; r < stages.data.getRowCount(); r++) {
            String path = stages.get(r, 1);
            if (path.isBlank())
                continue;
            String stage = stages.get(r, 0);
            p.stages.add(new Settings.StageFile(
                stage.equals(AUTO) ? "" : stage, path, choices.variantValue(path, stages.get(r, 2), true)));
        }
        for (var row : definitions.rows()) p.definitions.put(row[0], row[1]);
        return p;
    }
    private void store() {
        stages.commit();
        definitions.commit();
        if (selected != null && draft.containsKey(selected))
            draft.put(selected, capture());
    }
    private List<?> snapshot() {
        // Settings polls isModified while a cell is being edited. Never stop that editor from here.
        return draft.entrySet()
            .stream()
            .map(e -> e.getKey().equals(selected) ? capture().snapshot() : e.getValue().snapshot())
            .toList();
    }
    boolean modified() {
        return stages.table.isEditing() || definitions.table.isEditing() || !snapshot().equals(original);
    }
    void reset() {
        String before = selected;
        loading = true;
        selected = null;
        draft.clear();
        names.clear();
        Settings.of(project).pipelines().forEach((name, p) -> {
            draft.put(name, p.copy());
            names.addElement(name);
        });
        loading = false;
        select(draft.containsKey(before) ? before : names.isEmpty() ? null : names.firstElement());
        original = snapshot();
        persisted = storedSnapshot();
    }
    private List<?> storedSnapshot() {
        return Settings.of(project).pipelines().values().stream().map(Settings.Pipeline::snapshot).toList();
    }
    void apply() throws ConfigurationException {
        if (!modified())
            return;
        if (!persisted.equals(storedSnapshot()))
            throw new ConfigurationException(
                "Another editor saved these pipelines. Reload or reset before saving your changes.");
        store();
        for (var p : draft.values()) {
            for (String macro : p.definitions.keySet())
                if (!macro.matches("[A-Za-z_]\\w*"))
                    throw new ConfigurationException("Invalid definition in " + p.name + ": " + macro);
            var assigned = new HashSet<String>();
            for (var b : p.stages) {
                String stage =
                    b.stage.isBlank() ? ShaderConfig.Stage.fromPath(b.file).map(Enum::name).orElse("") : b.stage;
                if (!stage.isBlank() && !assigned.add(stage))
                    throw new ConfigurationException("Duplicate stage " + stage + " in " + p.name);
            }
        }
        var settings = Settings.of(project);
        settings.getState().pipelineDefinitions =
            new ArrayList<>(draft.values().stream().map(Settings.Pipeline::copy).toList());
        settings.getState().pipelines.clear();
        settings.changed();
        original = snapshot();
        persisted = storedSnapshot();
    }
    public static final class Window implements ToolWindowFactory {
        public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
            var editor = new PipelinePanel(project, new ShaderChoices(project));
            var wrap = new JPanel(new BorderLayout(4, 4));
            wrap.add(editor);
            var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
            for (String label : List.of("Save", "Reload", "Validate", "Validate All")) {
                var button = new JButton(label);
                actions.add(button);
                button.addActionListener(e -> {
                    if (label.equals("Reload")) {
                        editor.reset();
                        return;
                    }
                    try {
                        editor.apply();
                        editor.status.setText("Pipelines saved.");
                        if (label.equals("Validate") && editor.selected != null)
                            Actions.Pipeline.validate(project, editor.selected);
                        else if (label.equals("Validate All"))
                            Actions.AllPipelines.validate(project);
                    } catch (ConfigurationException ex) {
                        editor.status.setText(ex.getMessageHtml().toString());
                    }
                });
            }
            wrap.add(actions, BorderLayout.SOUTH);
            wrap.addHierarchyListener(e -> {
                if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && wrap.isShowing()
                    && !editor.modified())
                    editor.reset();
            });
            window.getContentManager().addContent(ContentFactory.getInstance().createContent(wrap, "", false));
        }
    }
}
