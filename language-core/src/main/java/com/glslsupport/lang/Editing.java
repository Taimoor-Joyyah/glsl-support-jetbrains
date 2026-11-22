package com.glslsupport.lang;

import static com.glslsupport.lang.Syntax.Kind.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

public final class Editing {
    private static final Set<String> ASSIGNMENTS =
        Set.of("=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "^=", "|=");
    private Editing() {}
    public record Change(String file, int start, int end, String text) {}
    public record Finding(int start, int end, String message) {}
    public static String guard(String path) {
        String normalized = Workspace.path(path);
        var hash = new CRC32();
        hash.update(normalized.getBytes(StandardCharsets.UTF_8));
        String name = normalized.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return "GLSLH_" + (name.isEmpty() ? "HEADER" : name) + "_"
            + Long.toHexString(hash.getValue()).toUpperCase(Locale.ROOT);
    }
    public static String header(String path) {
        String g = guard(path);
        return "#ifndef " + g + "\n#define " + g + "\n\n#endif // " + g + "\n";
    }
    public static boolean identifier(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*") && !name.startsWith("gl_")
            && !name.contains("__") && !Catalog.has(name, "keywords") && !Catalog.has(name, "types");
    }
    public static List<Change> rename(Workspace w, Workspace.Location target, String name) {
        if (!identifier(name))
            throw new IllegalArgumentException("Choose an available GLSL identifier");
        if (target.symbol().kind() == MACRO)
            throw new IllegalArgumentException("Macro expansion requires a manual rename");
        var refs = w.usages(target);
        if (refs.isEmpty())
            throw new IllegalArgumentException("No writable declaration");
        var result = new ArrayList<Change>();
        for (var ref : refs) {
            var f = w.files.get(ref.file());
            for (var hit : w.resolve(ref.file(), ref.start()))
                if (!w.equivalent(target, hit))
                    throw new IllegalArgumentException("Ambiguous reference");
            for (String file : w.visible(ref.file()))
                for (var s : w.files.get(file).symbols) {
                    if (!s.name().equals(name))
                        continue;
                    if (target.symbol().kind() == FIELD && s.kind() == FIELD
                        && !s.owner().equals(target.symbol().owner()))
                        continue;
                    if (s.local()
                        && (!file.equals(ref.file()) || ref.start() < s.scopeStart()
                            || ref.start() >= s.scopeEnd()))
                        continue;
                    throw new IllegalArgumentException("The new name conflicts with " + s.display());
                }
            result.add(new Change(ref.file(), ref.start(), ref.end(), name));
        }
        for (var entry : w.files.entrySet()) {
            if (!w.visible(entry.getKey()).contains(target.file()))
                continue;
            for (var t : entry.getValue().code)
                if (t.kind() == Lexer.Kind.DIRECTIVE && t.text().matches("(?s)#\\s*define\\b.*")
                    && (t.text().contains("##")
                        || t.text().matches("(?s).*\\b" + target.symbol().name() + "\\b.*")))
                    throw new IllegalArgumentException("A macro may generate or reference this identifier");
        }
        return List.copyOf(result);
    }
    public static List<Change> inline(Workspace w, Workspace.Location target) {
        var s = target.symbol();
        var f = w.files.get(target.file());
        if (s.kind() != LOCAL)
            throw new IllegalArgumentException("Select a local variable");
        int at = f.indexAt(s.at());
        if (!f.word(at + 1).equals("="))
            throw new IllegalArgumentException("A single initializer is required");
        if (!Catalog.has(s.type(), "types")
            || f.symbols.stream().anyMatch(
                other -> other != s && other.start() == s.start() && other.end() == s.end()))
            throw new IllegalArgumentException("Select a single built-in typed declaration");
        int end = f.indexAt(s.end() - 1);
        if (!f.word(end).equals(";"))
            throw new IllegalArgumentException("Complete the declaration");
        for (int i = at + 2; i < end; i++) {
            String token = f.word(i);
            if (f.ident(i) && !Catalog.has(token, "types") && !Set.of("true", "false").contains(token))
                throw new IllegalArgumentException("The initializer depends on mutable state or a function call");
            if (Set.of("=", "++", "--", ",").contains(token)
                && !(token.equals(",")
                    && f.source.substring(f.code.get(at + 2).start(), f.code.get(i).start()).contains("(")))
                throw new IllegalArgumentException("The initializer must be a single constant expression");
        }
        String expr = f.source.substring(f.code.get(at + 2).start(), f.code.get(end - 1).end());
        var result = new ArrayList<Change>();
        for (var u : w.usages(target)) {
            if (u.start() == s.at())
                continue;
            int i = f.indexAt(u.start());
            if (ASSIGNMENTS.contains(f.word(i + 1)) || Set.of("++", "--", ".", "[").contains(f.word(i + 1))
                || Set.of("++", "--").contains(f.word(i - 1)))
                throw new IllegalArgumentException("The variable is modified or used as a writable value");
            for (int j = i - 1; j >= 0 && !Set.of(";", "{", "}").contains(f.word(j)); j--)
                if (f.word(j).equals("(") && f.match[j] >= i && f.ident(j - 1)
                    && !Catalog.has(f.word(j - 1), "types"))
                    throw new IllegalArgumentException("An argument may be passed as out or inout");
            result.add(new Change(target.file(), u.start(), u.end(), s.type() + "(" + expr + ")"));
        }
        result.add(new Change(target.file(), s.start(), s.end(), ""));
        return result;
    }
    public static List<Change> extract(Workspace w, String file, int start, int end, String name) {
        if (!identifier(name) || w.completions(file, start, false).contains(name))
            throw new IllegalArgumentException("Choose a fresh function name");
        var f = w.files.get(file);
        var fn = f.symbols.stream()
                     .filter(s -> s.kind() == FUNCTION && s.start() < start && s.end() > end)
                     .findFirst()
                     .orElseThrow(() -> new IllegalArgumentException("Select code inside a function"));
        String selected = f.source.substring(start, end).strip();
        if (selected.isEmpty())
            throw new IllegalArgumentException("Select an expression or complete statements");
        var vars = new LinkedHashMap<String, Syntax.Symbol>();
        var writes = new HashSet<String>();
        int first = f.indexAt(start), last = f.indexAt(end - 1);
        if (first >= f.code.size() || last >= f.code.size() || f.code.get(first).start() < start
            || f.code.get(last).end() > end)
            throw new IllegalArgumentException("Select complete tokens");
        for (int i = first; i <= last; i++) {
            String t = f.word(i);
            if (Set.of("return", "break", "continue", "discard", "if", "else", "for", "while", "do", "switch", "{",
                       "}", "++", "--")
                    .contains(t)
                || f.code.get(i).kind() == Lexer.Kind.DIRECTIVE)
                throw new IllegalArgumentException("The selection changes control flow or evaluation");
            if (f.ident(i) && f.word(i + 1).equals("(") && !Catalog.has(t, "types"))
                throw new IllegalArgumentException("A call may write through an argument");
            if (!f.ident(i))
                continue;
            var hits = w.resolve(file, f.code.get(i).start());
            if (hits.isEmpty())
                continue;
            var s = hits.get(0).symbol();
            if (!s.local())
                continue;
            if (s.at() >= start && s.at() < end)
                throw new IllegalArgumentException("Move local declarations outside the selection");
            vars.putIfAbsent(s.name(), s);
            int j = i + 1;
            while (f.word(j).equals(".") && f.ident(j + 1)) j += 2;
            if (f.word(j).equals("["))
                throw new IllegalArgumentException("Array parameters require an explicit signature");
            if (ASSIGNMENTS.contains(f.word(j)))
                writes.add(s.name());
        }
        boolean statements = selected.endsWith(";");
        String type = statements ? "void" : w.typeAt(file, last, 0);
        if (!statements
            && (type.isEmpty() || !writes.isEmpty()
                || first != last
                    && !(f.ident(first) && Catalog.has(f.word(first), "types") && f.word(first + 1).equals("(")
                        && f.match[first + 1] == last)))
            throw new IllegalArgumentException("Select a typed value, constructor, or complete statements");
        if (statements && (first == 0 || !Set.of("{", "}", ";").contains(f.word(first - 1))))
            throw new IllegalArgumentException("Select a whole statement");
        var params = vars.values()
                         .stream()
                         .map(s -> (writes.contains(s.name()) ? "inout " : "in ") + s.type() + " " + s.name())
                         .toList();
        String definition = type + " " + name + "(" + String.join(", ", params) + ") {\n    "
            + (statements ? selected : "return " + selected + ";") + "\n}\n\n";
        String call = name + "(" + String.join(", ", vars.keySet()) + ")" + (statements ? ";" : "");
        return List.of(new Change(file, start, end, call), new Change(file, fn.start(), fn.start(), definition));
    }
    public static List<Finding> inspect(Workspace w, String file) {
        var f = w.files.get(file);
        var found = new ArrayList<Finding>();
        var defined = new HashSet<String>();
        var directive = java.util.regex.Pattern.compile("#\\s*(define|undef)\\s+([A-Za-z_]\\w*)");
        for (var t : f.code)
            if (t.kind() == Lexer.Kind.DIRECTIVE && w.active(file, t.start())) {
                var m = directive.matcher(t.text());
                if (!m.lookingAt())
                    continue;
                if (m.group(1).equals("undef"))
                    defined.remove(m.group(2));
                else if (!defined.add(m.group(2)))
                    found.add(new Finding(t.start() + m.start(2), t.start() + m.end(2),
                        "Duplicate macro definition '" + m.group(2) + "'"));
            }
        for (var s : f.symbols) {
            if (s.kind() != LOCAL || !w.active(file, s.at()))
                continue;
            if (w.usages(new Workspace.Location(file, s)).size() == 1)
                found.add(new Finding(s.at(), s.at() + s.name().length(), "Unused local '" + s.name() + "'"));
            if (f.symbols.stream().anyMatch(other
                    -> other != s && other.name().equals(s.name()) && other.at() < s.at()
                        && other.scopeStart() < s.scopeStart() && other.scopeEnd() > s.at()))
                found.add(
                    new Finding(s.at(), s.at() + s.name().length(), "Declaration shadows '" + s.name() + "'"));
        }
        for (var span : unreachable(f, at -> w.active(file, at)))
            if (w.active(file, span.start()))
                found.add(new Finding(span.start(), span.end(), "Unreachable code"));
        return found;
    }

    public static List<Syntax.Span> unreachable(Syntax f) {
        var conditions = new Conditions(f, Map.of());
        return unreachable(f, conditions::active);
    }
    public static List<Syntax.Span> unreachable(Syntax f, java.util.function.IntPredicate active) {
        var ranges = new ArrayList<Syntax.Span>();
        var code = new ArrayList<Integer>();
        var position = new int[f.code.size()];
        Arrays.fill(position, -1);
        for (int i = 0; i < f.code.size(); i++) {
            var t = f.code.get(i);
            if (t.kind() == Lexer.Kind.DIRECTIVE || !active.test(t.start()))
                continue;
            position[i] = code.size();
            code.add(i);
        }
        for (int i = 1; i < code.size(); i++) {
            int token = code.get(i);
            String word = f.word(token);
            if ((word.equals("if") || word.equals("while")) && i + 4 < code.size()
                && f.word(code.get(i + 1)).equals("(") && f.word(code.get(i + 3)).equals(")")
                && f.word(code.get(i + 4)).equals("{")) {
                int open = code.get(i + 4), close = f.match[open];
                if (close > open && position[close] >= 0) {
                    if (f.word(code.get(i + 2)).equals("false"))
                        ranges.add(
                            new Syntax.Span(f.code.get(open).end(), f.code.get(close).start(), "unreachable"));
                    int after = position[close] + 1;
                    if (word.equals("if") && f.word(code.get(i + 2)).equals("true") && after + 1 < code.size()
                        && f.word(code.get(after)).equals("else") && f.word(code.get(after + 1)).equals("{")) {
                        int other = code.get(after + 1), end = f.match[other];
                        if (end > other)
                            ranges.add(
                                new Syntax.Span(f.code.get(other).end(), f.code.get(end).start(), "unreachable"));
                    }
                }
            }
            if (!Set.of("return", "break", "continue", "discard", "terminateInvocation", "terminateRayEXT",
                        "ignoreIntersectionEXT")
                    .contains(word)
                || !Set.of("{", "}", ";", ":").contains(f.word(code.get(i - 1))))
                continue;
            int end = i + 1;
            while (end < code.size() && !Set.of(";", "{", "}").contains(f.word(code.get(end)))) end++;
            if (end >= code.size() || !f.word(code.get(end)).equals(";"))
                continue;
            int first = end + 1, j = first;
            while (j < code.size() && !Set.of("}", "case", "default").contains(f.word(code.get(j)))) {
                int at = code.get(j), close = f.match[at];
                if (f.word(at).equals("{") && close > at && position[close] > j)
                    j = position[close];
                j++;
            }
            if (j > first) {
                ranges.add(new Syntax.Span(
                    f.code.get(code.get(first)).start(), f.code.get(code.get(j - 1)).end(), "unreachable"));
                i = j - 1;
            }
        }
        ranges.sort(Comparator.comparingInt(Syntax.Span::start));
        var merged = new ArrayList<Syntax.Span>();
        for (var range : ranges) {
            if (range.end() <= range.start())
                continue;
            if (!merged.isEmpty() && range.start() <= merged.getLast().end()) {
                var prior = merged.removeLast();
                merged.add(new Syntax.Span(prior.start(), Math.max(prior.end(), range.end()), "unreachable"));
            } else
                merged.add(range);
        }
        return merged;
    }
}
