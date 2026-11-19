package dev.sdeyy.nmines.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** One ordered writer. Only immutable text crosses threads; pending snapshots coalesce per file. */
public final class WriteQueue implements AutoCloseable {
    @FunctionalInterface public interface Sink { void write(Path file, String content) throws IOException; }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "nMines-writer"); thread.setDaemon(true); return thread;
    });
    private final Map<Path, String> pending = new LinkedHashMap<>(), failed = new LinkedHashMap<>();
    private final Sink sink;
    private final BiConsumer<Path, IOException> errors;
    private IOException failure;
    private boolean running, closed;
    public WriteQueue(BiConsumer<Path, IOException> errors) { this(AtomicFile::write, errors); }
    public WriteQueue(Sink sink, BiConsumer<Path, IOException> errors) { this.sink = sink; this.errors = errors; }
    public synchronized void checkHealthy() throws IOException { if (failure != null) throw new IOException("A queued save failed; retry with /nm tasks save", failure); }
    public synchronized void submit(Path file, String content) {
        if (closed) throw new IllegalStateException("Writer is closed");
        pending.put(file, content); failed.remove(file); start();
    }
    private void start() { if (!running && !pending.isEmpty()) { running = true; worker.execute(this::drain); } }
    private void drain() {
        while (true) {
            Path file; String content;
            synchronized (this) {
                if (pending.isEmpty()) { running = false; notifyAll(); return; }
                file = pending.keySet().iterator().next(); content = pending.remove(file);
            }
            try {
                sink.write(file, content);
                synchronized (this) { failed.remove(file); if (failed.isEmpty()) failure = null; }
            } catch (IOException | RuntimeException e) {
                IOException error = e instanceof IOException ? (IOException) e : new IOException(e);
                synchronized (this) { if (!pending.containsKey(file)) failed.put(file, content); failure = error; }
                try { errors.accept(file, error); } catch (RuntimeException ignored) { /* Keep draining other files. */ }
            }
        }
    }
    /** Explicit barrier for reload/shutdown and recovery; ordinary edits never wait for disk. */
    public synchronized void flush() throws IOException {
        for (Map.Entry<Path, String> entry : failed.entrySet()) pending.putIfAbsent(entry.getKey(), entry.getValue());
        failed.clear(); start();
        while (running) {
            try { wait(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while saving", e); }
        }
        checkHealthy();
    }
    @Override public void close() throws IOException {
        synchronized (this) { if (closed) return; closed = true; }
        try { flush(); } finally { worker.shutdown(); }
    }
}
