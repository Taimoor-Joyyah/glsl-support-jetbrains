package com.glslsupport.lang;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Layout identifiers accepted by the packaged glslang. The compiler validates stage and extension restrictions.
 */
public final class LayoutQualifiers {
    private static final Map<String, Boolean> NAMES = new TreeMap<>();
    static {
        try (var in = LayoutQualifiers.class.getResourceAsStream("/glsl/layout.tsv");
            var lines =
                new BufferedReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
            lines.lines().forEach(line -> {
                var item = line.split("\t");
                NAMES.put(item[0], item[1].equals("value"));
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
    public static boolean takesValue(String name) {
        return Boolean.TRUE.equals(NAMES.get(name));
    }
    public static boolean known(String name) {
        return NAMES.containsKey(name);
    }

    /** Opening parenthesis for the current layout item, including incomplete lists. */
    public static int opening(Syntax f, int before) {
        for (int i = before - 1; i >= 0; i--) {
            String s = f.word(i);
            if (s.equals("("))
                return f.word(i - 1).equals("layout") ? i : -1;
            if (s.equals(")") || s.equals("]")) {
                if (f.match[i] < 0)
                    return -1;
                i = f.match[i];
            } else if (s.equals("{") || s.equals("}") || s.equals(";")
                || f.code.get(i).kind() == Lexer.Kind.DIRECTIVE)
                break;
        }
        return -1;
    }
    public static boolean item(Syntax f, int index) {
        return (f.word(index - 1).equals(",") || f.word(index - 1).equals("(")) && opening(f, index) >= 0;
    }
    public static List<String> suggestions(Syntax f, int index, String prefix, boolean vulkan) {
        int open = opening(f, index);
        if (open < 0 || !item(f, index))
            return null;
        Set<String> used = new HashSet<>();
        int close = f.match[open];
        if (close < 0)
            close = index;
        for (int i = open + 1; i < close; i++)
            if (i != index && (i == open + 1 || f.word(i - 1).equals(",")))
                used.add(f.word(i));
        return NAMES.keySet()
            .stream()
            .filter(n -> n.startsWith(prefix) && !used.contains(n))
            .filter(n -> vulkan || !Set.of("set", "push_constant", "input_attachment_index").contains(n))
            .toList();
    }
    private LayoutQualifiers() {}
}
