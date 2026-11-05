"""Run every watchdog case in an isolated process, with bounded host-aware concurrency."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import unittest

import test_watchdog


def discover_cases():
    def flatten(suite):
        for item in suite:
            if isinstance(item, unittest.TestSuite):
                yield from flatten(item)
            else:
                identity = item.id()
                prefix = test_watchdog.__name__ + '.'
                if not identity.startswith(prefix):
                    raise RuntimeError('Unexpected watchdog test identity')
                yield identity[len(prefix):]
    return list(flatten(unittest.TestLoader().loadTestsFromModule(test_watchdog)))


def available_workers():
    cpus = len(os.sched_getaffinity(0)) if hasattr(os, 'sched_getaffinity') else (os.cpu_count() or 1)
    return max(1, min(3, cpus))


def complete(result, count):
    # A successful exit without all expected unittest cases is not verification.
    output = result.stdout + result.stderr
    return (result.returncode == 0
            and re.search(rf'^Ran {count} tests? in ', output, re.MULTILINE) is not None
            and re.search(r'^OK$', output, re.MULTILINE) is not None
            and not re.search(r'\b(?:skipped|expected failures|unexpected successes)=', output))


def run():
    cases = discover_cases()
    if not cases:
        raise RuntimeError('No watchdog cases discovered')
    workers = min(available_workers(), len(cases))
    script = str(Path(__file__).with_name('test_watchdog.py'))
    start = time.monotonic()
    if workers == 1:
        result = subprocess.run([sys.executable, script, '-v'], capture_output=True, text=True)
        print(result.stdout, end=''); print(result.stderr, end='', file=sys.stderr)
        passed = len(cases) if complete(result, len(cases)) else 0
    else:
        # Start the longest timed case first; discovery still supplies the entire case set.
        ordered = sorted(cases, key=lambda name: (not name.endswith('.test_stalled_worker_has_sanitized_stack_evidence_before_hard_stop'), name))

        def execute(name):
            return subprocess.run([sys.executable, script, name, '-v'],
                                  capture_output=True, text=True)

        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(execute, ordered))
        passed = 0
        for result in results:
            print(result.stdout, end=''); print(result.stderr, end='', file=sys.stderr)
            passed += complete(result, 1)
    summary = {'cases': cases, 'workers': workers, 'passed': passed, 'total': len(cases),
               'elapsedSeconds': time.monotonic() - start}
    print('WATCHDOG_RESULTS ' + json.dumps(summary), flush=True)
    return 0 if passed == len(cases) else 1


if __name__ == '__main__':
    sys.exit(run())
