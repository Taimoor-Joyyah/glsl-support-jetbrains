package com.glslsupport.lang;

import java.util.*;

public final class Lexer {
    public enum Kind { SPACE, COMMENT, DIRECTIVE, WORD, NUMBER, STRING, SYMBOL, BAD }
    public record Token(Kind kind, String text, int start, int end) {
        public boolean trivia() {
            return kind == Kind.SPACE || kind == Kind.COMMENT;
        }
    }
    private static final List<String> OPERATORS =
        List.of("<<=", ">>=", "++", "--", "+=", "-=", "*=", "/=", "%=", "<=", ">=", "==", "!=", "&&", "||", "^^",
            "<<", ">>", "&=", "|=", "^=", "##");
    private Lexer() {}
    public static Token next(CharSequence text, int start, int end) {
        char c = text.charAt(start);
        int p = start + 1;
        Kind kind;
        if (Character.isWhitespace(c)) {
            kind = Kind.SPACE;
            while (p < end && Character.isWhitespace(text.charAt(p))) p++;
        } else if (c == '/' && p < end && text.charAt(p) == '/') {
            kind = Kind.COMMENT;
            while (p < end && text.charAt(p) != '\n' && text.charAt(p) != '\r') p++;
        } else if (c == '/' && p < end && text.charAt(p) == '*') {
            kind = Kind.COMMENT;
            p++;
            while (p + 1 < end && (text.charAt(p) != '*' || text.charAt(p + 1) != '/')) p++;
            p = p + 1 < end ? p + 2 : end;
        } else if (c == '#') {
            kind = Kind.DIRECTIVE;
            while (p < end) {
                char ch = text.charAt(p);
                if (ch == '\r' || ch == '\n') {
                    if (p == start || text.charAt(p - 1) != '\\')
                        break;
                    if (ch == '\r' && p + 1 < end && text.charAt(p + 1) == '\n')
                        p++;
                }
                p++;
            }
        } else if (c == '_' || Character.isLetter(c)) {
            kind = Kind.WORD;
            while (p < end && (Character.isLetterOrDigit(text.charAt(p)) || text.charAt(p) == '_')) p++;
        } else if (Character.isDigit(c) || (c == '.' && p < end && Character.isDigit(text.charAt(p)))) {
            kind = Kind.NUMBER;
            boolean hex = c == '0' && p < end && (text.charAt(p) == 'x' || text.charAt(p) == 'X');
            while (p < end) {
                char n = text.charAt(p);
                if (Character.isLetterOrDigit(n) || n == '.' || n == '_')
                    p++;
                else if ((n == '+' || n == '-') && p > start
                    && (hex ? "pP" : "eE").indexOf(text.charAt(p - 1)) >= 0)
                    p++;
                else
                    break;
            }
        } else if (c == '"' || c == '\'') {
            kind = Kind.STRING;
            while (p < end) {
                char n = text.charAt(p++);
                if (n == c || n == '\n')
                    break;
                if (n == '\\' && p < end)
                    p++;
            }
        } else {
            kind = "{}()[];:,.?+-*/%!~<>=&|^".indexOf(c) >= 0 ? Kind.SYMBOL : Kind.BAD;
            for (String op : OPERATORS) {
                if (p - 1 + op.length() <= end
                    && text.subSequence(start, start + op.length()).toString().equals(op)) {
                    p = start + op.length();
                    break;
                }
            }
        }
        return new Token(kind, text.subSequence(start, p).toString(), start, p);
    }
    public static List<Token> tokens(String text) {
        var result = new ArrayList<Token>();
        for (int p = 0; p < text.length();) {
            var t = next(text, p, text.length());
            result.add(t);
            p = t.end;
        }
        return List.copyOf(result);
    }
}
