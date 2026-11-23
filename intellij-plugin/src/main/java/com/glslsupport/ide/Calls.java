package com.glslsupport.ide;
import com.glslsupport.lang.*;
import com.intellij.codeInsight.hints.declarative.*;
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
                if (s.kind() == Syntax.Kind.FUNCTION && s.name().equals(name) && c.workspace.active(k, s.at())) {
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
    public static final class Hints implements InlayHintsProvider {
        public InlayHintsCollector createCollector(@NotNull PsiFile file, @NotNull com.intellij.openapi.editor.Editor editor) {
            if (!(file instanceof GlslLanguage.File) || !Settings.of(file.getProject()).getState().inlays) return null;
            return (SharedBypassCollector) this::collect;
        }
        private void collect(PsiElement element, InlayTreeSink sink) {
            if (!(element instanceof GlslLanguage.Element)
                || element.getNode().getElementType() != GlslLanguage.Elements.REF)
                return;
            var file = element.getContainingFile();
            var f = ProjectAccess.model(file);
            int i = f.indexAt(element.getTextOffset()), open = i + 1;
            if (!f.word(open).equals("(") || f.match[open] <= open + 1)
                return;
            var w = ProjectAccess.graph(file);
            String key = ProjectAccess.key(file);
            if (!w.active(key, element.getTextOffset())) return;
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
            int first = open + 1;
            for (int j = 0; j < Math.min(names.size(), args.size()); j++) {
                String name = names.get(j);
                if (!f.word(first).equals(name))
                    sink.addPresentation(new InlineInlayPosition(f.code.get(first).start(), false, 0),
                        null, null, HintFormat.Companion.getDefault(), tree -> {
                            tree.text(name + ":", null);
                            return kotlin.Unit.INSTANCE;
                        });
                first = args.get(j) + 2;
            }
        }
    }
    public static final class Colors implements ElementColorProvider {
        private record
            Anchor(com.intellij.openapi.project.Project project, com.intellij.openapi.editor.RangeMarker marker) {}
        private record Literal(List<Lexer.Token> channels, Color color) {}
        private static final com.intellij.openapi.util.Key<Anchor> ANCHOR =
            com.intellij.openapi.util.Key.create("glsl.colorConstructor");
        private static Lexer.Token next(CharSequence text, int from) {
            while (from < text.length()) {
                var t = Lexer.next(text, from, text.length());
                if (!t.trivia())
                    return t;
                from = t.end();
            }
            return null;
        }
        private static Literal literal(CharSequence text, int at) {
            if (at < 0 || at >= text.length())
                return null;
            var name = Lexer.next(text, at, text.length());
            int count = switch (name.text()) {
                case "vec3" -> 3;
                case "vec4" -> 4;
                default -> 0;
            };
            if (count == 0)
                return null;
            var token = next(text, name.end());
            if (token == null || !token.text().equals("("))
                return null;
            var channels = new ArrayList<Lexer.Token>(count);
            float[] rgba = {0, 0, 0, 1};
            for (int i = 0; i < count; i++) {
                var number = next(text, token.end());
                if (number == null || number.kind() != Lexer.Kind.NUMBER)
                    return null;
                try {
                    rgba[i] = Float.parseFloat(number.text());
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (!Float.isFinite(rgba[i]) || rgba[i] < 0 || rgba[i] > 1)
                    return null;
                channels.add(number);
                token = next(text, number.end());
                if (token == null || !token.text().equals(i == count - 1 ? ")" : ","))
                    return null;
            }
            return new Literal(channels, new Color(rgba[0], rgba[1], rgba[2], rgba[3]));
        }
        private static Anchor anchor(PsiElement e) {
            var saved = e.getUserData(ANCHOR);
            if (saved != null)
                return saved.marker.isValid() ? saved : null;
            if (!(e instanceof GlslLanguage.Element) || !e.isValid()
                || !Settings.of(e.getProject()).getState().colors)
                return null;
            String name = e.getText();
            if (!name.equals("vec3") && !name.equals("vec4"))
                return null;
            var d = PsiDocumentManager.getInstance(e.getProject()).getDocument(e.getContainingFile());
            int at = e.getTextOffset();
            if (d == null || literal(d.getCharsSequence(), at) == null)
                return null;
            var anchor = new Anchor(e.getProject(), d.createRangeMarker(at, at + name.length()));
            e.putUserData(ANCHOR, anchor);
            return anchor;
        }
        public Color getColorFrom(@NotNull PsiElement e) {
            var a = anchor(e);
            if (a == null)
                return null;
            var l = literal(a.marker.getDocument().getCharsSequence(), a.marker.getStartOffset());
            return l == null ? null : l.color;
        }
        public void setColorTo(@NotNull PsiElement e, @NotNull Color color) {
            var a = anchor(e);
            if (a == null || a.project.isDisposed())
                return;
            WriteCommandAction.runWriteCommandAction(a.project, "Change GLSL Color", null, () -> {
                if (!a.marker.isValid())
                    return;
                var d = a.marker.getDocument();
                var l = literal(d.getCharsSequence(), a.marker.getStartOffset());
                if (l == null)
                    return;
                var values = color.getRGBComponents(null);
                for (int i = l.channels.size() - 1; i >= 0; i--) {
                    var n = l.channels.get(i);
                    var value = Float.toString(values[i]);
                    if (!value.equals(n.text()))
                        d.replaceString(n.start(), n.end(), value);
                }
            });
        }
    }
}
