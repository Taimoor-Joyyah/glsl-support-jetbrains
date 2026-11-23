package com.glslsupport.bridge;

import java.util.*;
import java.util.regex.*;

/** A trace row is identified by its directive and the chain of include sites that led to it. */
public final class PreprocessorTrace {
    private PreprocessorTrace() {}
    public record Call(String file, int line, String header) {}
    public record Site(List<Call> callers, String file, int line, String kind, String name, int occurrence) {}
    public record Entry(String directive, String value) {}
    public record Row(Site site, Entry left, Entry right) {
        public boolean changed() { return !Objects.equals(left, right); }
    }
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    public static Map<Site,Entry> entries(NativeCompiler.Request request, NativeCompiler.Result result,
                                          Map<String,String> variant, Map<String,String> overrides) {
        var rows = new LinkedHashMap<Site,Entry>();
        var unit = request.units().getFirst();
        var config = unit.config();
        var values = new LinkedHashMap<String,String>();
        setting(rows, "Compiler", "Stage", config.stage().name());
        setting(rows, "Compiler", "Target", request.vulkan() == 0 ? "OpenGL" :
            "Vulkan 1." + (request.vulkan() - 10));
        for (var e : variant.entrySet()) setting(rows, "File variant", e.getKey(), e.getValue());
        for (var e : overrides.entrySet()) setting(rows, "Pipeline", e.getKey(), e.getValue());
        values.putAll(config.editorDefines());
        var files = new HashMap<>(request.headers());
        files.put(unit.path(), unit.text());
        var lines = new HashMap<String,String[]>();
        String version = Integer.toString(config.version() == 0 ? 110 : config.version());
        String profile = config.profile();
        String versionSource = "Variant version override";
        if (config.version() == 0) {
            versionSource = "Compiler default";
            profile = "";
            for (var event : result.trace()) {
                if (event.kind().equals("version")) {
                    version = event.name();
                    profile = event.value();
                    versionSource = event.file() + ":" + event.line();
                    break;
                }
            }
        }
        values.put("__VERSION__", version);
        setting(rows, "Compiler", "__VERSION__", version + " · " + versionSource);
        if (version.equals("100") || profile.equals("es")) values.put("GL_ES", "1");
        for (String name : List.of("GL_ES", "VULKAN"))
            if (values.containsKey(name)) setting(rows, "Compiler", name, values.get(name));

        var stack = new ArrayList<Call>();
        var repeats = new HashMap<Site,Integer>();
        for (var e : result.trace()) {
            if (e.file().isEmpty() || e.line() <= 0) continue;
            while (stack.size() > e.depth()) stack.removeLast();
            var callers = List.copyOf(stack);
            var base = new Site(callers, e.file(), e.line(), e.kind(), e.name(), 1);
            int index = repeats.merge(base, 1, Integer::sum);
            var site = index == 1 ? base : new Site(callers, e.file(), e.line(), e.kind(), e.name(), index);
            String source = files.get(e.file());
            String directive = source == null ? "" : directive(
                lines.computeIfAbsent(e.file(), k -> source.split("\\R", -1)), e.line());
            String label = directive.isEmpty() ? "#" + (e.kind().equals("branch") ? e.name() : e.kind() + " " + e.name())
                : directive;
            String value = e.value();
            switch (e.kind()) {
                case "include" -> {
                    if (value.isEmpty()) value = "Unresolved";
                    else stack.add(new Call(e.file(), e.line(), e.name()));
                }
                case "define" -> {
                    var definition = Pattern.compile("^#\\s*define\\s+" + Pattern.quote(e.name()) + "\\b(.*)$").matcher(directive);
                    value = definition.matches() ? definition.group(1).strip() : "Defined";
                    values.put(e.name(), value);
                    label = "#define " + e.name();
                    if (value.isEmpty()) value = "(empty)";
                }
                case "undef" -> {
                    String previous = values.remove(e.name());
                    value = previous == null ? "Undefined" : "Undefined · previously " + previous;
                }
                case "branch" -> {
                    var used = new LinkedHashSet<String>();
                    if (!value.startsWith("skipped"))
                        IDENT.matcher(directive).results().map(MatchResult::group).filter(values::containsKey)
                            .forEach(name -> used.add(name + "=" + values.get(name)));
                    if (!used.isEmpty()) value += " · " + String.join(", ", used);
                }
                case "version" -> value = e.name() + (value.isBlank() ? "" : " " + value);
                default -> { }
            }
            rows.put(site, new Entry(label, value));
        }
        return rows;
    }
    private static void setting(Map<Site,Entry> rows, String origin, String name, String value) {
        rows.put(new Site(List.of(), origin, 0, "setting", name, 1), new Entry(name, value));
    }
    private static String directive(String[] lines, int line) {
        if (line < 1 || line > lines.length) return "";
        String text = lines[line - 1].strip();
        while (text.endsWith("\\") && line < lines.length)
            text = text.substring(0, text.length() - 1) + " " + lines[line++].strip();
        return text.startsWith("#") ? text : "";
    }
    public static List<Row> compare(Map<Site,Entry> left, Map<Site,Entry> right) {
        var sites = new LinkedHashSet<>(left.keySet());
        sites.addAll(right.keySet());
        return sites.stream().map(k -> new Row(k, left.get(k), right.get(k))).toList();
    }
}
