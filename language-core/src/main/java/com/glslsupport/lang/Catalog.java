package com.glslsupport.lang;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Catalog {
    public record
        Entry(String kind, String name, String type, String parameters, String extensions, String description) {
        public String signature() {
            if (kind.equals("functions"))
                return type + " " + name + "(" + parameters + ")";
            return type.isEmpty() ? name : type + " " + name;
        }
    }
    private static final Map<String, List<Entry>> NAMES = read();
    private static final List<String> EXTENSIONS =
        NAMES.keySet().stream().filter(n -> has(n, "extensions")).toList();
    private static final List<String> CODE = NAMES.keySet().stream().filter(n -> !has(n, "extensions")).toList();
    private Catalog() {}
    private static Map<String, List<Entry>> read() {
        var result = new TreeMap<String, List<Entry>>();
        for (String resource : List.of("catalog.tsv", "core46.tsv", "compiler-keywords.tsv"))
            try (var in = new BufferedReader(new InputStreamReader(
                     Objects.requireNonNull(Catalog.class.getResourceAsStream("/glsl/" + resource)),
                     StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null;) {
                    var f = line.split("\t", -1);
                    if (resource.equals("core46.tsv"))
                        result.remove(f[1]);
                    result.computeIfAbsent(f[1], k -> new ArrayList<>())
                        .add(f.length == 2 ? new Entry(f[0], f[1], "", "", "", "")
                                           : new Entry(f[0], f[1], f[2], f[3], f[4], f[5]));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        result.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(result);
    }
    public static Collection<String> names() {
        return NAMES.keySet();
    }
    public static List<String> extensions() {
        return EXTENSIONS;
    }
    public static List<String> codeNames() {
        return CODE;
    }
    public static List<Entry> entries(String name) {
        return NAMES.getOrDefault(name, List.of());
    }
    public static boolean has(String name, String kind) {
        return entries(name).stream().anyMatch(e -> e.kind.equals(kind));
    }
    static String callResult(String name, List<String> arguments) {
        for (var entry : entries(name)) {
            if (!entry.kind.equals("functions"))
                continue;
            var params = entry.parameters.isBlank() ? new String[0] : entry.parameters.split(",\\s*");
            if (params.length != arguments.size())
                continue;
            var types = new HashMap<String, String>();
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                String formal = params[i]
                                    .replaceFirst("^(?:(?:const|in|inout|out|highp|mediump|lowp)\\s+)+", "")
                                    .split("\\s+")[0];
                String actual = arguments.get(i);
                if (formal.equals(actual))
                    continue;
                String pattern = switch (formal) {
                    case "genType" -> "float|vec[234]";
                    case "genDType" -> "double|dvec[234]";
                    case "genIType" -> "int|ivec[234]";
                    case "genUType" -> "uint|uvec[234]";
                    case "genBType" -> "bool|bvec[234]";
                    case "vec", "dvec", "ivec", "uvec", "bvec" -> formal + "[234]";
                    default -> "";
                };
                if (!pattern.isEmpty() && actual.matches(pattern)) {
                    String prev = types.putIfAbsent(formal, actual);
                    if (prev == null || prev.equals(actual))
                        continue;
                } else if (formal.startsWith("gsampler") && actual.matches("[iu]?" + formal.substring(1))) {
                    types.put("gvec4",
                        actual.startsWith("isampler")       ? "ivec4"
                            : actual.startsWith("usampler") ? "uvec4"
                                                            : "vec4");
                    continue;
                }
                match = false;
                break;
            }
            if (!match)
                continue;
            if (types.containsKey(entry.type))
                return types.get(entry.type);
            if (entry.type.equals("bvec") && !arguments.isEmpty()
                && arguments.getFirst().matches("[diub]?vec[234]"))
                return "bvec" + arguments.getFirst().charAt(arguments.getFirst().length() - 1);
            return entry.type;
        }
        return "";
    }
}
