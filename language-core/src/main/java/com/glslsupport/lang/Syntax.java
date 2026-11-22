package com.glslsupport.lang;

import static com.glslsupport.lang.Lexer.Kind.*;

import java.util.*;
import java.util.regex.*;

public final class Syntax {
    public enum Kind { FUNCTION, TYPE, BLOCK, FIELD, PARAMETER, LOCAL, GLOBAL, MACRO }
    public record Symbol(Kind kind, String name, String type, int at, int start, int end, int scopeStart,
        int scopeEnd, String owner, List<String> parameters) {
        public boolean local() {
            return kind == Kind.LOCAL || kind == Kind.PARAMETER;
        }
        public String display() {
            return kind == Kind.FUNCTION ? type + " " + name + "(" + String.join(", ", parameters) + ")"
                                         : type + " " + name;
        }
    }
    public record Span(int start, int end, String kind) {}
    public record Include(String path, int start, int end, boolean system) {}
    public static final Set<String> QUALIFIERS = Set.of("const", "in", "out", "inout", "uniform", "buffer",
        "shared", "attribute", "varying", "coherent", "volatile", "restrict", "readonly", "writeonly", "flat",
        "smooth", "noperspective", "centroid", "sample", "patch", "precise", "invariant", "highp", "mediump",
        "lowp", "perprimitiveEXT", "taskPayloadSharedEXT", "rayPayloadEXT", "rayPayloadInEXT", "hitAttributeEXT",
        "callableDataEXT", "callableDataInEXT", "subroutine");
    private static final Set<String> CONTROL = Set.of("if", "else", "while", "do", "for", "switch", "case",
        "default", "return", "break", "continue", "discard", "precision", "layout", "true", "false");
    private static final Pattern INCLUDE = Pattern.compile("^#\\s*include\\s*([<\"])([^>\"\\r\\n]+)[>\"]");
    private static final Pattern DEFINE = Pattern.compile("^#\\s*define\\s+([A-Za-z_]\\w*)");
    public final String source;
    public final List<Lexer.Token> tokens, code;
    public final List<Symbol> symbols = new ArrayList<>();
    public final List<Span> spans = new ArrayList<>();
    public final List<Include> includes = new ArrayList<>();
    public final int[] match;
    private final Map<String, List<Symbol>> byName = new HashMap<>();
    public List<Symbol> named(String name) {
        return byName.getOrDefault(name, List.of());
    }
    private final Map<Integer, Symbol> declarations = new HashMap<>();
    public Syntax(String source) {
        this.source = source;
        tokens = Lexer.tokens(source);
        code = tokens.stream().filter(t -> !t.trivia()).toList();
        match = new int[code.size()];
        Arrays.fill(match, -1);
        var opens = new ArrayDeque<Integer>();
        var conditionals = new ArrayDeque<Integer>();
        for (int i = 0; i < code.size(); i++) {
            var t = code.get(i);
            String v = t.text();
            if (v.equals("(") || v.equals("{") || v.equals("[")) {
                opens.push(i);
            } else if (v.equals(")") || v.equals("}") || v.equals("]")) {
                String expected = v.equals(")") ? "(" : v.equals("}") ? "{" : "[";
                if (!opens.isEmpty() && word(opens.peek()).equals(expected)) {
                    int n = opens.pop();
                    match[n] = i;
                    match[i] = n;
                    if (expected.equals("{"))
                        spans.add(new Span(code.get(n).start(), t.end(), "block"));
                }
            } else if (t.kind() == DIRECTIVE) {
                var inc = INCLUDE.matcher(v);
                if (inc.find())
                    includes.add(new Include(
                        inc.group(2), t.start() + inc.start(2), t.start() + inc.end(2), inc.group(1).equals("<")));
                var d = DEFINE.matcher(v);
                if (d.find())
                    add(new Symbol(Kind.MACRO, d.group(1), "#define", t.start() + d.start(1), t.start(), t.end(),
                        0, source.length(), "", List.of()));
                if (v.matches("(?s)#\\s*(if|ifdef|ifndef|region)\\b.*"))
                    conditionals.push(t.start());
                else if (v.matches("(?s)#\\s*(endif|endregion)\\b.*") && !conditionals.isEmpty())
                    spans.add(new Span(conditionals.pop(), t.end(), "directive"));
            }
        }
        parse(0, code.size(), 0, source.length(), "", false);
        for (var t : tokens)
            if (t.kind() == COMMENT && t.text().contains("\n"))
                spans.add(new Span(t.start(), t.end(), "comment"));
        symbols.sort(Comparator.comparingInt(Symbol::at));
    }
    public boolean hasVersion() {
        return code.stream().anyMatch(t -> t.kind() == DIRECTIVE && t.text().matches("(?s)#\\s*version\\b.*"));
    }
    public Symbol declaration(int offset) {
        return declarations.get(offset);
    }
    public int indexAt(int offset) {
        int l = 0, h = code.size();
        while (l < h) {
            int m = (l + h) >>> 1;
            if (code.get(m).end() <= offset)
                l = m + 1;
            else
                h = m;
        }
        return l;
    }
    public String word(int i) {
        return i >= 0 && i < code.size() ? code.get(i).text() : "";
    }
    public boolean ident(int i) {
        return i >= 0 && i < code.size() && code.get(i).kind() == WORD;
    }
    private void add(Symbol s) {
        symbols.add(s);
        byName.computeIfAbsent(s.name(), key -> new ArrayList<>()).add(s);
        declarations.put(s.at, s);
    }
    private int afterPair(int i, int end) {
        return match[i] > i ? Math.min(end, match[i] + 1) : end;
    }
    private int fence(int first, int end) {
        for (int i = first; i < end; i++) {
            String v = word(i);
            if (v.equals(";") || v.equals("{") || v.equals("}"))
                return i;
            if ((v.equals("(") || v.equals("[")) && match[i] > i)
                i = match[i];
            if (i > first && code.get(i).kind() == DIRECTIVE)
                return i;
        }
        return end;
    }
    private void parse(int first, int end, int scopeStart, int scopeEnd, String owner, boolean inFunction) {
        int i = first;
        while (i < end) {
            int start = i;
            String v = word(i);
            if (code.get(i).kind() == DIRECTIVE || v.equals(";") || v.equals("}")) {
                i++;
                continue;
            }
            if (v.equals("{")) {
                int stop = match[i] > i ? Math.min(match[i], end) : end;
                parse(i + 1, stop, code.get(i).start(), stop < end ? code.get(stop).end() : scopeEnd, owner,
                    inFunction);
                i = stop + 1;
                continue;
            }
            if (v.equals("for") && word(i + 1).equals("(") && match[i + 1] > i + 1) {
                int close = match[i + 1], sem = fence(i + 2, close);
                int bodyEnd = word(close + 1).equals("{") && match[close + 1] > 0
                    ? match[close + 1]
                    : Math.min(fence(close + 1, end), end - 1);
                if (sem < close)
                    parse(i + 2, sem + 1, code.get(i).start(), code.get(Math.max(close, bodyEnd)).end(), owner,
                        true);
                i = close + 1;
                continue;
            }
            if (v.equals("layout") && word(i + 1).equals("(")) {
                i = afterPair(i + 1, end);
                continue;
            }
            while (i < end && QUALIFIERS.contains(word(i))) i++;
            if (i >= end)
                break;
            if (word(i).equals("struct") || (!inFunction && ident(i) && word(i + 1).equals("{"))) {
                boolean struct = word(i).equals("struct");
                int name = struct ? i + 1 : i, brace = name + 1;
                if (ident(name) && word(brace).equals("{")) {
                    int close = match[brace] > brace ? Math.min(match[brace], end) : end;
                    int stop = close < end ? fence(close + 1, end) : end;
                    int finish = stop < end ? code.get(stop).end() : scopeEnd;
                    var id = code.get(name);
                    add(new Symbol(struct ? Kind.TYPE : Kind.BLOCK, id.text(), struct ? "struct" : "block",
                        id.start(), code.get(start).start(), finish, scopeStart, scopeEnd, "", List.of()));
                    parse(brace + 1, close, code.get(brace).start(),
                        close < end ? code.get(close).end() : scopeEnd, id.text(), false);
                    if (close + 1 < end && ident(close + 1))
                        variables(close + 1, stop, id.text(), start, scopeStart, scopeEnd, owner, inFunction);
                    i = stop + 1;
                    continue;
                }
            }
            if (ident(i) && !CONTROL.contains(word(i)) && ident(i + 1)) {
                String type = word(i);
                int name = i + 1;
                if (word(name + 1).equals("(")) {
                    int open = name + 1, close = match[open];
                    if (close < open || close >= end) {
                        i = open + 1;
                        continue;
                    }
                    int body = close + 1;
                    boolean definition = word(body).equals("{");
                    int stop = definition ? (match[body] > body ? Math.min(match[body], end) : end)
                                          : Math.min(body, end - 1);
                    int finish = stop < end ? code.get(stop).end() : scopeEnd;
                    var types = new ArrayList<String>();
                    var args = new ArrayList<int[]>();
                    int part = open + 1;
                    for (int j = part; j <= close; j++) {
                        if (j < close && !word(j).equals(","))
                            continue;
                        int k = part;
                        while (k < j && QUALIFIERS.contains(word(k))) k++;
                        if (k < j && !word(k).equals("void")) {
                            types.add(word(k));
                            if (k + 1 < j && ident(k + 1))
                                args.add(new int[] {k, k + 1});
                        }
                        part = j + 1;
                    }
                    var id = code.get(name);
                    add(new Symbol(Kind.FUNCTION, id.text(), type, id.start(), code.get(start).start(), finish, 0,
                        source.length(), "", List.copyOf(types)));
                    if (definition) {
                        for (var arg : args) {
                            var n = code.get(arg[1]);
                            add(new Symbol(Kind.PARAMETER, n.text(), word(arg[0]), n.start(),
                                code.get(arg[0]).start(), n.end(), code.get(open).start(), finish, id.text(),
                                List.of()));
                        }
                        parse(body + 1, stop, code.get(body).start(), finish, id.text(), true);
                    }
                    i = stop + 1;
                    continue;
                }
                int stop = fence(name + 1, end);
                variables(name, stop, type, start, scopeStart, scopeEnd, owner, inFunction);
                i = stop;
                continue;
            }
            int stop = fence(i, end);
            i = stop > i ? stop : i + 1;
        }
    }
    private void variables(
        int name, int stop, String type, int start, int scopeStart, int scopeEnd, String owner, boolean local) {
        for (int i = name; i < stop; i++) {
            if (i == name || word(i - 1).equals(",")) {
                if (ident(i)) {
                    var n = code.get(i);
                    int finish = stop < code.size() ? code.get(stop).end() : source.length();
                    add(new Symbol(local      ? Kind.LOCAL
                            : owner.isEmpty() ? Kind.GLOBAL
                                              : Kind.FIELD,
                        n.text(), type, n.start(), code.get(start).start(), finish, scopeStart, scopeEnd, owner,
                        List.of()));
                }
            }
            if ((word(i).equals("(") || word(i).equals("[")) && match[i] > i)
                i = match[i];
        }
    }
}
