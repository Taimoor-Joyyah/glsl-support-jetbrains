package com.glslsupport.tests;
import com.glslsupport.ide.GlslLanguage;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.*;
import com.intellij.platform.backend.observation.Observation;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.refactoring.rename.RenameProcessor;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

public final class IdeSmoke implements ApplicationStarter {
    @Override
    public boolean isHeadless() {
        return !Boolean.getBoolean("glsl.test.docsWindow");
    }
    @Override
    public int getRequiredModality() {
        return ApplicationStarter.NOT_IN_EDT;
    }
    static void check(boolean ok, String msg) {
        if (!ok)
            throw new AssertionError(msg);
    }
    static <T> T onEdt(Supplier<T> action) {
        var result = new AtomicReference<T>();
        ApplicationManager.getApplication().invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }
    static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }
    static void awaitProjectConfiguration(Project project) throws InterruptedException {
        // Smart mode can precede a product's first asynchronous project-model import.
        BuildersKt.<Boolean>runBlocking(EmptyCoroutineContext.INSTANCE,
            (scope, continuation)
                -> Observation.INSTANCE.awaitConfiguration(project, message -> Unit.INSTANCE, continuation));
        DumbService.getInstance(project).waitForSmartMode();
    }
    @Override
    public void main(List<String> args) {
        Path result = Path.of(System.getProperty("glsl.test.result"));
        try {
            Path fixtures = Path.of(System.getProperty("glsl.test.khronos"));
            var cache = new com.glslsupport.reference.ReferenceLibrary(PathManager.getSystemDir().resolve("glsl-support/reference"),
                (uri, limit) -> Files.readAllBytes(fixtures.resolve(Path.of(uri.getPath()).getFileName())));
            for (var name : List.of("glGet", "glBindTexture", "glUniform", "glDrawArrays", "glPolygonOffsetClamp"))
                cache.get(cache.filename(name));
            Path root = Path.of(System.getProperty("glsl.test.project"));
            Project p = Objects.requireNonNull(ProjectUtil.openOrImport(root));
            var fs = LocalFileSystem.getInstance();
            var h = fs.refreshAndFindFileByNioFile(root.resolve("common.glslh"));
            var main = fs.refreshAndFindFileByNioFile(root.resolve("scene.vert"));
            awaitProjectConfiguration(p);
            if (!isHeadless()) {
                ReferenceWindow.verify(p, root, true);
                EditorUpgrade.liveSwizzle(p, root);
                PipelineEditing.verify(p, root, true);
                Files.writeString(result,
                    "PASS OpenGL Docs tool window, manual search, rendered Chromium pages, pinned results and "
                        + "editor tracking, live swizzle correction, visible pipeline selector, declarative argument hints, "
                        + "batch validation, trace comparison and byte-for-byte SPIR-V export\n");
                exitGraphical(0);
                return;
            }
            var target = ReadAction.compute(() -> {
                var header = Objects.requireNonNull(PsiManager.getInstance(p).findFile(h));
                var shader = Objects.requireNonNull(PsiManager.getInstance(p).findFile(main));
                check(header instanceof GlslLanguage.File, "GLSL file type");
                var leaf = header.findElementAt(header.getText().indexOf("value"));
                var decl = (GlslLanguage.Named) leaf.getParent();
                var ref = shader.findReferenceAt(shader.getText().lastIndexOf("value"));
                check(ref != null && ref.resolve() == decl, "resolve unsaved/closed include reference");
                var found = StubIndex.getElements(GlslLanguage.SymbolIndex.KEY, "value", p,
                    GlobalSearchScope.projectScope(p), GlslLanguage.Named.class);
                check(!found.isEmpty(), "Go to Symbol index");
                check(ReferencesSearch.search(decl).findAll().size() == 1, "Find Usages");
                return decl;
            });
            ApplicationManager.getApplication().invokeAndWait(
                () -> new RenameProcessor(p, target, "gain", false, false).run());
            ReadAction.run(() -> {
                var header = PsiManager.getInstance(p).findFile(h);
                var shader = PsiManager.getInstance(p).findFile(main);
                check(header.getText().contains("gain") && shader.getText().contains("gain"), "multi-file Rename");
            });
            Features.verify(p, root);
            EditorUpgrade.verify(p, root);
            ReferenceWindow.verify(p, root, false);
            PipelineEditing.verify(p, root, false);
            Files.writeString(result,
                "PASS file type, include navigation, symbol index, Find Usages, project Rename, native DLL, "
                    + "SPIR-V, source map, block and struct layout, pipeline link, variants, gated search, "
                    + "folding, "
                    + "structure, documentation, colors, actions, settings, header template, IDE formatting, "
                    + "semantic palette, live swizzles, repeated color edits, cross-language references and "
                    + "pipeline XML, manual reference search, rendered Swing pages, pipeline contexts, clickable batch validation, trace comparison and export action\n");
            System.exit(0);
        } catch (Throwable t) {
            try {
                var text = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(text));
                Files.writeString(result, text.toString());
            } catch (Exception ignored) {
            }
            if (isHeadless()) System.exit(1);
            else exitGraphical(1);
        }
    }
    private static void exitGraphical(int code) {
        var app = ApplicationManager.getApplication();
        app.invokeLater(() -> app.exit(true, true, false, code), ModalityState.any());
    }
}
