package com.glslsupport.ide;

import com.glslsupport.bridge.NativeCompiler;
import com.glslsupport.lang.*;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.util.messages.Topic;
import java.util.*;
import org.jetbrains.annotations.NotNull;

@State(name = "GlslEditorContexts", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class PipelineContexts implements PersistentStateComponent<PipelineContexts.Choices> {
    public interface Listener { void contextsChanged(); }
    static final Topic<Listener> CHANGED = Topic.create("GLSL editor context changed", Listener.class);
    public static final class Pin {
        public String pipeline = "", root = "", stage = "";
        public Pin() {}
        Pin(Context c) {
            if (c.pipeline() != null) {
                pipeline = c.pipeline();
                root = Settings.relative(c.root().getProject(), ProjectAccess.key(c.root()));
                stage = c.assignedStage();
            }
        }
        boolean matches(Context c) {
            return pipeline.equals(c.pipeline()) && stage.equals(c.assignedStage())
                && root.equals(Settings.relative(c.root().getProject(), ProjectAccess.key(c.root())));
        }
    }
    public static final class Choices {
        public Map<String, Pin> files = new TreeMap<>();
    }
    public record Context(PsiFile root, String pipeline, String assignedStage, String variantId,
                          String variantName, ShaderConfig.Effective config, Map<String,String> variantMacros,
                          Map<String,String> pipelineMacros, Workspace graph, List<String> includeRoots) {
        public String label() {
            return pipeline == null ? "File variant · " + variantName
                : pipeline + " → " + config.stage() + " · " + variantName;
        }
        public NativeCompiler.Request request(NativeCompiler.Operation op) {
            var headers = new LinkedHashMap<String,String>();
            String path = ProjectAccess.key(root);
            graph.files.forEach((key, syntax) -> {
                if (!key.equals(path)) headers.put(key, syntax.source);
            });
            return new NativeCompiler.Request(config.vulkan(), op,
                List.of(new NativeCompiler.Unit(path, graph.files.get(path).source, config)), headers, includeRoots);
        }
    }

    private final Project project;
    // Settings can change while an IDE read action is building a graph. Old computations
    // must publish into their original cache, even if they finish after the settings change.
    private static final Key<Cache> GRAPH_CACHE = Key.create("glsl.context.graphCache");
    private static final class Cache extends UserDataHolderBase {
        final long settingsRevision;
        volatile Set<String> inputs = Set.of();
        Cache(long settingsRevision) { this.settingsRevision = settingsRevision; }
    }
    private Cache cache(UserDataHolder owner) {
        var changes = Settings.of(project).changes;
        synchronized (this) {
            long revision = changes.getModificationCount();
            var cache = owner.getUserData(GRAPH_CACHE);
            if (cache == null || cache.settingsRevision != revision) {
                cache = new Cache(revision);
                owner.putUserData(GRAPH_CACHE, cache);
            }
            return cache;
        }
    }
    UserDataHolder sourceCache(PsiFile root) {
        return cache(root);
    }
    Set<String> configuredInputs() {
        var current = project.getUserData(GRAPH_CACHE);
        return current == null ? Set.of() : current.inputs;
    }
    private Choices choices = new Choices();
    final SimpleModificationTracker selectionChanges = new SimpleModificationTracker();
    public PipelineContexts(Project project) { this.project = project; }
    public static PipelineContexts of(Project project) { return project.getService(PipelineContexts.class); }
    public synchronized Choices getState() {
        var copy = new Choices();
        copy.files.putAll(choices.files);
        return copy;
    }
    public synchronized void loadState(@NotNull Choices state) {
        choices = state;
        selectionChanges.incModificationCount();
        notifyChanged(project);
    }
    static void notifyChanged(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                project.getMessageBus().syncPublisher(CHANGED).contextsChanged();
                DaemonCodeAnalyzer.getInstance(project).restart("GLSL pipeline context or settings changed");
            }
        }, project.getDisposed());
    }
    public void select(PsiFile file, Context choice) {
        String path = Settings.relative(project, ProjectAccess.key(file));
        synchronized (this) {
            if (choice == null) choices.files.remove(path);
            else choices.files.put(path, new Pin(choice));
            selectionChanges.incModificationCount();
        }
        notifyChanged(project);
    }
    synchronized boolean automatic(PsiFile file) {
        return !choices.files.containsKey(Settings.relative(project, ProjectAccess.key(file)));
    }
    public List<Context> matches(PsiFile file) {
        var owner = cache(project);
        var index = CachedValuesManager.getManager(project).getCachedValue(owner, () -> {
            var byPath = new HashMap<String,List<Context>>();
            var inputs = new HashSet<String>();
            var deps = dependencies();
            for (var pipeline : Settings.of(project).pipelines().values()) {
                for (var binding : pipeline.stages) {
                    ProgressManager.checkCanceled();
                    String root = Workspace.resolvePath(Objects.requireNonNullElse(project.getBasePath(), ""), binding.file);
                    if (root != null) inputs.add(root);
                    try {
                        var c = binding(pipeline, binding, null);
                        collectDependencies(c, deps);
                        inputs.addAll(c.graph().inputPaths());
                        for (var path : c.graph().visible(ProjectAccess.key(c.root())))
                            byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(c);
                    } catch (IllegalArgumentException invalid) {
                        // Validation reports incomplete or invalid configured bindings by name.
                    }
                }
            }
            byPath.replaceAll((path, options) -> List.copyOf(options));
            owner.inputs = Set.copyOf(inputs);
            return CachedValueProvider.Result.create(byPath, deps.toArray());
        });
        return index.getOrDefault(ProjectAccess.key(file), List.of());
    }
    private Set<Object> dependencies() {
        return new HashSet<>(List.of(Settings.of(project).changes, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS));
    }
    private void collectDependencies(Context c, Set<Object> deps) {
        deps.addAll(ProjectAccess.sources(c.root()).dependencies);
    }
    public Context current(PsiFile file) {
        var options = matches(file);
        String path = Settings.relative(project, ProjectAccess.key(file));
        synchronized (this) {
            var pin = choices.files.get(path);
            if (pin != null && !pin.pipeline.isEmpty()) {
                for (var c : options) if (pin.matches(c)) return c;
                choices.files.remove(path);
                pin = null;
            }
            if (pin == null && options.size() == 1) return options.getFirst();
        }
        return standalone(file);
    }
    Context standalone(PsiFile file) {
        return CachedValuesManager.getManager(project).getCachedValue(cache(file), () -> {
            var context = build(file, null, "", null);
            var deps = dependencies();
            collectDependencies(context, deps);
            return CachedValueProvider.Result.create(context, deps.toArray());
        });
    }
    Context withVariant(Context current, String variant) {
        var pipeline = current.pipeline() == null ? null : Settings.of(project).pipelines().get(current.pipeline());
        return build(current.root(), pipeline, current.assignedStage(), variant);
    }
    Context binding(Settings.Pipeline pipeline, Settings.StageFile binding, String variantOverride) {
        String path = Workspace.resolvePath(Objects.requireNonNullElse(project.getBasePath(), ""), binding.file);
        var root = path == null ? null : ProjectAccess.psi(project, path);
        if (root == null) throw new IllegalArgumentException("Missing stage file " + binding.file);
        return build(root, pipeline, binding.stage, variantOverride == null ? binding.variant : variantOverride);
    }
    Context build(PsiFile root, Settings.Pipeline pipeline, String assigned, String variantId) {
        var settings = Settings.of(project);
        String id = variantId == null || variantId.isBlank() ? null : variantId;
        var variant = settings.variant(root, id);
        if (id != null && !id.equals(variant.id) && !id.equals(variant.name))
            throw new IllegalArgumentException("Unknown variant " + id + " in " + root.getName());
        var base = settings.effective(root, variant.id);
        var stage = assigned.isBlank() ? base.stage() : Settings.stage(assigned);
        if (stage == null) throw new IllegalArgumentException("Unknown shader stage " + assigned);
        Map<String,String> overrides = pipeline == null ? Map.of() : pipeline.definitions;
        var macros = new LinkedHashMap<>(base.defines());
        macros.putAll(overrides);
        boolean header = pipeline == null && root.getName().toLowerCase(Locale.ROOT).endsWith(".glslh")
            && base.version() == 0 && !ProjectAccess.model(root).hasVersion();
        var config = new ShaderConfig.Effective(stage, pipeline != null && pipeline.target >= 0
            ? pipeline.target : base.vulkan(), header ? 460 : base.version(), header ? "core" : base.profile(), macros);
        return new Context(root, pipeline == null ? null : pipeline.name, assigned, variant.id, variant.name,
            config, base.defines(), Collections.unmodifiableMap(new LinkedHashMap<>(overrides)),
            ProjectAccess.graph(root, config), settings.roots(project));
    }
}
