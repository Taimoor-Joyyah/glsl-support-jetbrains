package com.glslsupport.ide;

import com.glslsupport.bridge.*;
import com.glslsupport.lang.Workspace;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;
import org.jetbrains.annotations.NotNull;

public final class PipelineValidation extends JPanel implements Disposable {
    private record Item(String label, PipelineDiagnostics.Location location, String log) {
        public String toString() { return label; }
    }
    private record Job(String name, CompilerSupport.PipelineInput input, String error) {}
    private final Project project;
    private final DefaultMutableTreeNode root = node("Pipelines");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JTextArea raw = new JTextArea();
    private final JLabel status = new JLabel();
    private final JButton cancel = new JButton("Cancel");
    private volatile Set<String> paths = Set.of();
    private volatile long generation;
    private volatile ProgressIndicator running;
    private boolean stale;
    private int passed, failed;
    private String summary = "";

    static void open(Project project, List<String> names) {
        var window = ToolWindowManager.getInstance(project).getToolWindow("GLSL Inspector");
        if (window == null) return;
        var manager = window.getContentManager();
        var content = manager.findContent("Pipeline validation");
        if (content == null) {
            var panel = new PipelineValidation(project);
            content = ContentFactory.getInstance().createContent(panel, "Pipeline validation", false);
            content.setDisposer(panel);
            manager.addContent(content);
        }
        manager.setSelectedContent(content);
        window.activate(null);
        ((PipelineValidation) content.getComponent()).start(List.copyOf(names));
    }
    private PipelineValidation(Project project) {
        super(new BorderLayout(4, 4));
        this.project = project;
        var bar = new JPanel(new FlowLayout(FlowLayout.LEADING));
        bar.add(status);
        bar.add(cancel);
        cancel.addActionListener(e -> stop());
        add(bar, BorderLayout.NORTH);
        raw.setEditable(false);
        raw.getAccessibleContext().setAccessibleName("Original compiler log");
        tree.getAccessibleContext().setAccessibleName("Pipeline validation results");
        tree.addTreeSelectionListener(e -> {
            var selected = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            while (selected != null && selected.getUserObject() instanceof Item item) {
                if (item.log() != null) {
                    raw.setText(item.log());
                    raw.setCaretPosition(0);
                    return;
                }
                selected = (DefaultMutableTreeNode) selected.getParent();
            }
            raw.setText("");
        });
        tree.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) navigate();
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openGLSLSource");
        tree.getActionMap().put("openGLSLSource", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { navigate(); }
        });
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JBScrollPane(tree), new JBScrollPane(raw));
        split.setResizeWeight(.7);
        add(split);
        ShaderChanges.watch(project, this, () -> paths, false, this::markStale);
    }
    private void markStale() {
        if (!SwingUtilities.isEventDispatchThread()) {
            long ticket = generation;
            post(ticket, this::markStale);
            return;
        }
        stale = true;
        updateStatus();
    }
    private void navigate() {
        if (!(tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n)) return;
        var location = ((Item)n.getUserObject()).location();
        if (location != null) {
            var file = LocalFileSystem.getInstance().findFileByPath(location.path());
            if (file != null) new OpenFileDescriptor(project, file, location.offset()).navigate(true);
        }
    }
    private static DefaultMutableTreeNode node(String label) {
        return new DefaultMutableTreeNode(new Item(label, null, null));
    }
    private void updateStatus() { status.setText(summary + (stale ? " · Inputs changed, rerun to update" : "")); }
    private void stop() {
        generation++;
        var indicator = running;
        if (indicator != null) indicator.cancel();
        running = null;
        cancel.setEnabled(false);
        summary = "Cancelled · " + passed + " passed, " + failed + " failed";
        updateStatus();
    }
    private void start(List<String> names) {
        stop();
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        long ticket = generation;
        Set<String> watched = java.util.concurrent.ConcurrentHashMap.newKeySet();
        paths = watched;
        root.removeAllChildren();
        model.reload();
        raw.setText("");
        stale = false;
        passed = failed = 0;
        summary = names.isEmpty() ? "No configured pipelines" : "Validating " + names.size() + " pipeline(s)…";
        updateStatus();
        cancel.setEnabled(!names.isEmpty());
        if (names.isEmpty()) return;
        new Task.Backgroundable(project, "Validate GLSL pipelines", true) {
            public void run(@NotNull ProgressIndicator indicator) {
                if (ticket != generation) return;
                running = indicator;
                var jobs = ReadAction.computeBlocking(() -> prepare(names, watched, ticket, indicator));
                for (int i = 0; i < jobs.size(); i++) {
                    indicator.checkCanceled();
                    if (ticket != generation) return;
                    var job = jobs.get(i);
                    String name = job.name();
                    indicator.setText(name);
                    indicator.setFraction(i / (double) names.size());
                    try {
                        if (job.error() != null) {
                            reportFailure(ticket, name, job.error());
                            continue;
                        }
                        var input = job.input();
                        var result = CompilerSupport.of(project).analyze(input.request());
                        var issues = PipelineDiagnostics.collect(input.request(), result,
                            input.stages().stream().map(PipelineContexts.Context::graph).toList());
                        indicator.checkCanceled();
                        post(ticket, () -> append(name, input, result, issues));
                    } catch (ProcessCanceledException cancelled) {
                        throw cancelled;
                    } catch (RuntimeException | LinkageError error) {
                        reportFailure(ticket, name, Objects.toString(error.getMessage(), error.toString()));
                    }
                }
            }
            public void onSuccess() {
                post(ticket, () -> {
                    running = null;
                    cancel.setEnabled(false);
                    summary = passed + " passed, " + failed + " failed";
                    updateStatus();
                });
            }
            public void onCancel() { post(ticket, () -> stop()); }
        }.queue();
    }
    private List<Job> prepare(List<String> names, Set<String> watched, long ticket, ProgressIndicator indicator) {
        if (project.isDisposed() || ticket != generation) return List.of();
        var configured = Settings.of(project).pipelines();
        var jobs = new ArrayList<Job>();
        for (String name : names) {
            indicator.checkCanceled();
            if (ticket != generation) return List.of();
            var pipeline = configured.get(name);
            try {
                if (pipeline != null) for (var binding : pipeline.stages) {
                    String path = Workspace.resolvePath(Objects.requireNonNullElse(project.getBasePath(), ""), binding.file);
                    if (path != null) watched.add(path);
                }
                var input = CompilerSupport.pipelineInput(project, pipeline);
                for (var stage : input.stages()) watched.addAll(stage.graph().inputPaths());
                jobs.add(new Job(name, input, null));
            } catch (ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (RuntimeException error) {
                jobs.add(new Job(name, null, Objects.toString(error.getMessage(), error.toString())));
            }
        }
        return jobs;
    }
    private void reportFailure(long ticket, String name, String error) {
        post(ticket, () -> insert(new DefaultMutableTreeNode(new Item(name + " · Failed", null, error)),
            node(error), false));
    }
    private void post(long ticket, Runnable work) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (generation == ticket && !project.isDisposed()) work.run();
        }, project.getDisposed());
    }
    private void insert(DefaultMutableTreeNode pipeline, DefaultMutableTreeNode error, boolean success) {
        if (error != null) pipeline.add(error);
        model.insertNodeInto(pipeline, root, root.getChildCount());
        if (success) passed++; else failed++;
        tree.expandPath(new TreePath(root.getPath()));
        if (!success) tree.expandPath(new TreePath(pipeline.getPath()));
        if (root.getChildCount() == 1) tree.setSelectionPath(new TreePath(pipeline.getPath()));
    }
    private void append(String name, CompilerSupport.PipelineInput input, NativeCompiler.Result r,
                        List<PipelineDiagnostics.Issue> issues) {
        var pipeline = new DefaultMutableTreeNode(new Item(name + (r.success() ? " · Passed" : " · Failed"), null, r.log()));
        var stages = new ArrayList<DefaultMutableTreeNode>();
        for (var c : input.stages()) {
            var stage = node(c.config().stage() + " · " + c.variantName());
            pipeline.add(stage);
            stages.add(stage);
        }
        for (var issue : issues) {
            var where = issue.locations().size() == 1 ? issue.locations().getFirst() : null;
            var problem = new DefaultMutableTreeNode(new Item(issue.severity() + " · " +
                issue.message().lines().findFirst().orElse(""), where, null));
            (issue.stage() < 0 ? pipeline : stages.get(issue.stage())).add(problem);
            for (var loc : issue.locations())
                problem.add(new DefaultMutableTreeNode(new Item(loc.label(), loc, null)));
        }
        if (issues.isEmpty() && !r.success() && !r.log().isBlank()) pipeline.add(node(r.log().strip()));
        insert(pipeline, null, r.success());
    }
    public void dispose() { stop(); }
}
