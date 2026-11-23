package com.glslsupport.ide;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.*;
import com.intellij.openapi.vfs.newvfs.events.*;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

final class ShaderChanges {
    private ShaderChanges() {}
    static void watch(Project project, Disposable parent, Supplier<Set<String>> paths,
                      boolean followsEditorContext, Runnable changed) {
        var settings = Settings.of(project).changes;
        var contexts = PipelineContexts.of(project);
        project.getMessageBus().connect(parent).subscribe(PipelineContexts.CHANGED, new PipelineContexts.Listener() {
            long revision = settings.getModificationCount();
            public void contextsChanged() {
                long current = settings.getModificationCount();
                if (followsEditorContext || revision != current) changed.run();
                revision = current;
            }
        });
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            public void documentChanged(@NotNull DocumentEvent event) {
                var file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null && (paths.get().contains(file.getPath()) ||
                    followsEditorContext && contexts.configuredInputs().contains(file.getPath())))
                    changed.run();
            }
        }, parent);
        ApplicationManager.getApplication().getMessageBus().connect(parent).subscribe(
            VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                public void after(@NotNull List<? extends VFileEvent> events) {
                    for (var event : events) {
                        var file = event.getFile();
                        boolean directory = file != null && file.isDirectory();
                        var changedPaths = event instanceof VFileMoveEvent move ? List.of(move.getOldPath(), move.getNewPath())
                            : event instanceof VFilePropertyChangeEvent rename && VirtualFile.PROP_NAME.equals(rename.getPropertyName())
                                ? List.of(rename.getOldPath(), rename.getNewPath()) : List.of(event.getPath());
                        if (changedPaths.stream().anyMatch(path -> affects(paths.get(), path, directory) ||
                            followsEditorContext && affects(contexts.configuredInputs(), path, directory))) {
                            changed.run();
                            break;
                        }
                    }
                }
            });
    }
    private static boolean affects(Set<String> inputs, String path, boolean directory) {
        return inputs.contains(path) || directory && inputs.stream().anyMatch(p -> p.startsWith(path + "/"));
    }
}
