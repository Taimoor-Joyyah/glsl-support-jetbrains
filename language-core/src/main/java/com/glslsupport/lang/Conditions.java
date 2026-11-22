package com.glslsupport.lang;

import java.util.*;
import java.util.regex.*;

public final class Conditions {
    private record Branch(boolean parent, boolean taken) {}
    private static final Pattern DIRECTIVE = Pattern.compile("#\\s*(\\w+)\\s*(.*)", Pattern.DOTALL);
    private final List<int[]> inactive = new ArrayList<>();
    public Conditions(Syntax file, Map<String, String> initial) {
        this(file, new HashMap<>(initial), i -> {});
    }
    Conditions(Syntax file, Map<String, String> values, java.util.function.Consumer<Syntax.Include> enter) {
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
                   arg = matcher.group(2).strip().replaceAll("(?s)/\\*.*?\\*/", " ").split("//", 2)[0].strip();
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
                        String v = arg.substring(name.length()).strip();
                        values.put(name, v.isEmpty() ? "1" : v);
                    }
                }
                case "undef" -> {
                    if (active)
                        values.remove(name);
                }
                case "version" -> {
                    if (active && !name.isEmpty() && !overrideVersion) {
                        values.put("__VERSION__", name);
                        if (name.equals("100") || arg.matches(".*\\s+es\\b.*"))
                            values.put("GL_ES", "1");
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
    static boolean evaluate(String expr, Map<String, String> values) {
        try {
            return new Expr(expr, values, 0).expression(0) != 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    private static final class Expr {
        final List<String> t;
        final Map<String, String> defs;
        int at;
        final int depth;
        static final List<List<String>> ops = List.of(List.of("||"), List.of("^^"), List.of("&&"), List.of("|"),
            List.of("^"), List.of("&"), List.of("==", "!="), List.of("<", ">", "<=", ">="), List.of("<<", ">>"),
            List.of("+", "-"), List.of("*", "/", "%"));
        Expr(String value, Map<String, String> defs, int depth) {
            this.t = Lexer.tokens(value).stream().filter(k -> !k.trivia()).map(Lexer.Token::text).toList();
            this.defs = defs;
            this.depth = depth;
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
            String s = t.get(at++);
            if (s.equals("(")) {
                long n = expression(0);
                if (peek().equals(")"))
                    at++;
                return n;
            }
            if (s.equals("!"))
                return atom() == 0 ? 1 : 0;
            if (s.equals("~"))
                return ~atom();
            if (s.equals("-"))
                return -atom();
            if (s.equals("+"))
                return atom();
            if (s.equals("defined")) {
                boolean paren = peek().equals("(");
                if (paren)
                    at++;
                boolean has = defs.containsKey(t.get(at++));
                if (paren && peek().equals(")"))
                    at++;
                return has ? 1 : 0;
            }
            if (Character.isDigit(s.charAt(0)))
                return Long.decode(s.replaceAll("[uUlL]+$", ""));
            String value = defs.get(s);
            return value == null || depth >= 16 ? 0 : new Expr(value, defs, depth + 1).expression(0);
        }
    }
}
