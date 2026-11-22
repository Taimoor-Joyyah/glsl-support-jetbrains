package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.codeInsight.hints.*;
import com.intellij.lang.parameterInfo.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.ElementColorProvider;
import com.intellij.psi.*;
import java.awt.Color;
import java.util.*;
import org.jetbrains.annotations.*;

public final class Calls {
    record Call(PsiElement element, int open, int index, Syntax syntax, Workspace workspace) {}
    static Call at(PsiFile file, int offset) {
        if (!(file instanceof GlslLanguage.File) || !Settings.of(file.getProject()).getState().signatures)
            return null;
        var f = ProjectAccess.model(file);
        int i = f.indexAt(Math.max(0, offset - 1));
        for (int j = Math.min(i, f.code.size() - 1); j > 0; j--) {
            if (f.word(j).equals(";") || f.word(j).equals("{"))
                break;
            if (f.word(j).equals("(") && f.ident(j - 1) && (f.match[j] < 0 || f.match[j] >= i)) {
                var e = file.findElementAt(f.code.get(j - 1).start());
                if (e == null)
                    return null;
                int arg = 0;
                for (int k = j + 1; k <= i && k < f.code.size(); k++) {
                    if (f.word(k).equals(","))
                        arg++;
                    if ((f.word(k).equals("(") || f.word(k).equals("[")) && f.match[k] > k)
                        k = f.match[k];
                }
                return new Call(e.getParent(), j, arg, f, ProjectAccess.graph(file));
            }
        }
        return null;
    }
    static List<String> signatures(Call c) {
        var text = new LinkedHashSet<String>();
        String key = ProjectAccess.key(c.element.getContainingFile()), name = c.syntax.word(c.open - 1);
        for (var k : c.workspace.visible(key))
            for (var s : c.workspace.files.get(k).symbols)
                if (s.kind() == Syntax.Kind.FUNCTION && s.name().equals(name)) {
                    var f = c.workspace.files.get(k);
                    int idx = f.indexAt(s.at()) + 1;
                    int close = f.match[idx];
                    if (close > idx)
                        text.add(s.type() + " " + name
                            + f.source.substring(f.code.get(idx).start(), f.code.get(close).end()));
                }
        for (var d : Catalog.entries(name))
            if (d.kind().equals("functions"))
                text.add(d.signature());
        return List.copyOf(text);
    }
    public static final class Parameters implements ParameterInfoHandler<PsiElement, String> {
        public PsiElement findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
            var c = at(context.getFile(), context.getOffset());
            if (c == null)
                return null;
            var options = signatures(c);
            if (options.isEmpty())
                return null;
            context.setItemsToShow(options.toArray());
            return c.element;
        }
        public void showParameterInfo(@NotNull PsiElement owner, @NotNull CreateParameterInfoContext context) {
            var c = at(context.getFile(), context.getOffset());
            if (c != null)
                context.showHint(owner, c.syntax.code.get(c.open).start(), this);
        }
        public PsiElement findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext ctx) {
            var c = at(ctx.getFile(), ctx.getOffset());
            return c == null ? null : c.element;
        }
        public void updateParameterInfo(@NotNull PsiElement owner, @NotNull UpdateParameterInfoContext ctx) {
            var c = at(ctx.getFile(), ctx.getOffset());
            if (c == null || !owner.equals(ctx.getParameterOwner()))
                ctx.removeHint();
            else
                ctx.setCurrentParameter(c.index);
        }
        public void updateUI(String signature, @NotNull ParameterInfoUIContext ctx) {
            if (signature == null)
                return;
            int a = signature.indexOf('(') + 1, b = a;
            for (int n = 0; n <= ctx.getCurrentParameterIndex(); n++) {
                if (n > 0)
                    a = b + 1;
                if (a >= signature.length())
                    break;
                b = signature.indexOf(',', a);
                if (b < 0)
                    b = signature.indexOf(')', a);
            }
            ctx.setupUIComponentPresentation(signature, Math.min(a, signature.length()), Math.max(0, b), false,
                false, false, ctx.getDefaultParameterColor());
        }
    }
    public static final class Hints implements InlayParameterHintsProvider {
        public @NotNull Set<String> getDefaultBlackList() {
            return Set.of();
        }
        public @NotNull List<InlayInfo> getParameterHints(@NotNull PsiElement element) {
            if (!(element instanceof GlslLanguage.Element)
                || element.getNode().getElementType() != GlslLanguage.Elements.REF
                || !Settings.of(element.getProject()).getState().inlays)
                return List.of();
            var file = element.getContainingFile();
            var f = ProjectAccess.model(file);
            int i = f.indexAt(element.getTextOffset()), open = i + 1;
            if (!f.word(open).equals("(") || f.match[open] <= open + 1)
                return List.of();
            var w = ProjectAccess.graph(file);
            String key = ProjectAccess.key(file);
            var hits = w.resolve(key, element.getTextOffset());
            var args = Workspace.arguments(f, open);
            List<String> names = new ArrayList<>();
            for (var hit : hits) {
                var s = hit.symbol();
                if (s.kind() == Syntax.Kind.FUNCTION) {
                    var model = w.files.get(hit.file());
                    model.symbols.stream()
                        .filter(p
                            -> p.kind() == Syntax.Kind.PARAMETER && p.at() > s.at() && p.at() < s.end()
                                && p.owner().equals(s.name()))
                        .forEach(p -> names.add(p.name()));
                    if (!names.isEmpty())
                        break;
                }
            }
            if (names.isEmpty()) {
                var value =
                    Catalog.entries(f.word(i))
                        .stream()
                        .filter(
                            e -> e.kind().equals("functions") && e.parameters().split(",").length == args.size())
                        .findFirst();
                if (value.isPresent())
                    for (String part : value.get().parameters().split(",")) {
                        String[] words = part.strip().split("\\s+");
                        names.add(words[words.length - 1]);
                    }
            }
            var hints = new ArrayList<InlayInfo>();
            int first = open + 1;
            for (int j = 0; j < Math.min(names.size(), args.size()); j++) {
                String name = names.get(j);
                if (!f.word(first).equals(name))
                    hints.add(new InlayInfo(name, f.code.get(first).start()));
                first = args.get(j) + 2;
            }
            return hints;
        }
    }
    public static final class Colors implements ElementColorProvider {
        record Literal(Syntax f, int open, int close, float[] rgb) {}
        Literal literal(PsiElement e) {
            if (!(e instanceof GlslLanguage.Element) || !Settings.of(e.getProject()).getState().colors)
                return null;
            String name = e.getText();
            if (!name.equals("vec3") && !name.equals("vec4"))
                return null;
            var f = ProjectAccess.model(e.getContainingFile());
            int i = f.indexAt(e.getTextOffset()), open = i + 1;
            if (!f.word(open).equals("(") || f.match[open] < 0)
                return null;
            int count = name.equals("vec3") ? 3 : 4;
            var args = Workspace.arguments(f, open);
            if (args.size() != count)
                return null;
            float[] v = {0, 0, 0, 1};
            for (int j = 0; j < count; j++) {
                int pos = args.get(j);
                if (pos != open + 1 + 2 * j)
                    return null;
                try {
                    v[j] = Float.parseFloat(f.word(pos));
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (!Float.isFinite(v[j]) || v[j] < 0 || v[j] > 1)
                    return null;
            }
            return new Literal(f, open, f.match[open], v);
        }
        public Color getColorFrom(@NotNull PsiElement e) {
            var l = literal(e);
            return l == null ? null : new Color(l.rgb[0], l.rgb[1], l.rgb[2], l.rgb[3]);
        }
        public void setColorTo(@NotNull PsiElement e, @NotNull Color color) {
            var l = literal(e);
            if (l == null)
                return;
            var d = PsiDocumentManager.getInstance(e.getProject()).getDocument(e.getContainingFile());
            if (d == null)
                return;
            float[] values = color.getRGBComponents(null);
            var parts = new ArrayList<String>();
            for (int n = 0; n < (e.getText().equals("vec3") ? 3 : 4); n++) parts.add(Float.toString(values[n]));
            WriteCommandAction.runWriteCommandAction(e.getProject(),
                ()
                    -> d.replaceString(
                        l.f.code.get(l.open).end(), l.f.code.get(l.close).start(), String.join(", ", parts)));
        }
    }
}
