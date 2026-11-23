package com.glslsupport.lang;

import java.util.*;
import java.util.regex.*;

public final class Conditions {
    private record Branch(boolean parent, boolean taken) {}
    record Macro(List<String> parameters, String body) {
        static Macro definition(String tail) {
            if (!tail.startsWith("(")) return new Macro(null, tail.strip());
            int end = tail.indexOf(')');
            if (end < 0) return new Macro(null, "");
            String params = tail.substring(1, end).strip();
            return new Macro(params.isEmpty() ? List.of() : List.of(params.split("\\s*,\\s*")),
                tail.substring(end + 1).strip());
        }
    }
    static Map<String,Macro> macros(Map<String,String> initial) {
        var result = new HashMap<String,Macro>();
        initial.forEach((k,v) -> result.put(k, new Macro(null, v)));
        return result;
    }
    private static final Pattern DIRECTIVE = Pattern.compile("#\\s*(\\w+)\\s*(.*)", Pattern.DOTALL);
    private final List<int[]> inactive = new ArrayList<>();
    public Conditions(Syntax file, Map<String, String> initial) {
        this(file, macros(initial), i -> {});
    }
    Conditions(Syntax file, Map<String, Macro> values, java.util.function.Consumer<Syntax.Include> enter) {
        boolean overrideVersion = values.containsKey("__VERSION__");
        var branches = new ArrayDeque<Branch>();
        boolean active = true;
        int start = -1;
        int includeIndex = 0;
        for (var t : file.code) {
            if (t.kind() != Lexer.Kind.DIRECTIVE)
                continue;
            var matcher = DIRECTIVE.matcher(t.text());
            if (!matcher.matches())
                continue;
            String directive = matcher.group(1),
                   arg = matcher.group(2).replaceAll("\\\\\\R", "").strip().replaceAll("(?s)/\\*.*?\\*/", " ").split("//", 2)[0].strip();
            String name = arg.isEmpty() ? "" : arg.split("[\\s(]", 2)[0];
            switch (directive) {
                case "include" -> {
                    while (
                        includeIndex < file.includes.size() && file.includes.get(includeIndex).start() < t.end()) {
                        var include = file.includes.get(includeIndex++);
                        if (active)
                            enter.accept(include);
                    }
                }
                case "if", "ifdef", "ifndef" -> {
                    boolean cond = directive.equals("if") ? evaluate(arg, values)
                                                          : values.containsKey(name) != directive.equals("ifndef");
                    branches.push(new Branch(active, cond));
                    active = active && cond;
                }
                case "elif", "else" -> {
                    if (!branches.isEmpty()) {
                        var b = branches.pop();
                        boolean cond = !b.taken && (directive.equals("else") || evaluate(arg, values));
                        branches.push(new Branch(b.parent, b.taken || cond));
                        active = b.parent && cond;
                    }
                }
                case "endif" -> {
                    if (!branches.isEmpty())
                        active = branches.pop().parent;
                }
                case "define" -> {
                    if (active && !name.isEmpty()) {
                        values.put(name, Macro.definition(arg.substring(name.length())));
                    }
                }
                case "undef" -> {
                    if (active)
                        values.remove(name);
                }
                case "version" -> {
                    if (active && !name.isEmpty() && !overrideVersion) {
                        values.put("__VERSION__", new Macro(null, name));
                        if (name.equals("100") || arg.matches(".*\\s+es\\b.*"))
                            values.put("GL_ES", new Macro(null, "1"));
                    }
                }
            }
            if (active && start >= 0) {
                inactive.add(new int[] {start, t.start()});
                start = -1;
            } else if (!active && start < 0)
                start = t.end();
        }
        if (start >= 0)
            inactive.add(new int[] {start, file.source.length()});
    }
    public boolean active(int pos) {
        int first = 0, end = inactive.size();
        while (first < end) {
            int mid = (first + end) >>> 1;
            var range = inactive.get(mid);
            if (pos < range[0])
                end = mid;
            else if (pos >= range[1])
                first = mid + 1;
            else
                return false;
        }
        return true;
    }
    public List<int[]> ranges() {
        return List.copyOf(inactive);
    }
    private static boolean evaluate(String expr, Map<String, Macro> values) {
        try {
            var expanded = new Expansion(values).expand(tokens(expr), Set.of(), 0);
            var evaluator = new Expr(expanded);
            long value = evaluator.conditional();
            return evaluator.at == expanded.size() && value != 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    private static List<String> tokens(String expression) {
        return Lexer.tokens(expression).stream().filter(t -> !t.trivia()).map(Lexer.Token::text).toList();
    }
    private static final class Expansion {
        final Map<String,Macro> definitions;
        int budget = 16384;
        Expansion(Map<String,Macro> definitions) { this.definitions = definitions; }
        List<String> expand(List<String> input, Set<String> hidden, int depth) {
            if (depth > 64) throw new IllegalArgumentException("Nested macro expansion");
            var out = new ArrayList<String>();
            for (int i = 0; i < input.size(); i++) {
                if (--budget < 0) throw new IllegalArgumentException("Macro expansion limit");
                String name = input.get(i);
                if (name.equals("defined")) {
                    boolean parens = input.get(++i).equals("(");
                    if (parens) i++;
                    String tested = input.get(i);
                    if (parens && !input.get(++i).equals(")")) throw new IllegalArgumentException();
                    out.add(definitions.containsKey(tested) ? "1" : "0");
                    continue;
                }
                var macro = definitions.get(name);
                if (macro == null || hidden.contains(name) || macro.parameters() != null &&
                    (i + 1 == input.size() || !input.get(i + 1).equals("("))) {
                    out.add(name);
                    continue;
                }
                var body = tokens(macro.body());
                if (macro.parameters() != null) {
                    var args = new ArrayList<List<String>>();
                    int start = i + 2, nesting = 0;
                    i += 2;
                    for (; i < input.size(); i++) {
                        String token = input.get(i);
                        if (nesting == 0 && (token.equals(",") || token.equals(")"))) {
                            if (start < i || !macro.parameters().isEmpty()) args.add(input.subList(start, i));
                            if (token.equals(")")) break;
                            start = i + 1;
                        } else if (token.equals("(")) nesting++;
                        else if (token.equals(")")) nesting--;
                    }
                    if (i == input.size() || args.size() != macro.parameters().size())
                        throw new IllegalArgumentException("Incomplete macro call");
                    var substitution = new ArrayList<String>();
                    for (String token : body) {
                        int parameter = macro.parameters().indexOf(token);
                        if (parameter < 0) substitution.add(token);
                        else substitution.addAll(expand(args.get(parameter), hidden, depth + 1));
                    }
                    body = substitution;
                }
                var next = new HashSet<>(hidden);
                next.add(name);
                out.addAll(expand(body, next, depth + 1));
            }
            return out;
        }
    }
    private static final class Expr {
        final List<String> t;
        int at;
        int nesting;
        static final List<List<String>> ops = List.of(List.of("||"), List.of("^^"), List.of("&&"), List.of("|"),
            List.of("^"), List.of("&"), List.of("==", "!="), List.of("<", ">", "<=", ">="), List.of("<<", ">>"),
            List.of("+", "-"), List.of("*", "/", "%"));
        Expr(List<String> tokens) { t = tokens; }
        long conditional() {
            if (++nesting > 64) throw new IllegalArgumentException("Nested conditional");
            long value = expression(0);
            if (peek().equals("?")) {
                at++;
                long yes = conditional();
                if (!t.get(at++).equals(":")) throw new IllegalArgumentException();
                long no = conditional();
                value = value != 0 ? yes : no;
            }
            nesting--;
            return value;
        }
        String peek() {
            return at < t.size() ? t.get(at) : "";
        }
        long expression(int level) {
            if (level == ops.size())
                return atom();
            long v = expression(level + 1);
            while (ops.get(level).contains(peek())) {
                String op = t.get(at++);
                long b = expression(level + 1);
                v = switch (op) {
                    case "||" -> v != 0 || b != 0 ? 1 : 0;
                    case "^^" -> (v != 0) ^ (b != 0) ? 1 : 0;
                    case "&&" -> v != 0 && b != 0 ? 1 : 0;
                    case "|" -> v | b;
                    case "^" -> v ^ b;
                    case "&" -> v & b;
                    case "==" -> v == b ? 1 : 0;
                    case "!=" -> v != b ? 1 : 0;
                    case "<" -> v < b ? 1 : 0;
                    case ">" -> v > b ? 1 : 0;
                    case "<=" -> v <= b ? 1 : 0;
                    case ">=" -> v >= b ? 1 : 0;
                    case "<<" -> v << b;
                    case ">>" -> v >> b;
                    case "+" -> v + b;
                    case "-" -> v - b;
                    case "*" -> v* b;
                    case "/" -> b == 0 ? 0 : v / b;
                    default -> b == 0 ? 0 : v % b;
                };
            }
            return v;
        }
        long atom() {
            int start = at;
            while (List.of("!", "~", "-", "+").contains(peek())) at++;
            int end = at;
            String s = t.get(at++);
            long value;
            if (s.equals("(")) {
                value = conditional();
                if (!t.get(at++).equals(")")) throw new IllegalArgumentException();
            } else
                value = Character.isDigit(s.charAt(0)) ? Long.decode(s.replaceAll("[uUlL]+$", "")) : 0;
            while (end > start)
                value = switch (t.get(--end)) {
                    case "!" -> value == 0 ? 1 : 0;
                    case "~" -> ~value;
                    case "-" -> -value;
                    default -> value;
                };
            return value;
        }
    }
}
