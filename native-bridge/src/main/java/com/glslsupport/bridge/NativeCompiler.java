package com.glslsupport.bridge;

import com.glslsupport.lang.ShaderConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class NativeCompiler {
    public enum Operation { CHECK, SPIRV, LAYOUT }
    public record Unit(String path, String text, ShaderConfig.Effective config) {}
    public record Request(int vulkan, Operation operation, List<Unit> units, Map<String, String> headers,
        List<String> includeRoots) {
        public Request {
            units = List.copyOf(units);
            headers = Map.copyOf(headers);
            includeRoots = List.copyOf(includeRoots);
        }
    }
    public record Diagnostic(String severity, String file, int line, int column, String message) {}
    public record Line(String file, int line) {}
    public record Result(boolean success, String log, String assembly, String layout, int[] words) {
        public List<Diagnostic> diagnostics(String fallback) {
            var result = new ArrayList<Diagnostic>();
            for (String line : log.split("\\R")) {
                var match = MESSAGE.matcher(line);
                if (match.matches()) {
                    String message = match.group(5).strip();
                    if (!summary(message))
                        result.add(
                            new Diagnostic(match.group(1), match.group(2).equals("0") ? fallback : match.group(2),
                                Integer.parseInt(match.group(3)),
                                match.group(4) == null ? 0 : Integer.parseInt(match.group(4)), message));
                } else if ((line.startsWith("ERROR:") || line.startsWith("WARNING:")) && !summary(line))
                    result.add(new Diagnostic(line.startsWith("ERROR") ? "ERROR" : "WARNING", fallback, 1, 0,
                        line.substring(line.indexOf(':') + 1).strip()));
            }
            return result;
        }
        private static boolean summary(String s) {
            return s.contains("compilation errors.") || s.contains("compilation error.")
                || s.contains("No code generated") || s.isBlank();
        }
        public Map<Integer, Line> instructions() {
            var files = new HashMap<String, String>();
            var map = new TreeMap<Integer, Line>();
            Line current = null;
            int i = 0;
            for (String line : assembly.split("\n", -1)) {
                var path = SPV_STRING.matcher(line);
                if (path.matches())
                    files.put(path.group(1), path.group(2).replace("\\\\", "\\"));
                var loc = SPV_LINE.matcher(line);
                if (loc.matches()) {
                    String file = files.get(loc.group(1));
                    current = file == null ? null : new Line(file, Integer.parseInt(loc.group(2)));
                } else if (line.strip().equals("OpNoLine") || line.strip().equals("OpFunctionEnd"))
                    current = null;
                if (current != null)
                    map.put(i, current);
                i++;
            }
            return map;
        }
    }
    private static final Pattern MESSAGE = Pattern.compile("^(ERROR|WARNING): (.+?):(\\d+):(?:(\\d+):)?\\s*(.*)$");
    private static final Pattern SPV_STRING = Pattern.compile("\\s*(%\\S+)\\s*=\\s*OpString\\s+\"(.*)\"\\s*");
    private static final Pattern SPV_LINE = Pattern.compile("\\s*OpLine\\s+(%\\S+)\\s+(\\d+)\\s+\\d+\\s*");
    private static volatile boolean loaded;
    private static native int abi();
    private static native byte[] invoke(byte[] input);
    private NativeCompiler() {}
    public static synchronized void load(Path library) {
        if (loaded)
            return;
        System.load(library.toAbsolutePath().toString());
        if (abi() != 1)
            throw new UnsatisfiedLinkError("Unsupported GLSL compiler ABI");
        loaded = true;
    }
    public static Result compile(Request request) {
        if (!loaded)
            throw new IllegalStateException("The bundled GLSL compiler is unavailable");
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(request.vulkan);
            out.writeInt(request.operation.ordinal());
            out.writeInt(request.units.size());
            for (var unit : request.units) {
                write(out, unit.path);
                write(out, unit.text);
                var config = unit.config;
                out.writeInt(config.stage().ordinal());
                out.writeInt(config.version());
                write(out, config.profile());
                out.writeInt(config.defines().size());
                for (var def : config.defines().entrySet()) {
                    write(out, def.getKey());
                    write(out, def.getValue());
                }
            }
            out.writeInt(request.headers.size());
            for (var h : request.headers.entrySet()) {
                write(out, h.getKey());
                write(out, h.getValue());
            }
            out.writeInt(request.includeRoots.size());
            for (var root : request.includeRoots) write(out, root);
            var result = new DataInputStream(new ByteArrayInputStream(invoke(bytes.toByteArray())));
            boolean success = result.readInt() != 0;
            String log = read(result), assembly = read(result), layout = read(result);
            int[] words = new int[result.readInt()];
            for (int i = 0; i < words.length; i++) words[i] = result.readInt();
            return new Result(success, log, assembly, layout, words);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    private static void write(DataOutputStream out, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }
    private static String read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available())
            throw new IOException("Invalid native result length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
    public static int offset(String text, int line, int byteColumn) {
        int start = 0;
        for (int l = 1; l < line; l++) {
            int next = text.indexOf('\n', start);
            if (next < 0)
                return text.length();
            start = next + 1;
        }
        int pos = start, bytes = 1;
        while (pos < text.length() && text.charAt(pos) != '\n' && bytes < byteColumn) {
            int cp = text.codePointAt(pos);
            int width = cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4;
            if (bytes + width > byteColumn)
                break;
            bytes += width;
            pos += Character.charCount(cp);
        }
        return pos;
    }
}
