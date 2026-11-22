package com.glslsupport.ide;

import com.glslsupport.lang.Lexer;
import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import java.util.*;
import java.util.regex.*;
import org.jetbrains.annotations.NotNull;

/** The parser keeps a directive intact. The editor colors its operands independently. */
final class ColorLexer extends LexerBase {
    record Part(int from, int to, ShaderPalette color) {}
    private static final Pattern DOC_DETAIL =
        Pattern.compile("(?<block>\\x60{3}[\\s\\S]*?(?:\\x60{3}|$))|(?<markup></"
            + "?[A-Za-z][^>\\n]*>)|(?<inline>\\x60[^\\x60\\n]+\\x60)|(?<tag>@\\w+|\\\\\\w+)(?:[ "
            + "\\t]+(?<arg>[A-Za-z_]\\w*))?");
    static List<Part> parts(Lexer.Token token) {
        String s = token.text();
        int base = token.start(), end = s.length();
        var list = new ArrayList<Part>();
        var role = ShaderPalette.lexical(token);
        if (token.kind() == Lexer.Kind.DIRECTIVE) {
            int i = 1;
            while (i < end && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            int start = i;
            while (i < end && Character.isJavaIdentifierPart(s.charAt(i))) i++;
            String directive = s.substring(start, i);
            list.add(new Part(base, base + i, ShaderPalette.DIRECTIVE));
            boolean first = true, functionMacro = false;
            int paramsEnd = -1;
            var parameters = new HashSet<String>();
            while (i < end) {
                if (s.charAt(i) == '#') {
                    int stop = i + 1 < end && s.charAt(i + 1) == '#' ? i + 2 : i + 1;
                    list.add(new Part(base + i, base + stop, ShaderPalette.MACRO_OPERATOR));
                    i = stop;
                    continue;
                }
                var t = Lexer.next(s, i, end);
                int stop = t.end();
                var color = ShaderPalette.lexical(t);
                if (first && directive.equals("include") && (s.charAt(i) == '<' || s.charAt(i) == '"')) {
                    char closer = s.charAt(i) == '<' ? '>' : '"';
                    int close = s.indexOf(closer, i + 1);
                    stop = close < 0 ? end : close + 1;
                    color = ShaderPalette.HEADER;
                } else if (t.kind() == Lexer.Kind.WORD) {
                    if (first && directive.equals("define") && stop < end && s.charAt(stop) == '(') {
                        functionMacro = true;
                        paramsEnd = s.indexOf(')', stop + 1);
                        if (paramsEnd < 0)
                            paramsEnd = end;
                        color = ShaderPalette.MACRO;
                    } else if (functionMacro && (i < paramsEnd || parameters.contains(t.text()))) {
                        parameters.add(t.text());
                        color = ShaderPalette.MACRO_PARAMETER;
                    } else if (first && directive.equals("extension"))
                        color = ShaderPalette.EXTENSION;
                    else if (directive.equals("version") || t.text().equals("defined"))
                        color = ShaderPalette.DIRECTIVE;
                    else if (color == ShaderPalette.NAME || first && directive.equals("define"))
                        color = ShaderPalette.MACRO;
                } else if (t.kind() == Lexer.Kind.DIRECTIVE)
                    color = ShaderPalette.DIRECTIVE;
                list.add(new Part(base + i, base + stop, color));
                if (!t.trivia())
                    first = false;
                i = stop;
            }
        } else if (role == ShaderPalette.DOC) {
            int i = 0;
            var m = DOC_DETAIL.matcher(s);
            while (m.find()) {
                if (m.start() > i)
                    list.add(new Part(base + i, base + m.start(), role));
                if (m.group("tag") == null) {
                    var detail = m.group("block") != null ? ShaderPalette.DOC_BLOCK
                        : m.group("markup") != null       ? ShaderPalette.DOC_MARKUP
                                                          : ShaderPalette.DOC_CODE;
                    list.add(new Part(base + m.start(), base + m.end(), detail));
                } else {
                    list.add(new Part(base + m.start(), base + m.end("tag"), ShaderPalette.DOC_TAG));
                    if (m.group("arg") != null) {
                        list.add(new Part(base + m.end("tag"), base + m.start("arg"), role));
                        list.add(new Part(base + m.start("arg"), base + m.end("arg"), ShaderPalette.DOC_VALUE));
                    }
                }
                i = m.end();
            }
            if (i < end)
                list.add(new Part(base + i, base + end, role));
        } else if (role == ShaderPalette.STRING_VALUE) {
            int start = 0;
            for (int i = 0; i + 1 < end; i++)
                if (s.charAt(i) == '\\') {
                    if (start < i)
                        list.add(new Part(base + start, base + i, role));
                    boolean valid = "'\"\\abfnrtv?01234567xXuU\n\r".indexOf(s.charAt(i + 1)) >= 0;
                    list.add(
                        new Part(base + i, base + i + 2, valid ? ShaderPalette.ESCAPE : ShaderPalette.BAD_ESCAPE));
                    start = ++i + 1;
                }
            if (start < end)
                list.add(new Part(base + start, base + end, role));
        } else
            list.add(new Part(base, token.end(), role));
        return list;
    }
    private CharSequence buffer = "";
    private int end, base, index;
    private List<Part> parts = List.of();
    public void start(@NotNull CharSequence text, int start, int end, int state) {
        this.buffer = text;
        this.end = end;
        base = Math.max(0, start - state);
        read();
        while (getTokenType() != null && getTokenEnd() <= start) advance();
    }
    private void read() {
        index = 0;
        parts = base >= end ? List.of() : parts(Lexer.next(buffer, base, end));
    }
    public int getState() {
        return getTokenStart() - base;
    }
    public IElementType getTokenType() {
        if (parts.isEmpty())
            return null;
        var c = parts.get(index).color;
        return c == null ? TokenType.WHITE_SPACE : c.token;
    }
    public int getTokenStart() {
        return parts.isEmpty() ? end : parts.get(index).from;
    }
    public int getTokenEnd() {
        return parts.isEmpty() ? end : parts.get(index).to;
    }
    public void advance() {
        if (!parts.isEmpty() && ++index == parts.size()) {
            base = parts.get(index - 1).to;
            read();
        }
    }
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }
    public int getBufferEnd() {
        return end;
    }
}
