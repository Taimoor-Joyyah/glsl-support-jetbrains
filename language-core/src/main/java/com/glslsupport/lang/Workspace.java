package com.glslsupport.lang;

import static com.glslsupport.lang.Syntax.Kind.*;

import java.nio.file.Path;
import java.util.*;

public final class Workspace {
    public record Location(String file, Syntax.Symbol symbol) {}
    public record Use(String file, int start, int end) {}
    public final Map<String, Syntax> files;
    private final List<String> roots;
    private final Map<String, String> definitions;
    private final String translationUnit;
    private final ShaderConfig.Stage stage;
    private Set<String> inputPaths;
    private final Map<String, Translation> translations = new java.util.concurrent.ConcurrentHashMap<>();
    private final class Translation {
        final Map<String, List<Conditions>> ranges = new LinkedHashMap<>();
        final List<String> visible;
        int includes;
        Translation(String root) {
            enter(root, Conditions.macros(definitions), new HashSet<>());
            visible = List.copyOf(ranges.keySet());
        }
        void enter(String path, Map<String, Conditions.Macro> macros, Set<String> stack) {
            var syntax = files.get(path);
            if (syntax == null || stack.size() >= 64 || includes++ >= 4096 || !stack.add(path))
                return;
            var records = ranges.computeIfAbsent(path, k -> new ArrayList<>());
            records.add(new Conditions(syntax, macros, inc -> {
                var next = include(path, inc);
                if (next != null)
                    enter(next, macros, stack);
            }));
            stack.remove(path);
        }
        boolean active(String path, int offset) {
            var contexts = ranges.get(path);
            if (contexts != null)
                for (var context : contexts)
                    if (context.active(offset)) return true;
            return false;
        }
    }
    private Translation translation(String root) {
        return translations.computeIfAbsent(translationUnit == null ? root : translationUnit, Translation::new);
    }
    public static String path(String value) {
        return Path.of(value).normalize().toString().replace('\\', '/');
    }
    public Workspace(Map<String, Syntax> files, List<String> roots, Map<String, String> definitions) {
        this(files, roots, definitions, null);
    }
    public Workspace(
        Map<String, Syntax> files, List<String> roots, Map<String, String> definitions, String translationUnit) {
        this(files, roots, definitions, translationUnit, null);
    }
    public Workspace(Map<String, Syntax> files, List<String> roots, ShaderConfig.Effective config, String translationUnit) {
        this(files, roots, config.editorDefines(), translationUnit, config.stage());
    }
    private Workspace(Map<String, Syntax> files, List<String> roots, Map<String, String> definitions,
                      String translationUnit, ShaderConfig.Stage stage) {
        this.stage = stage;
        this.translationUnit = translationUnit == null ? null : path(translationUnit);
        var normalized = new LinkedHashMap<String, Syntax>();
        files.forEach((k, v) -> normalized.put(path(k), v));
        this.files = Collections.unmodifiableMap(normalized);
        this.roots = List.copyOf(roots);
        this.definitions = Map.copyOf(definitions);
    }
    public boolean active(String file, int offset) {
        String root = files.containsKey(file) ? file : path(file);
        return translation(root).active(root, offset);
    }
    public String include(String file, Syntax.Include inc) {
        return include(file, inc, null);
    }
    /** Includes missing search candidates whose creation would change this snapshot. */
    public synchronized Set<String> inputPaths() {
        if (inputPaths == null) {
            var paths = new HashSet<>(files.keySet());
            files.forEach((path, syntax) -> syntax.includes.forEach(inc -> include(path, inc, paths)));
            inputPaths = Set.copyOf(paths);
        }
        return inputPaths;
    }
    private String include(String file, Syntax.Include inc, Set<String> searched) {
        var dirs = new ArrayList<String>();
        var parent = Path.of(file).getParent();
        if (!inc.system())
            dirs.add(parent == null ? "" : parent.toString());
        dirs.addAll(roots);
        for (var dir : dirs) {
            var resolved = resolvePath(dir, inc.path());
            if (resolved == null) continue;
            if (searched != null) searched.add(resolved);
            if (files.containsKey(resolved))
                return resolved;
        }
        return null;
    }
    public static String resolvePath(String directory, String include) {
        try {
            return path(Path.of(directory).resolve(include).toString());
        } catch (java.nio.file.InvalidPathException ignored) {
            return null;
        }
    }
    public List<String> visible(String file) {
        return translation(files.containsKey(file) ? file : path(file)).visible;
    }
    public List<Location> resolve(String file, int offset) {
        if (!files.containsKey(file))
            file = path(file);
        var f = files.get(file);
        if (f == null)
            return List.of();
        var decl = f.declaration(offset);
        if (decl != null)
            return List.of(new Location(file, decl));
        int i = f.indexAt(offset);
        if (i >= f.code.size() || f.code.get(i).start() > offset || !f.ident(i))
            return List.of();
        return lookup(file, i, 0);
    }
    private List<Location> lookup(String file, int index, int depth) {
        var f = files.get(file);
        String name = f.word(index);
        if (index < 0 || index >= f.code.size() || depth > 24)
            return List.of();
        int pos = f.code.get(index).start();
        String owner = f.word(index - 1).equals(".") ? typeAt(file, index - 2, depth + 1) : null;
        var found = new ArrayList<Location>();
        int best = -1;
        for (String candidate : visible(file)) {
            var model = files.get(candidate);
            for (var s : model.named(name)) {
                if (!translation(file).active(candidate, s.at()))
                    continue;
                int score = 0;
                if (owner != null) {
                    if (s.kind() != FIELD || !owner.equals(s.owner()))
                        continue;
                    score = 1;
                } else if (s.kind() == FIELD) {
                    if (!exposedField(model, s))
                        continue;
                } else if (s.local()) {
                    if (!candidate.equals(file) || s.at() > pos || pos < s.scopeStart() || pos >= s.scopeEnd())
                        continue;
                    score = 2 + s.scopeStart();
                }
                if (score > best) {
                    found.clear();
                    best = score;
                }
                if (score == best)
                    found.add(new Location(candidate, s));
            }
        }
        if (f.word(index + 1).equals("(") && found.stream().anyMatch(l -> l.symbol.kind() == FUNCTION)) {
            var args = arguments(f, index + 1);
            var ranks = new LinkedHashMap<Location, Integer>();
            for (var l : found) {
                var s = l.symbol;
                if (s.kind() != FUNCTION || s.parameters().size() != args.size())
                    continue;
                int rank = 0;
                boolean possible = true;
                for (int k = 0; k < args.size(); k++) {
                    String type = typeAt(file, args.get(k), depth + 1);
                    if (type.equals(s.parameters().get(k)))
                        rank += 3;
                    else if (!type.isEmpty() && !numeric(type, s.parameters().get(k))) {
                        possible = false;
                        break;
                    }
                }
                if (possible)
                    ranks.put(l, rank);
            }
            if (!ranks.isEmpty()) {
                int top = Collections.max(ranks.values());
                found.removeIf(l -> ranks.getOrDefault(l, -1) != top);
            }
        }
        return found;
    }
    static boolean exposedField(Syntax f, Syntax.Symbol field) {
        for (var b : f.named(field.owner()))
            if (b.kind() == BLOCK) {
                String tail =
                    f.source.substring(Math.max(b.start(), f.source.lastIndexOf('}', b.end() - 1) + 1), b.end())
                        .trim();
                return tail.equals(";");
            }
        return false;
    }
    static boolean numeric(String a, String b) {
        return Set.of("int", "uint", "float", "double").contains(a)
            && Set.of("int", "uint", "float", "double").contains(b);
    }
    public static List<Integer> arguments(Syntax f, int open) {
        int close = f.match[open] >= 0 ? f.match[open] : f.code.size();
        var args = new ArrayList<Integer>();
        int first = open + 1;
        for (int j = first; j <= close; j++) {
            if (j == close || f.word(j).equals(",")) {
                if (first < j)
                    args.add(j - 1);
                first = j + 1;
            } else if ((f.word(j).equals("(") || f.word(j).equals("[")) && f.match[j] > j)
                j = f.match[j];
        }
        return args;
    }
    public String typeAt(String file, int i, int depth) {
        if (depth > 24)
            return "";
        var f = files.get(file);
        if (i < 0 || i >= f.code.size())
            return "";
        var t = f.code.get(i);
        String n = t.text();
        if (n.equals(")")) {
            int open = f.match[i];
            if (open < 0)
                return "";
            if (!f.ident(open - 1))
                return i > open + 1 ? typeAt(file, i - 1, depth + 1) : "";
            String name = f.word(open - 1);
            if (Catalog.has(name, "functions") && lookup(file, open - 1, depth + 1).isEmpty()) {
                var args = arguments(f, open).stream().map(arg -> typeAt(file, arg, depth + 1)).toList();
                String result = Catalog.callResult(name, args);
                if (!result.isEmpty())
                    return result;
            }
            return typeAt(file, open - 1, depth + 1);
        }
        if (n.equals("]")) {
            int indexes = 0, base = i;
            while (f.word(base).equals("]")) {
                base = f.match[base] - 1;
                if (base < 0)
                    return "";
                indexes++;
            }
            String type = typeAt(file, base, depth + 1);
            int dimensions = 0;
            if (f.ident(base))
                for (var hit : lookup(file, base, depth + 1)) {
                    var decl = files.get(hit.file());
                    int bracket = decl.indexAt(hit.symbol().at()) + 1;
                    while (decl.word(bracket).equals("[") && decl.match[bracket] > bracket) {
                        dimensions++;
                        bracket = decl.match[bracket] + 1;
                    }
                    break;
                }
            for (int d = dimensions; d < indexes; d++) {
                if (vector(type))
                    type = swizzle(type, 1);
                else if (type.matches("d?mat[234](x[234])?"))
                    type = (type.startsWith("d") ? "dvec" : "vec") + type.charAt(type.length() - 1);
                else
                    return "";
            }
            return type;
        }
        if (t.kind() == Lexer.Kind.NUMBER) {
            String number = n.toLowerCase(Locale.ROOT);
            if (number.endsWith("u"))
                return "uint";
            if (number.startsWith("0x"))
                return "int";
            if (number.endsWith("lf"))
                return "double";
            return number.contains(".") || number.indexOf('e') >= 0 || number.endsWith("f") ? "float" : "int";
        }
        if (n.equals("true") || n.equals("false"))
            return "bool";
        if (Catalog.has(n, "types") || f.symbols.stream().anyMatch(s -> s.kind() == TYPE && s.name().equals(n)))
            return n;
        if (!f.ident(i))
            return "";
        if (f.word(i - 1).equals(".") && n.matches("([xyzw]{1,4}|[rgba]{1,4}|[stpq]{1,4})")) {
            String v = typeAt(file, i - 2, depth + 1);
            if (validSwizzle(v, n))
                return swizzle(v, n.length());
        }
        var hits = lookup(file, i, depth + 1);
        if (!hits.isEmpty())
            return hits.get(0).symbol.type();
        return Catalog.entries(n)
            .stream()
            .map(Catalog.Entry::type)
            .filter(v -> !v.isEmpty())
            .findFirst()
            .orElse("");
    }
    static String swizzle(String vec, int length) {
        String prefix = vec.substring(0, vec.indexOf("vec"));
        return length == 1 ? switch (prefix) {
            case "i" -> "int";
            case "u" -> "uint";
            case "b" -> "bool";
            case "d" -> "double";
            case "" -> "float";
            case "bf16" -> "bfloat16_t";
            default ->
                (prefix.startsWith("i")          ? "int"
                        : prefix.startsWith("u") ? "uint"
                                                 : "float")
                    + prefix.substring(1) + "_t";
        } : prefix + "vec" + length;
    }
    private static boolean vector(String type) {
        int n = type.length();
        return n >= 4 && type.startsWith("vec", n - 4) && "234".indexOf(type.charAt(n - 1)) >= 0
            && Catalog.has(type, "types");
    }
    public static boolean validSwizzle(String type, String name) {
        if (!vector(type) || name.isEmpty() || name.length() > 4)
            return false;
        int width = type.charAt(type.length() - 1) - '0';
        for (String alphabet : List.of("xyzw", "rgba", "stpq"))
            if (name.chars().allMatch(c -> alphabet.indexOf(c) >= 0 && alphabet.indexOf(c) < width))
                return true;
        return false;
    }
    public List<String> completions(String file, int pos, boolean builtins) {
        var f = files.get(file);
        int i = f.indexAt(pos);
        if (i > 0 && f.code.get(i - 1).end() == pos && f.ident(i - 1))
            i--;
        int lineStart = f.source.lastIndexOf('\n', pos - 1) + 1;
        String line = f.source.substring(lineStart, pos).stripLeading();
        if (line.startsWith("#") && line.substring(1).stripLeading().matches("extension\\s+\\w*")) {
            String extension = line.substring(1).stripLeading().substring("extension".length()).strip();
            return builtins ? Catalog.extensions().stream().filter(n -> n.startsWith(extension)).toList()
                            : List.of();
        }
        String prefix = i < f.code.size() && f.ident(i) && f.code.get(i).start() < pos
            ? f.source.substring(f.code.get(i).start(), pos)
            : "";
        var names = new LinkedHashSet<String>();
        var layouts = LayoutQualifiers.suggestions(f, i, prefix, definitions.containsKey("VULKAN"));
        if (layouts != null)
            return layouts;
        if (f.word(i - 1).equals(".")) {
            String type = typeAt(file, i - 2, 0);
            for (String k : visible(file))
                for (var s : files.get(k).fields(type))
                    if (translation(file).active(k, s.at()))
                        names.add(s.name());
            if (vector(type)) {
                int count = type.charAt(type.length() - 1) - '0';
                for (String set : List.of("xyzw", "rgba", "stpq")) {
                    String letters = set.substring(0, count);
                    for (char c : letters.toCharArray()) {
                        if (prefix.isEmpty()
                            || prefix.chars().allMatch(k -> letters.indexOf(k) >= 0) && prefix.length() < 4)
                            names.add(prefix + c);
                    }
                }
            }
        } else {
            for (String k : visible(file))
                for (var s : files.get(k).symbols) {
                    if (!translation(file).active(k, s.at()))
                        continue;
                    if (s.local()
                        && (!k.equals(file) || s.at() > pos || pos < s.scopeStart() || pos >= s.scopeEnd()))
                        continue;
                    if (s.kind() != FIELD || exposedField(files.get(k), s))
                        names.add(s.name());
                }
            if (builtins) {
                boolean vulkan = definitions.containsKey("VULKAN");
                for (var name : Catalog.codeNames())
                    if (name.startsWith(prefix) && (stage == null || Catalog.available(name, stage, vulkan)))
                        names.add(name);
            }
        }
        return names.stream().filter(n -> n.startsWith(prefix)).toList();
    }
    public List<Use> usages(Location target) {
        var out = new ArrayList<Use>();
        String name = target.symbol.name();
        for (var entry : files.entrySet()) {
            var f = entry.getValue();
            String file = entry.getKey();
            if (!visible(file).contains(target.file) && !visible(target.file).contains(file))
                continue;
            for (var t : f.code) {
                if (!t.text().equals(name) || !active(file, t.start()))
                    continue;
                if (resolve(file, t.start()).stream().anyMatch(hit -> equivalent(target, hit)))
                    out.add(new Use(file, t.start(), t.end()));
            }
        }
        return out;
    }
    public boolean equivalent(Location a, Location b) {
        if (a.equals(b))
            return true;
        return a.symbol.kind() == FUNCTION && b.symbol.kind() == FUNCTION
            && a.symbol.name().equals(b.symbol.name()) && a.symbol.parameters().equals(b.symbol.parameters())
            && (visible(a.file).contains(b.file) || visible(b.file).contains(a.file));
    }
}
