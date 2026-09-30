package com.hermes.testing;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Build-only agent: an independent JVM enforces a wall-clock limit on exactly this test fork. */
public final class TestWorkerWatchdog {
    private static Process watcher; // Retain its stdin pipe until the worker actually exits.
    private static volatile boolean workerExited;
    public static void premain(String seconds, Instrumentation ignored) throws Exception {
        long timeoutSeconds = Long.parseLong(seconds);
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeout must be positive");
        ProcessHandle worker = ProcessHandle.current();
        String jar = Path.of(TestWorkerWatchdog.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString();
        // Audited: executable comes from this JVM, jar from this agent's CodeSource, and remaining
        // dynamic arguments are this worker's identity and a validated numeric deadline. An argv
        // vector is used, never a shell; no work-item, network or test-content input reaches it.
        // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
        watcher = new ProcessBuilder(
                java,
                "-Xmx32m", "-XX:+UseSerialGC", "-cp", jar, TestWorkerWatchdog.class.getName(),
                Long.toString(worker.pid()), worker.info().startInstant().orElseThrow().toString(),
                Long.toString(timeoutSeconds))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    public static void main(String[] args) throws Exception {
        ProcessHandle worker = ProcessHandle.of(Long.parseLong(args[0])).orElse(null);
        Instant started = Instant.parse(args[1]);
        // A dead worker can remain a zombie while Gradle drains stderr. Its stdin pipe closes
        // at actual exit, avoiding a false timeout and letting the watcher release those pipes.
        Thread eof = new Thread(() -> {
            try { while (System.in.read() != -1) {} }
            catch (java.io.IOException ignored) { /* A closed parent pipe also means exit. */ }
            workerExited = true;
        }, "test-worker-exit");
        eof.setDaemon(true);
        eof.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[2]));
        while (!workerExited && sameWorker(worker, started)) {
            if (System.nanoTime() >= deadline) {
                System.err.println("HR-TEST-001 测试进程超时，已强制终止。修复卡住的测试后重试。 / "
                        + "Test worker timed out and was forcibly stopped. Fix the hanging test, then retry.");
                // No SIGTERM/coroutine cooperation needed; never select processes by name.
                if (sameWorker(worker, started)) worker.destroyForcibly();
                return;
            }
            Thread.sleep(100);
        }
    }

    private static boolean sameWorker(ProcessHandle worker, Instant started) {
        return worker != null && worker.isAlive()
                && worker.info().startInstant().filter(started::equals).isPresent();
    }
}
