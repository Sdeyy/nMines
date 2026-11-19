package dev.sdeyy.nmines.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class AtomicFile {
    private AtomicFile() { }
    public static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            move(temp, target);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
