import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('contract_lab', Path(__file__).parents[1] / 'hermes-compatibility-contract-lab.py')
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)


class ContractLabTests(unittest.TestCase):
    def test_daily_home_is_rejected_before_runtime_execution(self):
        with mock.patch('sys.argv', ['probe', '--lab-root', str(Path.home())]), mock.patch.object(lab, 'probe') as runner:
            with self.assertRaisesRegex(ValueError, 'not_disposable_lab'):
                lab.main()
            runner.assert_not_called()

    def test_candidate_failure_preserves_both_runtimes_and_restores_original_path(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root = Path(directory).resolve()
            for name, value in [('user', 'old'), ('candidate-after-experiment', 'new')]:
                (root / name).mkdir()
                (root / name / 'identity').write_text(value)
            (root / 'report.json').write_text(json.dumps({'kind':'runtime-rehearsal', 'status':'runtime_stage_passed'}))
            installer = types.SimpleNamespace(RUNTIME_SOURCE='old', RUNTIME_TARGET='new',
                git_read=lambda path, *args: (path.parents[1] / 'identity').read_bytes())
            stages = []
            def run(active_root, _lab, version, stage, node):
                stages.append(version)
                if version == '0.22.5':
                    self.assertEqual((active_root/'user/identity').read_text(), 'new')
                    (active_root/'user/new-synthetic-data').write_text('preserve')
                    raise TimeoutError('synthetic probe failure')
                return {'checks':{'fixtureOnly':True}}
            with mock.patch('sys.argv',['probe','--lab-root',str(root)]), mock.patch.object(lab,'load_lab',return_value=installer), \
                    mock.patch.object(lab,'probe',side_effect=run), mock.patch.object(lab.shutil,'which',return_value='/synthetic/node'):
                with self.assertRaises(TimeoutError): lab.main()
            self.assertEqual(stages,['0.21.3','0.22.5'])
            self.assertEqual((root/'user/identity').read_text(),'old')
            self.assertEqual((root/'candidate-after-experiment/identity').read_text(),'new')
            self.assertEqual((root/'candidate-after-experiment/new-synthetic-data').read_text(),'preserve')
            self.assertFalse((root/'contract-probe-old').exists())

    def test_prompt_network_fence_precedes_model_config_and_upstream_execution(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory);(root/'tmp').mkdir();(root/'isolation.sb').write_text('(version 1)\n(allow default)\n')
            installer=types.SimpleNamespace(isolated_environment=lambda root:{})
            with mock.patch.object(lab.subprocess,'run',return_value=types.SimpleNamespace(returncode=2)), \
                    mock.patch.object(lab,'runtime_probe') as runner:
                with self.assertRaisesRegex(ValueError,'network_fence_failed'):
                    lab.prompt_probe(root,installer,'0.21.3','fixture-stage','/synthetic/node')
                runner.assert_not_called()
            self.assertFalse((root/'user').exists())

    def test_restart_keeps_checkpoint_private_and_restores_configuration(self):
        for failure in (False, True):
            with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
                root=Path(directory);(root/'tmp').mkdir();(root/'isolation.sb').write_text('(version 1)\n(allow default)\n')
                config=root/'user/.hermes/config.yaml';config.parent.mkdir(parents=True);config.write_text('synthetic original');config.chmod(0o600)
                installer=types.SimpleNamespace(isolated_environment=lambda root:{},sha256=lambda data:'a'*64)
                calls=[]
                def run(*args, **kwargs):
                    calls.append(kwargs)
                    if kwargs.get('restart_state'):
                        self.assertTrue(kwargs['restart_state']['stopped'])
                        self.assertEqual(kwargs['restart_state']['token'],'synthetic-private')
                        self.assertNotEqual(config.read_text(),'synthetic original')
                        if failure:raise TimeoutError('synthetic restart failure')
                        return {'attempted':True,'checks':{'restartHistory':True}}
                    self.assertTrue(kwargs['capture_restart'])
                    return {'checks':{'promptHistory':True},'_restart':{'stopped':True,'token':'synthetic-private',
                        'port':12345,'pid':123,'checkpoint':{'storedId':'synthetic-private'}}}
                with mock.patch.object(lab.subprocess,'run',return_value=types.SimpleNamespace(returncode=0)), \
                    mock.patch.object(lab,'runtime_probe',side_effect=run):
                    if failure:
                        with self.assertRaises(TimeoutError):lab.prompt_probe(root,installer,'0.21.3','fixture','/synthetic/node',restart=True)
                    else:
                        result=lab.prompt_probe(root,installer,'0.21.3','fixture','/synthetic/node',restart=True)
                        self.assertNotIn('synthetic-private',json.dumps(result))
                        self.assertTrue(result['restart']['checks']['restartConfigurationRestored'])
                self.assertEqual(len(calls),2)
                self.assertEqual(config.read_text(),'synthetic original')

    def test_restart_rejects_live_previous_backend_before_starting_another(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory);(root/'tmp').mkdir();(root/'isolation.sb').write_text('(version 1)\n(allow default)\n')
            config=root/'user/.hermes/config.yaml';config.parent.mkdir(parents=True);config.write_text('synthetic original')
            installer=types.SimpleNamespace(isolated_environment=lambda root:{})
            with mock.patch.object(lab.subprocess,'run',return_value=types.SimpleNamespace(returncode=0)), \
                mock.patch.object(lab,'runtime_probe',return_value={'checks':{},'_restart':{'stopped':False}}) as runner:
                with self.assertRaisesRegex(ValueError,'previous_backend_alive'):
                    lab.prompt_probe(root,installer,'0.21.3','fixture','/synthetic/node',restart=True)
                self.assertEqual(runner.call_count,1)
            self.assertEqual(config.read_text(),'synthetic original')

    def test_missing_completed_turn_preserves_rpc_failure_and_does_not_start_restart(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory);(root/'tmp').mkdir();(root/'isolation.sb').write_text('(version 1)\n(allow default)\n')
            config=root/'user/.hermes/config.yaml';config.parent.mkdir(parents=True);config.write_text('synthetic original')
            installer=types.SimpleNamespace(isolated_environment=lambda root:{},sha256=lambda data:'a'*64)
            with mock.patch.object(lab.subprocess,'run',return_value=types.SimpleNamespace(returncode=0)), \
                mock.patch.object(lab,'runtime_probe',return_value={'checks':{'promptHistory':False},'_restart':{'stopped':True,'checkpoint':None}}) as runner:
                result=lab.prompt_probe(root,installer,'0.21.3','fixture','/synthetic/node',restart=True)
                self.assertEqual(runner.call_count,1)
                self.assertFalse(result['checks']['promptHistory'])
                self.assertEqual(result['restart']['checks'],{})
                self.assertFalse(result['restart']['attempted'])
            self.assertEqual(config.read_text(),'synthetic original')

    def test_uncertain_launchd_cleanup_quarantines_candidate_without_moving_its_live_paths(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();(root/'tmp').mkdir()
            for name,value in [('user','old'),('candidate-after-experiment','new')]:
                (root/name).mkdir();(root/name/'identity').write_text(value)
            (root/'report.json').write_text(json.dumps({'kind':'runtime-rehearsal','status':'runtime_stage_passed'}))
            installer=types.SimpleNamespace(RUNTIME_SOURCE='old',RUNTIME_TARGET='new',git_read=lambda p,*args:(p.parents[1]/'identity').read_bytes())
            agent=types.SimpleNamespace(label='com.hermesgo.compat-lab.synthetic',workspace=root/'tmp',pid=123,
                stop=mock.Mock(side_effect=ValueError('uncertain_cleanup')))
            def run(_root,_lab,version,*args):
                if version=='0.22.5':lab.stop_agent(root,agent)
                return {'checks':{'fixtureOnly':True}}
            with mock.patch('sys.argv',['probe','--lab-root',str(root)]),mock.patch.object(lab,'load_lab',return_value=installer), \
                mock.patch.object(lab,'probe',side_effect=run),mock.patch.object(lab.shutil,'which',return_value='/synthetic/node'):
                with self.assertRaisesRegex(ValueError,'uncertain_cleanup'):lab.main()
            self.assertEqual((root/'user/identity').read_text(),'new')
            self.assertEqual((root/'contract-probe-old/identity').read_text(),'old')
            self.assertEqual(lab.cleanup_marker(root).stat().st_mode&0o777,0o600)
            with mock.patch('sys.argv',['probe','--lab-root',str(root)]),mock.patch.object(lab,'load_lab') as loader:
                with self.assertRaisesRegex(ValueError,'previous_launchagent_needs_cleanup'):lab.main()
                loader.assert_not_called()


if __name__ == '__main__': unittest.main()
