package com.glslsupport.lang;

import java.util.*;

public final class Formatting {
    private Formatting() {}
    public record Edit(int start, int end, String replacement) {
        public String apply(String text) {
            return text.substring(0, start) + replacement + text.substring(end);
        }
    }
    public static Edit format(String source, int from, int to, int size, boolean tabs) {
        int start = source.lastIndexOf('\n', Math.max(-1, from - 1)) + 1;
        int end =
            to > start && to <= source.length() && source.charAt(to - 1) == '\n' ? to : source.indexOf('\n', to);
        if (end < 0)
            end = source.length();
        else if (end < source.length() && source.charAt(end) == '\n')
            end++;
        int depth = 0, paren = 0;
        String prev = "", before = "";
        boolean line = true;
        var statementBraces = new ArrayDeque<Boolean>();
        boolean closedStatement = false;
        StringBuilder out = new StringBuilder();
        String indent = tabs ? "\t" : " ".repeat(Math.clamp(size, 0, 16));
        for (var tok : Lexer.tokens(source)) {
            String s = tok.text();
            if (tok.start() >= end)
                break;
            if (tok.end() <= start) {
                if (s.equals("{"))
                    depth++;
                if (s.equals("}"))
                    depth = Math.max(0, depth - 1);
                if (s.equals("("))
                    paren++;
                if (s.equals(")"))
                    paren = Math.max(0, paren - 1);
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
                if (line)
                    out.append(indent.repeat(Math.max(0, depth)));
                else if (space(before, prev, s))
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
    private static boolean space(String before, String prev, String s) {
        if (prev.isEmpty() || Set.of(";", ",", ")", "]", ".", "++", "--").contains(s))
            return false;
        if (Set.of("(", "[", ".", "!", "~").contains(prev))
            return false;
        if (s.equals("("))
            return Set.of("if", "for", "while", "switch", "return").contains(prev);
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
