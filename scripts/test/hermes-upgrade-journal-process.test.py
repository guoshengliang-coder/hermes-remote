import json
import importlib.util
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid


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
        cls.recovery_helper=Path(cls.workspace.name)/'recovery-helper'
        subprocess.run(['swiftc','-parse-as-library','-I',str(binary/'Modules'),
            str(cls.source/'scripts/hermes-recovery-point-helper.swift'),*map(str,objects),'-o',str(cls.recovery_helper)],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=120)
        cls.pinned_helper=Path(cls.workspace.name)/'pinned-helper'
        subprocess.run(['swiftc','-parse-as-library','-I',str(binary/'Modules'),
            str(cls.source/'scripts/hermes-pinned-upgrade-helper.swift'),*map(str,objects),'-o',str(cls.pinned_helper)],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=120)

    def test_pinned_history_probe_keeps_prior_logs_and_uses_unique_names(self):
        spec=importlib.util.spec_from_file_location('pinned_lab_test',self.source/'scripts/hermes-pinned-upgrade-lab.py')
        lab=importlib.util.module_from_spec(spec);spec.loader.exec_module(lab)
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();workspace=root/'probe';workspace.mkdir()
            existing=root/'candidateHistory.private.log';existing.write_bytes(b'old ready marker')
            seen=[]
            def fake_probe(root,profile,env,launcher,name,version):
                log=root/(name+'.private.log')
                self.assertIn('(literal "'+str(log)+'")',profile.read_text())
                self.assertIn('(deny network-outbound)',profile.read_text())
                with log.open('xb') as output:output.write(b'fresh')
                seen.append(name)
                return {'historyPresent':True}
            with patch.object(lab.base,'probe_history',side_effect=fake_probe):
                for _ in range(2):
                    self.assertTrue(lab.probe_history(root,workspace,{},root/'launcher','candidateHistory','0.22.5')['historyPresent'])
            self.assertEqual(len(set(seen)),2)
            self.assertEqual(existing.read_bytes(),b'old ready marker')

    def test_recovery_material_normalizes_macos_aliases_but_refuses_other_roots(self):
        spec=importlib.util.spec_from_file_location('pinned_paths_test',self.source/'scripts/hermes-pinned-upgrade-lab.py')
        lab=importlib.util.module_from_spec(spec);spec.loader.exec_module(lab)
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();run=str(uuid.uuid4())
            home=root/'user/.hermes';home.mkdir(parents=True)
            points=root/('native-recovery-'+run)/'points';staged=points/'materialized/tree-0';staged.mkdir(parents=True)
            # Exercise the spelling Foundation returns for standard macOS temporary paths.
            alias=lambda p:str(p).removeprefix('/private') if str(p).startswith('/private/var/') else str(p)
            material={'runID':run.upper(),'roots':[{'original':alias(home),'staged':alias(staged)}]}
            self.assertEqual(lab.checked_material_roots(root,run,material,[home]),[(home,staged)])
            with patch.object(lab,'idle',return_value=False):
                with self.assertRaises(lab.base.LabRefusal):lab.checked_material_roots(root,run,material,[home])
            unrelated=root/'unrelated';unrelated.mkdir()
            material['roots'][0]['original']=str(unrelated)
            with self.assertRaises(lab.base.LabRefusal):lab.checked_material_roots(root,run,material,[home])
            material['roots'][0]={'original':str(home),'staged':str(unrelated)}
            with self.assertRaises(lab.base.LabRefusal):lab.checked_material_roots(root,run,material,[home])
            home.rmdir();home.symlink_to(unrelated,target_is_directory=True)
            material['roots'][0]={'original':str(home),'staged':str(staged)}
            with self.assertRaises(lab.base.LabRefusal):lab.checked_material_roots(root,run,material,[home])

    def test_pinned_installer_helper_refuses_unprepared_labs_without_writing(self):
        for prefix in ['not-a-hermes-lab-', 'hermes-compat-lab-']:
            with tempfile.TemporaryDirectory(prefix=prefix) as directory:
                root=Path(directory).resolve()
                result=subprocess.run([str(self.pinned_helper),str(root),str(uuid.uuid4()),'normal'],
                    env={'PATH':'/usr/bin:/bin','TMPDIR':tempfile.gettempdir(),'HOME':str(root)},
                    text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15)
                self.assertEqual(result.returncode,2)
                self.assertEqual(json.loads(result.stdout),{'status':'refused','code':'HR-HERMESUPDATE-002'})
                self.assertEqual(list(root.iterdir()),[])

    def test_pinned_upgrade_lab_refuses_a_nonlab_with_bilingual_error(self):
        with tempfile.TemporaryDirectory(prefix='not-a-hermes-lab-') as directory:
            result=subprocess.run([sys.executable,'-B',str(self.source/'scripts/hermes-pinned-upgrade-lab.py'),
                '--lab',directory],text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15)
            self.assertEqual(result.returncode,2)
            error=json.loads(result.stdout)['error']
            self.assertEqual(error['code'],'HR-HERMESUPDATE-002')
            self.assertTrue(error['summaryZh']);self.assertTrue(error['summaryEn'])
            self.assertFalse(error['retryable']);self.assertNotIn(directory,result.stdout)
            missing=Path(directory)/'missing'
            result=subprocess.run([sys.executable,'-B',str(self.source/'scripts/hermes-pinned-upgrade-lab.py'),
                '--lab',str(missing)],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
            self.assertEqual(result.returncode,2);self.assertEqual(result.stderr,'')
            self.assertEqual(json.loads(result.stdout)['error']['code'],'HR-HERMESUPDATE-002')
            self.assertNotIn(str(missing),result.stdout)

    def test_recovery_material_survives_process_exit_and_keeps_new_candidate_data(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();home=root/'user/.hermes'
            runtime=root/'user/.local/share/uv/python/cpython-synthetic'
            (home/'hermes-agent/venv/bin').mkdir(parents=True);(runtime/'bin').mkdir(parents=True)
            python=runtime/'bin/python';python.write_text('#!/bin/sh\nexit 0\n');python.chmod(0o700)
            (home/'hermes-agent/venv/bin/python').symlink_to(python)
            (home/'state.db').write_bytes(b'old synthetic history')
            run=str(uuid.uuid4());env={'PATH':'/usr/bin:/bin','HOME':str(root),'TMPDIR':tempfile.gettempdir()}
            captured=subprocess.run([str(self.recovery_helper),'capture',str(root),run],env=env,
                text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=30)
            self.assertEqual(captured.returncode,0);self.assertEqual(json.loads(captured.stdout),{'status':'captured'})
            # Both native helpers must load the same state directory. No installer is supplied,
            # so this reaches the captured intent and then refuses before any upstream execution.
            refused=subprocess.run([str(self.pinned_helper),str(root),run,'normal'],env=env,
                text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15)
            self.assertEqual(refused.returncode,2)
            frames=[json.loads(line) for line in refused.stdout.splitlines()]
            self.assertIn({'checkpoint':'intent-resumed'},frames)
            self.assertEqual(frames[-1],{'status':'refused','code':'HR-HERMESUPDATE-002'})
            (home/'state.db').write_bytes(b'new synthetic history')
            restored=subprocess.run([str(self.recovery_helper),'materialize',str(root),run],env=env,
                text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=30)
            self.assertEqual(restored.returncode,0);self.assertEqual(json.loads(restored.stdout),{'status':'materialized'})
            workspace=root/('native-recovery-'+run)
            plan=json.loads((workspace/'material.json').read_text())
            self.assertEqual(plan['runID'].lower(),run)
            self.assertEqual(len(plan['roots']),2)
            staged=Path(plan['roots'][0]['staged']).resolve()
            self.assertTrue(staged.is_relative_to(workspace/'points'))
            self.assertEqual((staged/'state.db').read_bytes(),b'old synthetic history')
            self.assertEqual((home/'state.db').read_bytes(),b'new synthetic history')

    def test_recovery_lab_refusal_has_registered_bilingual_error_without_private_path(self):
        with tempfile.TemporaryDirectory(prefix='not-a-hermes-lab-') as directory:
            result=subprocess.run([sys.executable,'-B',str(self.source/'scripts/hermes-recovery-point-lab.py'),
                '--lab',directory],text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15)
            self.assertEqual(result.returncode,2)
            payload=json.loads(result.stdout);error=payload['error']
            self.assertEqual(error['code'],'HR-HERMESUPDATE-002')
            self.assertFalse(error['retryable']);self.assertTrue(error['summaryZh']);self.assertTrue(error['summaryEn'])
            self.assertEqual(error['action'],'check_case_and_cache');self.assertNotIn(directory,result.stdout)

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
