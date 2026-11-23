package com.glslsupport.lang;

import java.util.*;

public final class Formatting {
    private Formatting() {}
    private static final Set<String> ASSIGNMENTS =
        Set.of("=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=");
    public record Style(int indentSize, boolean tabs, boolean spaceAfterComma, boolean spaceAroundAssignment,
                        Set<String> spacedControls) {
        public Style {
            spacedControls = Set.copyOf(spacedControls);
        }
        public static Style defaults(int size, boolean tabs) {
            return new Style(size, tabs, true, true, Set.of("if", "for", "while", "switch"));
        }
    }
    public record Edit(int start, int end, String replacement) {
        public String apply(String text) {
            return text.substring(0, start) + replacement + text.substring(end);
        }
    }
    public static Edit format(String source, int from, int to, int size, boolean tabs) {
        return format(source, from, to, Style.defaults(size, tabs));
    }
    public static Edit format(String source, int from, int to, Style style) {
        from = Math.clamp(from, 0, source.length());
        to = Math.clamp(to, from, source.length());
        int start = source.lastIndexOf('\n', Math.max(-1, from - 1)) + 1;
        int end = to;
        if (to <= start || source.charAt(to - 1) != '\n') {
            int newline = source.indexOf('\n', to);
            end = newline < 0 ? source.length() : newline + 1;
        }
        int depth = 0, paren = 0;
        String prev = "", before = "";
        boolean line = true;
        var statementBraces = new ArrayDeque<Boolean>();
        boolean closedStatement = false;
        StringBuilder out = new StringBuilder();
        int indentWidth = style.tabs() ? 1 : Math.max(0, style.indentSize());
        long outputBudget = Math.max(256 * 1024L, 32L * (end - start));
        for (var tok : Lexer.tokens(source)) {
            String s = tok.text();
            if (tok.start() >= end)
                break;
            if (tok.end() <= start) {
                if (tok.trivia())
                    continue;
                if (s.equals("{")) {
                    depth++;
                    statementBraces.push(Set.of(")", "else", "do", "{", ";", "}").contains(prev));
                }
                if (s.equals("}")) {
                    depth = Math.max(0, depth - 1);
                    closedStatement = !statementBraces.isEmpty() && statementBraces.pop();
                }
                if (s.equals("("))
                    paren++;
                if (s.equals(")"))
                    paren = Math.max(0, paren - 1);
                before = prev;
                prev = s;
                continue;
            }
            if (tok.start() < start || tok.end() > end) {
                if (tok.kind() != Lexer.Kind.SPACE)
                    return new Edit(start, end, source.substring(start, end));
            }
            if (tok.kind() == Lexer.Kind.SPACE) {
                String part = source.substring(Math.max(start, tok.start()), Math.min(end, tok.end()));
                if (part.contains("\n") && !line) {
                    out.append('\n');
                    line = true;
                }
                if (part.indexOf('\n') != part.lastIndexOf('\n') && out.length() > 0
                    && (out.length() < 2 || out.charAt(out.length() - 2) != '\n'))
                    out.append('\n');
                continue;
            }
            if (s.equals("}")) {
                depth = Math.max(0, depth - 1);
                if (!line && !prev.equals("{")) {
                    out.append('\n');
                    line = true;
                }
            }
            if (!line
                && ((prev.equals("{") && !s.equals("}"))
                    || (prev.equals(";") && paren == 0 && tok.kind() != Lexer.Kind.COMMENT)
                    || (prev.equals("}") && closedStatement
                        && !Set.of(";", ",", "else", "while", ")", "]").contains(s)
                        && tok.kind() != Lexer.Kind.COMMENT))) {
                out.append('\n');
                line = true;
            }
            if (tok.kind() == Lexer.Kind.DIRECTIVE) {
                if (!line)
                    out.append('\n');
                out.append(s);
                line = false;
            } else {
                if (line) {
                    long width = (long) indentWidth * depth;
                    // Incomplete or generated buffers can otherwise expand quadratically with nesting.
                    if (out.length() + width > outputBudget)
                        return new Edit(start, end, source.substring(start, end));
                    out.repeat(style.tabs() ? '\t' : ' ', (int) width);
                }
                else if (space(before, prev, s, style))
                    out.append(' ');
                out.append(s);
                line = false;
            }
            if (s.equals("{")) {
                depth++;
                statementBraces.push(Set.of(")", "else", "do", "{", ";", "}").contains(prev));
            }
            if (s.equals("}"))
                closedStatement = !statementBraces.isEmpty() && statementBraces.pop();
            if (s.equals("("))
                paren++;
            if (s.equals(")"))
                paren = Math.max(0, paren - 1);
            before = prev;
            prev = s;
        }
        String formatted = out.toString();
        if (source.substring(start, end).endsWith("\n") && !formatted.endsWith("\n"))
            formatted += "\n";
        if (source.contains("\r\n"))
            formatted = formatted.replace("\r\n", "\n").replace("\n", "\r\n");
        return new Edit(start, end, formatted);
    }
    private static boolean space(String before, String prev, String s, Style style) {
        // Removing this separator would create a new operator or a comment.
        if ((prev.endsWith("+") && s.startsWith("+")) || (prev.endsWith("-") && s.startsWith("-"))
            || (prev.equals("/") && (s.startsWith("/") || s.startsWith("*"))))
            return true;
        if (s.equals("++") || s.equals("--"))
            return prev.equals(";") || prev.equals(",") && style.spaceAfterComma()
                || ASSIGNMENTS.contains(prev) && style.spaceAroundAssignment();
        if (prev.isEmpty() || Set.of(";", ",", ")", "]", ".").contains(s))
            return false;
        if (Set.of("(", "[", ".", "!", "~").contains(prev))
            return false;
        if (prev.equals(","))
            return style.spaceAfterComma();
        if (ASSIGNMENTS.contains(prev) || ASSIGNMENTS.contains(s))
            return style.spaceAroundAssignment();
        if (s.equals("("))
            return prev.equals("return") || style.spacedControls().contains(prev);
        if (s.equals("["))
            return false;
        if (prev.equals("{") && s.equals("}"))
            return false;
        if ((prev.equals("+") || prev.equals("-") || prev.equals("++") || prev.equals("--"))
            && (before.isEmpty()
                || Set.of("(", "[", "=", ",", "return", "+", "-", "/", "*", ";", "{").contains(before)))
            return false;
        return true;
    }
}
