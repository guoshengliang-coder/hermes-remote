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


if __name__ == '__main__': unittest.main()
