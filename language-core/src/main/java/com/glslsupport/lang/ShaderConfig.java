package com.glslsupport.lang;

import java.nio.file.Path;
import java.util.*;
public final class ShaderConfig {
    public enum Stage {
        VERTEX("vert", "vs"),
        TESS_CONTROL("tesc"),
        TESS_EVAL("tese"),
        GEOMETRY("geom"),
        FRAGMENT("frag", "fs"),
        COMPUTE("comp"),
        RAYGEN("rgen"),
        INTERSECTION("rint"),
        ANY_HIT("rahit"),
        CLOSEST_HIT("rchit"),
        MISS("rmiss"),
        CALLABLE("rcall"),
        TASK("task"),
        MESH("mesh");
        public final List<String> extensions;
        Stage(String... ext) {
            extensions = List.of(ext);
        }
        public static Optional<Stage> fromPath(String path) {
            return Arrays.stream(values())
                .filter(
                    s -> s.extensions.stream().anyMatch(ext -> path.toLowerCase(Locale.ROOT).endsWith("." + ext)))
                .findFirst();
        }
    }
    public record Variant(
        String name, Stage stage, Integer vulkan, int version, String profile, Map<String, String> defines) {
        public Variant {
            defines = Collections.unmodifiableMap(new LinkedHashMap<>(defines));
        }
        public static Variant defaultVariant() {
            return new Variant("Default", null, null, 0, "core", Map.of());
        }
    }
    public record Effective(Stage stage, int vulkan, int version, String profile, Map<String, String> defines) {
        public Effective {
            defines = Collections.unmodifiableMap(new LinkedHashMap<>(defines));
        }
        public Map<String, String> editorDefines() {
            var values = new LinkedHashMap<>(defines);
            if (vulkan > 0)
                values.put("VULKAN", "100");
            if (version > 0) {
                values.put("__VERSION__", Integer.toString(version));
                if (version == 100 || profile.equals("es"))
                    values.put("GL_ES", "1");
            }
            return values;
        }
    }
    public static Effective resolve(String file, int projectTarget, Map<String, Integer> targets,
        Stage defaultStage, Map<String, Stage> stageMacros, Variant v) {
        var filePath = Path.of(file).normalize();
        int best = -1, target = projectTarget;
        for (var rule : targets.entrySet()) {
            var dir = Path.of(rule.getKey()).normalize();
            if (filePath.startsWith(dir) && dir.getNameCount() > best) {
                best = dir.getNameCount();
                target = rule.getValue();
            }
        }
        Stage stage = v.stage();
        if (stage == null) {
            for (String macro : v.defines.keySet())
                if (stageMacros.containsKey(macro))
                    stage = stageMacros.get(macro);
        }
        return new Effective(stage == null ? Stage.fromPath(file).orElse(defaultStage) : stage,
            v.vulkan == null ? target : v.vulkan, v.version, v.profile, v.defines);
    }
}
