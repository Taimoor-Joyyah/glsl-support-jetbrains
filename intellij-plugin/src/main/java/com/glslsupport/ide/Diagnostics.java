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

public final class Diagnostics extends ExternalAnnotator<Diagnostics.Input, List<Diagnostics.Issue>> {
    static final Key<Long> SAVED = Key.create("glslsupport.savedVariants");
    record Job(String variant, NativeCompiler.Request request) {}
    record Input(Project project, String main, Syntax file, List<Job> jobs) {}
    record Issue(HighlightSeverity severity, int start, int end, String message) {}
    @Override
    public Input collectInformation(@NotNull PsiFile file) {
        if (!(file instanceof GlslLanguage.File) || file.getName().endsWith(".glslh"))
            return null;
        var settings = Settings.of(file.getProject());
        if (!settings.getState().diagnostics)
            return null;
        var jobs = new ArrayList<Job>();
        var document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document != null && Objects.equals(document.getUserData(SAVED), document.getModificationStamp())) {
            for (var v : settings.variants(file))
                jobs.add(new Job(v.name, CompilerSupport.request(file, v.id, NativeCompiler.Operation.CHECK)));
        } else
            jobs.add(new Job("", CompilerSupport.request(file, null, NativeCompiler.Operation.CHECK)));
        var watched = new HashSet<String>();
        for (var job : jobs) watched.addAll(job.request.headers().keySet());
        CompilerSupport.of(file.getProject()).track(file, watched);
        return new Input(file.getProject(), ProjectAccess.key(file), ProjectAccess.model(file), jobs);
    }
    @Override
    public List<Issue> doAnnotate(Input input) {
        if (input == null)
            return List.of();
        var merged = new LinkedHashMap<Issue, Set<String>>();
        for (var job : input.jobs) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled();
            NativeCompiler.Result result;
            try {
                result = CompilerSupport.of(input.project).analyze(job.request);
            } catch (LinkageError | IllegalStateException e) {
                return List.of(new Issue(HighlightSeverity.WARNING, 0, Math.min(1, input.file.source.length()),
                    "GLSL compiler unavailable: " + e.getMessage()));
            }
            for (var d : result.diagnostics(input.main)) {
                boolean local = d.file().equals(input.main);
                int a = 0, b = 0;
                if (local) {
                    a = NativeCompiler.offset(input.file.source, d.line(), d.column());
                    b = input.file.source.indexOf('\n', a);
                    if (b < 0)
                        b = input.file.source.length();
                    var quoted = java.util.regex.Pattern.compile("'([^']+)'").matcher(d.message());
                    if (quoted.find()) {
                        int hit = input.file.source.indexOf(quoted.group(1), a);
                        if (hit >= a && hit < b) {
                            a = hit;
                            b = a + quoted.group(1).length();
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
        return issues;
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
    public void apply(@NotNull PsiFile file, List<Issue> issues, @NotNull AnnotationHolder holder) {
        if (issues != null)
            for (var i : issues) {
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
                var f = PsiManager.getInstance(p).findFile(v);
                if (f != null)
                    DaemonCodeAnalyzer.getInstance(p).restart(f, "GLSL shader saved");
            }
        }
    }
}
