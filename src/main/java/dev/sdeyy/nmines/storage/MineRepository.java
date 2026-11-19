package dev.sdeyy.nmines.storage;

import dev.sdeyy.nmines.model.Mine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** One atomic document: rename, delete and bulk edits commit together. */
public final class MineRepository {
    private final Path file;
    public final MineCodec codec = new MineCodec();
    public MineRepository(Path file) { this.file = file; }
    public Map<String, Mine> load() throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        return codec.decode(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
    public void save(Map<String, Mine> mines) throws IOException { AtomicFile.write(file, codec.encode(mines)); }
}
