package com.hermes.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional CI evidence; independent of the thread enforcing the worker's hard deadline. */
final class WorkerDiagnostics implements AutoCloseable {
    private static final Pattern THREAD = Pattern.compile("^\".*\" #(\\d+).*");
    private static final Pattern STATE = Pattern.compile(
            "^\\s+java\\.lang\\.Thread\\.State: (NEW|RUNNABLE|BLOCKED|WAITING|TIMED_WAITING|TERMINATED).*");
    private static final Pattern FRAME = Pattern.compile(
            "^\\s+at ([A-Za-z0-9_.$/@<>-]+\\((?:[A-Za-z0-9_.-]+(?:@[A-Za-z0-9_.+-]+)?/)?"
                    + "[A-Za-z0-9_.$ -]+(?::\\d+)?\\))\\s*$");
    private final ProcessHandle worker;
    private final Instant started;
    private final Path command;
    private final Path directory;
    private final String prefix;
    private final AtomicReference<Process> child = new AtomicReference<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private boolean first;
    private boolean second;

    WorkerDiagnostics(String output, ProcessHandle worker, Instant started, long seconds, Path command) {
        this.worker = worker;
        this.started = started;
        this.command = command;
        this.prefix = "worker-" + worker.pid() + "-" + started.toEpochMilli();
        Path selected = null;
        try {
            if (output != null && !output.isBlank()) {
                selected = Path.of(output).toAbsolutePath();
                Files.createDirectories(selected);
                Files.writeString(selected.resolve(prefix + ".worker.txt"),
                        "workerPid=" + worker.pid() + "\nstarted=" + started + "\ntimeoutSeconds=" + seconds
                                + "\nsnapshotFractions=0.5,0.8\n",
                        StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException | RuntimeException ignored) {
            selected = null; // Evidence failures must not disable the watchdog or fail a healthy test.
        }
        this.directory = selected;
    }

    void tick(long elapsed, long duration) {
        if (directory == null || closed) return;
        if (!first && elapsed >= duration / 2) {
            first = true;
            snapshot(1);
        } else if (!second && elapsed >= duration - duration / 5) {
            second = true;
            snapshot(2);
        }
    }

    private void snapshot(int index) {
        if (!busy.compareAndSet(false, true)) return;
        Thread capture = new Thread(() -> capture(index), "test-worker-snapshot");
        capture.setDaemon(true);
        capture.start();
    }

    private void capture(int index) {
        Path raw = null;
        Process process = null;
        String evidence = "capture=unavailable\n";
        try {
            if (closed || !TestWorkerWatchdog.sameWorker(worker, started)) return;
            // The raw dump never lives in the upload directory and is deleted in finally.
            raw = Files.createTempFile("hermes-worker-stack-", ".tmp");
            // Audited: command is this JDK's jcmd; PID comes from the validated worker identity.
            // No shell, test content, credential or remote input becomes an executable or argument.
            // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
            process = new ProcessBuilder(command.toString(), Long.toString(worker.pid()), "Thread.print", "-l")
                    .redirectErrorStream(true).redirectOutput(raw.toFile()).start();
            child.set(process);
            if (closed) return;
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
                    && Files.size(raw) <= 2 * 1024 * 1024) {
                try (BufferedReader reader = Files.newBufferedReader(raw)) {
                    evidence = sanitize(reader);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            // No raw exceptions, thread names, command lines or local paths in exported evidence.
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                child.compareAndSet(process, null);
            }
            if (raw != null) {
                try { Files.deleteIfExists(raw); } catch (IOException ignored) {}
            }
            try {
                if (!closed) Files.writeString(directory.resolve(prefix + "-" + index + ".threads.txt"),
                        "workerPid=" + worker.pid() + "\nsnapshot=" + index + "\n" + evidence,
                        StandardOpenOption.CREATE_NEW);
            } catch (IOException | RuntimeException ignored) {}
            busy.set(false);
        }
    }

    static String sanitize(BufferedReader reader) throws IOException {
        StringBuilder safe = new StringBuilder("capture=completed\n");
        String line;
        int threads = 0;
        int frames = 0;
        boolean inThread = false;
        while ((line = reader.readLine()) != null && safe.length() < 256 * 1024) {
            Matcher header = THREAD.matcher(line);
            Matcher state = STATE.matcher(line);
            Matcher frame = FRAME.matcher(line);
            if (header.matches()) {
                if (++threads > 256) break;
                safe.append("thread #").append(header.group(1)).append('\n');
                frames = 0;
                inThread = true;
            } else if (inThread && state.matches()) {
                safe.append("java.lang.Thread.State: ").append(state.group(1)).append('\n');
            } else if (inThread && frame.matches() && frames++ < 96) {
                safe.append("at ").append(frame.group(1)).append('\n');
            }
        }
        return safe.toString();
    }

    @Override public void close() {
        closed = true;
        Process process = child.getAndSet(null);
        if (process != null && process.isAlive()) process.destroyForcibly();
    }
}
