package com.glslsupport.bridge;

import com.glslsupport.lang.*;
import java.util.*;
import java.util.regex.*;

public final class PipelineDiagnostics {
    private PipelineDiagnostics() {}
    public record Location(String path, int line, int offset, String label) {}
    public record Issue(int stage, String severity, String message, List<Location> locations) {}
    private static final Pattern STAGE_DETAIL = Pattern.compile("^\\s*(.+?) stage:\\s*(.*)$");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    public static List<Issue> collect(NativeCompiler.Request request, NativeCompiler.Result result,
                                      List<Workspace> graphs) {
        var issues = new ArrayList<Issue>();
        for (int i = 0; i < result.stageLogs().size(); i++)
            parse(result.stageLogs().get(i), i, request, graphs, issues);
        parse(result.linkLog(), -1, request, graphs, issues);
        return List.copyOf(issues);
    }
    private static void parse(String log, int stage, NativeCompiler.Request request, List<Workspace> graphs,
                              List<Issue> issues) {
        String[] lines = log.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("ERROR:") && !line.startsWith("WARNING:")) continue;
            String severity = line.substring(0, line.indexOf(':'));
            String message = line.substring(severity.length() + 1).strip();
            if (NativeCompiler.Result.summary(message)) continue;
            var places = new LinkedHashSet<Location>();
            var located = NativeCompiler.MESSAGE.matcher(line);
            if (located.matches()) {
                message = located.group(5);
                String path = located.group(2);
                if (path.equals("0") && stage >= 0) path = request.units().get(stage).path();
                String source = request.headers().get(path);
                for (var unit : request.units()) if (unit.path().equals(path)) source = unit.text();
                int row = Integer.parseInt(located.group(3));
                if (source != null && row > 0) {
                    int column = located.group(4) == null ? 1 : Integer.parseInt(located.group(4));
                    places.add(new Location(path, row, NativeCompiler.offset(source, row, column), path + ":" + row));
                }
            }
            while (i + 1 < lines.length && !lines[i + 1].isBlank() && Character.isWhitespace(lines[i + 1].charAt(0))) {
                String detail = lines[++i];
                message += "\n" + detail.strip();
                var match = STAGE_DETAIL.matcher(detail);
                if (stage < 0 && match.matches()) {
                    for (int u = 0; u < request.units().size(); u++) {
                        var unit = request.units().get(u);
                        if (stageName(unit.config().stage()).equals(match.group(1)))
                            declaration(graphs.get(u), unit.path(), match.group(2), match.group(1)).ifPresent(places::add);
                    }
                }
            }
            issues.add(new Issue(stage, severity, message, List.copyOf(places)));
        }
    }
    private static String stageName(ShaderConfig.Stage stage) {
        return switch (stage) {
            case TESS_CONTROL -> "tessellation control";
            case TESS_EVAL -> "tessellation evaluation";
            case RAYGEN -> "ray-generation";
            case ANY_HIT -> "any-hit";
            case CLOSEST_HIT -> "closest-hit";
            default -> stage.name().toLowerCase(Locale.ROOT);
        };
    }
    private static Optional<Location> declaration(Workspace graph, String root, String detail, String stage) {
        var names = new ArrayList<String>();
        IDENT.matcher(detail).results().forEach(m -> names.add(m.group()));
        // glslang ends each type comparison with the interface name (or the block member name).
        if (names.isEmpty()) return Optional.empty();
        String name = names.getLast();
        var hits = new ArrayList<Location>();
        for (String path : graph.visible(root)) {
            var file = graph.files.get(path);
            for (var entry : file.named(name)) {
                if (entry.kind() != Syntax.Kind.GLOBAL && entry.kind() != Syntax.Kind.BLOCK &&
                    entry.kind() != Syntax.Kind.FIELD || !graph.active(path, entry.at())) continue;
                int row = 1 + (int) file.source.substring(0, entry.at()).chars().filter(c -> c == '\n').count();
                hits.add(new Location(path, row, entry.at(), stage + " · " + name + " · " + path + ":" + row));
            }
        }
        return hits.size() == 1 ? Optional.of(hits.getFirst()) : Optional.empty();
    }
}
