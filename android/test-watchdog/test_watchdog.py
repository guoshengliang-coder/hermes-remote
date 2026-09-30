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
        cls.temp = tempfile.TemporaryDirectory()
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
        System.out.println("READY");
        System.out.flush();
        while (true) Thread.sleep(100);
    }
}
''')
        agent = Path(__file__).parent / 'src/com/hermes/testing/TestWorkerWatchdog.java'
        subprocess.run([cls.javac, '--release', '17', '-d', str(cls.classes), str(agent), str(cls.root / 'Probe.java')], check=True)
        manifest = cls.root / 'manifest'
        manifest.write_text('Premain-Class: com.hermes.testing.TestWorkerWatchdog\n')
        cls.jar = cls.root / 'watchdog.jar'
        subprocess.run([cls.jar_tool, 'cfm', str(cls.jar), str(manifest), '-C', str(cls.classes), '.'], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def spawn(self, guarded, mode='hang'):
        return subprocess.Popen([self.java, *([f'-javaagent:{self.jar}=1'] if guarded else []),
                                 '-cp', str(self.classes), 'Probe', mode],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def stop(self, proc):
        if proc.poll() is None:
            proc.kill()
        proc.communicate(timeout=5)

    def test_normal_exit_is_success_and_watcher_releases_pipes(self):
        proc = self.spawn(True, 'exit')
        out, err = proc.communicate(timeout=5)
        self.assertEqual(0, proc.returncode)
        self.assertNotIn('HR-TEST-001', err)

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
        proc = self.spawn(True)
        try:
            self.assertEqual('READY', other.stdout.readline().strip())
            self.assertEqual('READY', proc.stdout.readline().strip())
            proc.terminate()  # SIGTERM enters a deliberately stuck shutdown hook.
            time.sleep(.15)
            self.assertIsNone(proc.poll())
            out, err = proc.communicate(timeout=5)
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
