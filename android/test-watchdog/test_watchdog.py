"""Real subprocess checks: no Android SDK or Gradle needed (JDK 17+)."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


class WatchdogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="watchdog space ;$(literal) ")
        cls.root = Path(cls.temp.name)
        cls.java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
        cls.javac = str(Path(os.environ['JAVA_HOME']) / 'bin/javac') if 'JAVA_HOME' in os.environ else 'javac'
        cls.jar_tool = str(Path(os.environ['JAVA_HOME']) / 'bin/jar') if 'JAVA_HOME' in os.environ else 'jar'
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        (cls.root / 'Probe.java').write_text('''
public class Probe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("exit")) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
        }));
        Thread.currentThread().setName("synthetic-private-thread-token");
        System.out.println("READY");
        System.out.flush();
        while (true) Thread.sleep(100);
    }
}
''')
        agent = Path(__file__).parent / 'src/com/hermes/testing/TestWorkerWatchdog.java'
        harness = cls.root / 'DiagnosticsHarness.java'
        harness.write_text('''
package com.hermes.testing;
public class DiagnosticsHarness {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("sanitize")) {
            System.out.print(WorkerDiagnostics.sanitize(new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))));
        } else {
            ProcessHandle worker = ProcessHandle.of(Long.parseLong(args[1])).orElseThrow();
            TestWorkerWatchdog.watch(worker, worker.info().startInstant().orElseThrow(), 3,
                args[2], java.nio.file.Path.of(args[3]));
        }
    }
}
''')
        subprocess.run([cls.javac, '--release', '17', '-d', str(cls.classes),
                        *(str(file) for file in sorted(agent.parent.glob('*.java'))),
                        str(cls.root / 'Probe.java'), str(harness)], check=True)
        manifest = cls.root / 'manifest'
        manifest.write_text('Premain-Class: com.hermes.testing.TestWorkerWatchdog\n')
        cls.jar = cls.root / 'watchdog.jar'
        subprocess.run([cls.jar_tool, 'cfm', str(cls.jar), str(manifest), '-C', str(cls.classes), '.'], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def spawn(self, guarded, mode='hang', timeout=3, diagnostics=None):
        return subprocess.Popen([self.java, *([f'-javaagent:{self.jar}={timeout}'] if guarded else []),
                                 *([f'-Dhermes.test.diagnostics.dir={diagnostics}'] if diagnostics else []),
                                 '-cp', str(self.classes), 'Probe', mode],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def test_stalled_worker_has_sanitized_stack_evidence_before_hard_stop(self):
        with tempfile.TemporaryDirectory(dir=self.root) as output:
            other = self.spawn(False)
            proc = self.spawn(True, timeout=6, diagnostics=output)
            try:
                self.assertEqual('READY', proc.stdout.readline().strip())
                self.assertEqual('READY', other.stdout.readline().strip())
                out, err = proc.communicate(timeout=12)
                self.assertNotEqual(0, proc.returncode)
                self.assertIn('HR-TEST-001', err)
                files = list(Path(output).glob('*.threads.txt'))
                self.assertTrue(files, 'capture must precede the hard stop')
                evidence = '\n'.join(file.read_text() for file in files)
                self.assertIn('Probe.main(Probe.java:', evidence)
                self.assertIn('java.lang.Thread.State:', evidence)
                self.assertNotIn('synthetic-private-thread-token', evidence)
                self.assertNotIn(str(self.root), evidence)
                self.assertNotIn('JNI global refs', evidence)
                self.assertIsNone(other.poll())
            finally:
                self.stop(proc)
                self.stop(other)

    def stop(self, proc):
        if proc.poll() is None:
            proc.kill()
        proc.communicate(timeout=10)

    def test_normal_exit_is_success_and_watcher_releases_pipes(self):
        output = self.root / 'normal-exit-evidence'
        for destination in (None, output):
            proc = self.spawn(True, 'exit', timeout=5, diagnostics=destination)
            out, err = proc.communicate(timeout=10)
            self.assertEqual(0, proc.returncode)
            self.assertNotIn('HR-TEST-001', err)
        self.assertEqual([], list(output.glob('*.threads.txt')))

    def test_blocked_collector_does_not_postpone_hard_timeout_or_survive_watcher(self):
        fake = self.root / 'blocked-jcmd'
        fake.write_text('#!/bin/sh\necho "$$" > "$0.pid"\nexec sleep 30\n')
        fake.chmod(0o700)
        worker = self.spawn(False)
        guard = None
        try:
            self.assertEqual('READY', worker.stdout.readline().strip())
            guard = subprocess.Popen([self.java, '-cp', str(self.classes), 'com.hermes.testing.DiagnosticsHarness',
                                      'watch', str(worker.pid), str(self.root / 'blocked-evidence'), str(fake)],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            # Keep stdin open so EOF cannot disguise a postponed hard deadline.
            self.assertEqual(0, guard.wait(timeout=6))
            out, err = guard.communicate(timeout=2)
            self.assertIn('HR-TEST-001', err)
            self.assertNotEqual(0, worker.wait(timeout=2))
            child_pid = int(Path(str(fake) + '.pid').read_text())
            # The collector belongs to this watcher; cleanup must never select JVMs by name.
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                try: os.kill(child_pid, 0)
                except ProcessLookupError: break
                state = subprocess.run(['ps', '-p', str(child_pid), '-o', 'stat='], capture_output=True, text=True)
                if not state.stdout.strip() or state.stdout.strip().startswith('Z'): break
                time.sleep(.05)
            else: self.fail('collector survived the watcher')
        finally:
            if guard is not None: self.stop(guard)
            self.stop(worker)

    def test_snapshot_export_whitelists_frames_and_bounds_output(self):
        raw = ('pid-secret\n"synthetic-private-thread-token" #9 prio=5\n'
               '   java.lang.Thread.State: TIMED_WAITING (private-cause)\n'
               '        at java.base@21/java.lang.Thread.sleep0(Native Method)\n'
               '        at com.hermes.client.Test.case(Test.kt:12)\n'
               '        - locked <0x123> (private object details)\nJNI global refs: private\n')
        result = subprocess.run([self.java, '-cp', str(self.classes), 'com.hermes.testing.DiagnosticsHarness', 'sanitize'],
                                input=raw * 400, capture_output=True, text=True, check=True)
        self.assertIn('thread #9', result.stdout)
        self.assertIn('com.hermes.client.Test.case(Test.kt:12)', result.stdout)
        self.assertIn('java.base@21/java.lang.Thread.sleep0(Native Method)', result.stdout)
        self.assertNotIn('private', result.stdout)
        self.assertNotIn('JNI', result.stdout)
        self.assertLessEqual(result.stdout.count('thread #9'), 256)

    def test_mismatched_start_time_does_not_kill_a_reused_pid(self):
        other = self.spawn(False)
        guard = None
        try:
            self.assertEqual('READY', other.stdout.readline().strip())
            guard = subprocess.Popen(
                [self.java, '-cp', str(self.jar), 'com.hermes.testing.TestWorkerWatchdog',
                 str(other.pid), '1970-01-01T00:00:00Z', '1'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            # Hold stdin open: EOF must not conceal a missing process-identity check.
            self.assertEqual(0, guard.wait(timeout=3))
            out, err = guard.communicate(timeout=3)
            self.assertNotIn('HR-TEST-001', err)
            self.assertIsNone(other.poll())
        finally:
            if guard is not None:
                self.stop(guard)
            self.stop(other)

    def test_hard_timeout_overcomes_stuck_shutdown_and_leaves_other_jvm_alive(self):
        other = self.spawn(False)
        proc = self.spawn(True, diagnostics=self.root / 'Probe.java')  # Unwritable directory is best effort.
        try:
            self.assertEqual('READY', other.stdout.readline().strip())
            self.assertEqual('READY', proc.stdout.readline().strip())
            proc.terminate()  # SIGTERM enters a deliberately stuck shutdown hook.
            time.sleep(.15)
            self.assertIsNone(proc.poll())
            out, err = proc.communicate(timeout=10)
            self.assertNotEqual(0, proc.returncode)
            self.assertIn('HR-TEST-001', err)
            self.assertIn('测试进程超时，已强制终止。修复卡住的测试后重试。', err)
            self.assertIn('Test worker timed out and was forcibly stopped. Fix the hanging test, then retry.', err)
            self.assertIsNone(other.poll())
            self.assertNotIn(str(self.root), err)  # Static diagnostic never exposes local paths.
            registry = (Path(__file__).parents[2] / 'docs/ERROR_HANDLING.md').read_text()
            row = next(line for line in registry.splitlines() if line.startswith('| `HR-TEST-001` |'))
            self.assertIn('| No (fix test first) |', row)
        finally:
            self.stop(proc)
            self.stop(other)


if __name__ == '__main__':
    unittest.main()
