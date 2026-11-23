package com.glslsupport.ide;
import com.glslsupport.bridge.*;
import com.glslsupport.lang.*;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.lang.annotation.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.*;
import com.intellij.psi.*;
import java.util.*;
import org.jetbrains.annotations.*;

public final class Diagnostics
    extends ExternalAnnotator<Diagnostics.Input, Diagnostics.Report> implements DumbAware {
    private static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile("'([^']+)'");
    static final Key<Long> SAVED = Key.create("glslsupport.savedVariants");
    record Job(String variant, NativeCompiler.Request request) {}
    public record Revision(long document, long psi, long settings, long choices, long structure) {
        static Revision of(PsiFile file) {
            var project = file.getProject();
            return new Revision(file.getModificationStamp(),
                com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount(),
                Settings.of(project).changes.getModificationCount(),
                PipelineContexts.of(project).selectionChanges.getModificationCount(),
                com.intellij.openapi.vfs.VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.getModificationCount());
        }
    }
    public record Input(Project project, String main, Syntax file, List<Job> jobs, Revision stamp) {}
    public record Report(Revision stamp, List<Issue> issues) {}
    public record Issue(HighlightSeverity severity, int start, int end, String message) {}
    @Override
    public Input collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        return collectInformation(file);
    }
    @Override
    public Input collectInformation(@NotNull PsiFile file) {
        if (!(file instanceof GlslLanguage.File))
            return null;
        var revision = Revision.of(file);
        var settings = Settings.of(file.getProject());
        if (!settings.getState().diagnostics
            || CompilerSupport.of(file.getProject()).waiting(file.getVirtualFile()))
            return null;
        var jobs = new ArrayList<Job>();
        var document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        boolean saved =
            document != null && Objects.equals(document.getUserData(SAVED), document.getModificationStamp());
        var compiler = CompilerSupport.of(file.getProject());
        var contexts = PipelineContexts.of(file.getProject());
        var context = contexts.current(file);
        var roots = context.pipeline() != null || !contexts.automatic(file) || !contexts.matches(file).isEmpty()
            ? List.of(context.root()) : compiler.owners(file);
        if (roots.isEmpty()) roots = List.of(file);
        if (context.pipeline() != null)
            jobs.add(new Job(context.label(), context.request(NativeCompiler.Operation.CHECK)));
        for (var root : roots) {
            if (saved)
                for (var v : settings.variants(root))
                    jobs.add(new Job(v.name, CompilerSupport.request(root, v.id, NativeCompiler.Operation.CHECK)));
            else if (context.pipeline() == null)
                jobs.add(new Job(root == file ? "" : root.getName(),
                    contexts.standalone(root).request(NativeCompiler.Operation.CHECK)));
        }
        var watched = new HashSet<String>();
        for (var job : jobs) {
            watched.addAll(job.request.headers().keySet());
            job.request.units().forEach(unit -> watched.add(unit.path()));
        }
        watched.remove(ProjectAccess.key(file));
        compiler.track(file, watched, roots.size() == 1 && roots.getFirst().equals(file));
        return new Input(file.getProject(), ProjectAccess.key(file), ProjectAccess.model(file), jobs, revision);
    }
    @Override
    public Report doAnnotate(Input input) {
        if (input == null)
            return null;
        var merged = new LinkedHashMap<Issue, Set<String>>();
        for (var job : input.jobs) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled();
            NativeCompiler.Result result;
            try {
                result = CompilerSupport.of(input.project).analyze(job.request);
            } catch (LinkageError | IllegalStateException e) {
                return new Report(input.stamp,
                    List.of(new Issue(HighlightSeverity.WARNING, 0, Math.min(1, input.file.source.length()),
                        "GLSL compiler unavailable: " + e.getMessage())));
            }
            String root = job.request.units().getFirst().path();
            for (var d : result.diagnostics(root)) {
                boolean local = d.file().equals(input.main);
                if (!local && !root.equals(input.main))
                    continue;
                int a = 0, b = 0;
                if (local) {
                    a = NativeCompiler.offset(input.file.source, d.line(), d.column());
                    b = input.file.source.indexOf('\n', a);
                    if (b < 0)
                        b = input.file.source.length();
                    var quoted = QUOTED.matcher(d.message());
                    if (quoted.find()) {
                        String word = quoted.group(1);
                        int from = input.file.source.lastIndexOf('\n', Math.max(0, a - 1)) + 1;
                        int nearest = -1, distance = Integer.MAX_VALUE;
                        for (int i = input.file.indexAt(from);
                            i < input.file.code.size() && input.file.code.get(i).start() < b; i++) {
                            var token = input.file.code.get(i);
                            if (!token.text().equals(word))
                                continue;
                            if (d.message().contains("swizzle") && !input.file.word(i - 1).equals("."))
                                continue;
                            int delta = Math.abs(token.start() - a);
                            if (delta < distance) {
                                distance = delta;
                                nearest = i;
                            }
                        }
                        if (nearest >= 0) {
                            var token = input.file.code.get(nearest);
                            a = token.start();
                            b = token.end();
                            if (d.message().contains("unknown swizzle selection")) {
                                for (int n = 0; n < word.length(); n++) {
                                    if ("xyzwrgbastpq".indexOf(word.charAt(n)) < 0) {
                                        a += n;
                                        b = a + 1;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    var inc = owningInclude(input.file, input.main, d.file(), job.request, new HashSet<>());
                    if (inc != null) {
                        a = inc.start();
                        b = inc.end();
                    }
                }
                var msg = local ? d.message() : d.file() + ":" + d.line() + " — " + d.message();
                var issue =
                    new Issue(d.severity().equals("WARNING") ? HighlightSeverity.WARNING : HighlightSeverity.ERROR,
                        a, b, msg);
                merged.computeIfAbsent(issue, k -> new LinkedHashSet<>()).add(job.variant);
            }
        }
        var issues = new ArrayList<Issue>();
        merged.forEach((i, variants)
                           -> issues.add(new Issue(i.severity, i.start, i.end,
                               i.message
                                   + (variants.size() == 1 && variants.contains("")
                                           ? ""
                                           : " [" + String.join(", ", variants) + "]"))));
        return new Report(input.stamp, issues);
    }
    private static Syntax.Include owningInclude(
        Syntax file, String path, String wanted, NativeCompiler.Request request, Set<String> seen) {
        if (!seen.add(path))
            return null;
        for (var inc : file.includes) {
            var roots = new ArrayList<>(request.includeRoots());
            var parent = java.nio.file.Path.of(path).getParent();
            if (!inc.system() && parent != null)
                roots.addFirst(parent.toString());
            for (String dir : roots) {
                String next = Workspace.resolvePath(dir, inc.path());
                if (next == null)
                    continue;
                if (next.equals(wanted))
                    return inc;
                String contents = request.headers().get(next);
                if (contents != null) {
                    if (owningInclude(new Syntax(contents), next, wanted, request, seen) != null)
                        return inc;
                    break;
                }
            }
        }
        return null;
    }
    @Override
    public void apply(@NotNull PsiFile file, Report report, @NotNull AnnotationHolder holder) {
        if (report != null && report.stamp.equals(Revision.of(file))
            && PsiDocumentManager.getInstance(file.getProject()).isCommitted(file.getViewProvider().getDocument()))
            for (var i : report.issues) {
                int len = file.getTextLength();
                int a = Math.min(i.start, len), b = Math.min(i.end, len);
                var annotation = holder.newAnnotation(i.severity, i.message);
                if (b > a)
                    annotation.range(new TextRange(a, b));
                else
                    annotation.fileLevel();
                annotation.create();
            }
    }
    public static final class Saved implements FileDocumentManagerListener {
        @Override
        public void beforeDocumentSaving(@NotNull Document doc) {
            var v = FileDocumentManager.getInstance().getFile(doc);
            if (v == null || v.getFileType() != GlslLanguage.Type.INSTANCE)
                return;
            doc.putUserData(SAVED, doc.getModificationStamp());
            for (var p : ProjectManager.getInstance().getOpenProjects()) {
                if (p.isDisposed())
                    continue;
                CompilerSupport.of(p).ready(v);
                var f = PsiManager.getInstance(p).findFile(v);
                if (f != null)
                    DaemonCodeAnalyzer.getInstance(p).restart(f, "GLSL shader saved");
            }
        }
    }
}
