package com.glslsupport.ide;
import com.intellij.lang.cacheBuilder.*;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.navigation.*;
import com.intellij.openapi.project.*;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.stubs.*;
import com.intellij.psi.tree.*;
import java.util.*;
import org.jetbrains.annotations.*;

public final class Navigation {
    public static final class Usages implements FindUsagesProvider {
        public WordsScanner getWordsScanner() {
            return new DefaultWordsScanner(new GlslLanguage.Adapter(), TokenSet.create(GlslLanguage.Elements.WORD),
                TokenSet.create(GlslLanguage.Elements.COMMENT), TokenSet.create(GlslLanguage.Elements.STRING));
        }
        public boolean canFindUsagesFor(@NotNull PsiElement e) {
            return e instanceof GlslLanguage.Named;
        }
        public String getHelpId(@NotNull PsiElement e) {
            return null;
        }
        public @NotNull String getType(@NotNull PsiElement e) {
            return "GLSL symbol";
        }
        public @NotNull String getDescriptiveName(@NotNull PsiElement e) {
            return e.getText();
        }
        public @NotNull String getNodeText(@NotNull PsiElement e, boolean full) {
            return e.getText();
        }
    }
    public static final class Symbols implements ChooseByNameContributor {
        static boolean extraSearch() {
            return com.intellij.openapi.application.ApplicationInfo.getInstance().getBuild().getBaselineVersion()
                >= 262;
        }
        private record ShaderItem(Project project, String name, String file, String variant)
            implements NavigationItem {
            public String getName() {
                return name;
            }
            public boolean canNavigate() {
                return true;
            }
            public boolean canNavigateToSource() {
                return true;
            }
            public com.intellij.navigation.ItemPresentation getPresentation() {
                return new com.intellij.navigation.ItemPresentation() {
                    public String getPresentableText() {
                        return name;
                    }
                    public String getLocationString() {
                        return file;
                    }
                    public javax.swing.Icon getIcon(boolean unused) {
                        return GlslLanguage.Type.INSTANCE.getIcon();
                    }
                };
            }
            public void navigate(boolean focus) {
                if (project.getBasePath() == null)
                    return;
                String local =
                    java.nio.file.Path.of(project.getBasePath()).resolve(file).toString().replace('\\', '/');
                var v = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(local);
                if (v == null)
                    return;
                if (variant != null) {
                    var s = Settings.of(project);
                    var f = s.getState().files.get(file);
                    if (f != null) {
                        f.active = variant;
                        s.changed();
                    }
                }
                new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, v).navigate(focus);
            }
        }
        private List<ShaderItem> extra(Project p) {
            if (!extraSearch())
                return List.of();
            var list = new ArrayList<ShaderItem>();
            var d = Settings.of(p).getState();
            Settings.of(p).pipelines().forEach((name, pipeline) -> {
                if (!pipeline.stages.isEmpty())
                    list.add(new ShaderItem(p, name, pipeline.stages.getFirst().file, null));
            });
            d.files.forEach(
                (file, options) -> options.variants.forEach(v -> list.add(new ShaderItem(p, v.name, file, v.id))));
            return list;
        }
        public String @NotNull[] getNames(Project p, boolean libraries) {
            var names = new LinkedHashSet<>(StubIndex.getInstance().getAllKeys(GlslLanguage.SymbolIndex.KEY, p));
            extra(p).forEach(s -> names.add(s.name));
            return names.toArray(String[] ::new);
        }
        public NavigationItem @NotNull[] getItemsByName(
            String name, String pattern, Project p, boolean libraries) {
            var items = new ArrayList<NavigationItem>(StubIndex.getElements(GlslLanguage.SymbolIndex.KEY, name, p,
                GlobalSearchScope.projectScope(p), GlslLanguage.Named.class));
            extra(p).stream().filter(i -> i.name.equals(name)).forEach(items::add);
            return items.toArray(NavigationItem[] ::new);
        }
    }
}
