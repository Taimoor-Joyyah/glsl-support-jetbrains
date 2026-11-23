package com.glslsupport.bridge;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.function.BooleanSupplier;

public final class SpirvExport {
    private SpirvExport() {}
    public static void write(Path destination, NativeCompiler.Result compiled, boolean overwrite,
                             BooleanSupplier current) throws IOException {
        int[] words = compiled.words();
        if (!compiled.success() || words.length < 5 || words[0] != 0x07230203)
            throw new IOException("No validated SPIR-V module is available.");
        Path target = destination.toAbsolutePath();
        if (!current.getAsBoolean()) throw new IOException("The shader changed. Export its refreshed compilation.");
        Path temp = Files.createTempFile(target.getParent(), ".glsl-export-", ".tmp");
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);
                for (int word : words) {
                    if (!buffer.hasRemaining()) flush(buffer, channel);
                    buffer.putInt(word);
                }
                flush(buffer, channel);
            }
            if (!current.getAsBoolean()) throw new IOException("The shader changed. Export its refreshed compilation.");
            if (overwrite) Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
    private static void flush(ByteBuffer buffer, FileChannel channel) throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) channel.write(buffer);
        buffer.clear();
    }
}
