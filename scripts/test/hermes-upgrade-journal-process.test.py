import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform == 'darwin', 'actual Desktop Core process acceptance requires macOS')
class JournalProcessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=Path(__file__).resolve().parents[2]
        cls.workspace=tempfile.TemporaryDirectory(prefix='hermes-upgrade-operation-lab-')
        cls.addClassCleanup(cls.workspace.cleanup)
        cls.helper=Path(cls.workspace.name)/'journal-helper'
        subprocess.run(['swift','build','--package-path',str(cls.source/'desktop'),'--target','HermesGoDesktopCore'],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=300)
        build=subprocess.run(['swift','build','--package-path',str(cls.source/'desktop'),'--show-bin-path'],
            check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=30)
        binary=Path(build.stdout.strip());objects=sorted((binary/'HermesGoDesktopCore.build').glob('*.o'))
        if not objects:raise RuntimeError('core_objects_missing')
        subprocess.run(['swiftc','-parse-as-library','-I',str(binary/'Modules'),
            str(cls.source/'scripts/hermes-upgrade-journal-helper.swift'),*map(str,objects),'-o',str(cls.helper)],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=120)

    def test_sigkill_preserves_complete_intent_and_exact_run_can_be_resumed(self):
        for action,phase in [('hold','backingUp'),('before-replace','quiescing'),('after-replace','backingUp')]:
            with self.subTest(action=action), tempfile.TemporaryDirectory(prefix='hermes-upgrade-operation-lab-') as directory:
                root=Path(directory).resolve()
                # The helper uses the standard temporary parent for its root guard.
                env={'PATH':'/usr/bin:/bin','HOME':str(root),'TMPDIR':tempfile.gettempdir()}
                child=subprocess.Popen([str(self.helper),action,str(root)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,env=env,start_new_session=True)
                try:
                    with selectors.DefaultSelector() as selector:
                        selector.register(child.stdout,selectors.EVENT_READ)
                        self.assertTrue(selector.select(timeout=15),'helper did not reach the selected persistence point')
                        self.assertEqual(child.stdout.readline(64),b'ready\n')
                    journal=root/'hermes-upgrade-state.json'
                    before=json.loads(journal.read_text());self.assertEqual(before['phase'],phase)
                    self.assertEqual(journal.stat().st_mode&0o777,0o600)
                    contender=subprocess.run([str(self.helper),'contend',str(root)],env=env,text=True,
                        stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=10)
                    self.assertEqual(contender.returncode,2)
                    self.assertEqual(json.loads(contender.stdout),{'status':'refused','code':'HR-HERMESUPDATE-002'})
                    child.kill();self.assertEqual(child.wait(timeout=10),-9)
                    self.assertEqual(json.loads(journal.read_text()),before)
                    resumed=subprocess.run([str(self.helper),'resume',str(root)],env=env,text=True,
                        stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=10)
                    self.assertEqual(resumed.returncode,0)
                    self.assertEqual(json.loads(resumed.stdout),{'status':'cancelled','sameRun':True,'migrationLeaseAvailable':True})
                    after=json.loads(journal.read_text())
                    self.assertEqual(after['runID'],before['runID']);self.assertEqual(after['phase'],'cancelled')
                    self.assertEqual(after['target'],before['target'])
                    self.assertFalse((root/'synthetic-home').exists(),'intent probe must never mutate an installation')
                finally:
                    if child.poll() is None:child.kill();child.wait(timeout=10)
                    child.stdin.close();child.stdout.close()


if __name__=='__main__':unittest.main()
