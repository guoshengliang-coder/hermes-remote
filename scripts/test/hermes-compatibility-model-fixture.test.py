import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
import urllib.request
import urllib.error

spec=importlib.util.spec_from_file_location('fixture',Path(__file__).parents[1]/'hermes-compatibility-model-fixture.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

class ModelFixtureTests(unittest.TestCase):
    def test_stream_and_json_use_only_the_expected_synthetic_prompt(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'config.yaml';path.write_bytes(b'original synthetic configuration');path.chmod(0o640)
            with fixture.model_fixture(path) as state:
                config=json.loads(path.read_text())
                self.assertEqual(config['model']['provider'],'custom')
                for stream in [True,False]:
                    request=urllib.request.Request(f"http://127.0.0.1:{state['port']}/v1/chat/completions",
                        data=json.dumps({'stream':stream,'messages':[{'role':'user','content':fixture.PROMPT}]}).encode(),
                        headers={'Content-Type':'application/json'})
                    with urllib.request.urlopen(request,timeout=2) as response: data=response.read().decode()
                    if stream:
                        chunks=[json.loads(line[6:]) for line in data.splitlines() if line.startswith('data: {')]
                        self.assertEqual(''.join(c['choices'][0]['delta'].get('content','') for c in chunks),fixture.ANSWER)
                        self.assertIn('data: [DONE]',data)
                    else:self.assertEqual(json.loads(data)['choices'][0]['message']['content'],fixture.ANSWER)
                bad=urllib.request.Request(f"http://127.0.0.1:{state['port']}/v1/chat/completions",
                    data=json.dumps({'messages':[{'role':'user','content':'not the fixture'}]}).encode())
                with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(bad,timeout=2)
                self.assertEqual(error.exception.code,400)
                self.assertEqual(state['requests'],2)
            self.assertTrue(state['restored'])
            self.assertEqual(path.read_bytes(),b'original synthetic configuration')
            self.assertEqual(stat.S_IMODE(path.stat().st_mode),0o640)

    def test_failure_restores_bytes_and_mode_and_symlink_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'config.yaml';path.write_bytes(b'synthetic');path.chmod(0o600)
            with self.assertRaises(RuntimeError):
                with fixture.model_fixture(path) as state:raise RuntimeError('injected')
            self.assertTrue(state['restored']);self.assertEqual(path.read_bytes(),b'synthetic')
            link=Path(tmp)/'link';link.symlink_to(path)
            with self.assertRaises(ValueError):
                with fixture.model_fixture(link):self.fail('unsafe config accepted')
            self.assertEqual(path.read_bytes(),b'synthetic')

if __name__=='__main__':unittest.main()
