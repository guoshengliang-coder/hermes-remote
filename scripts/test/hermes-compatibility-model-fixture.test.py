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

    def test_clarify_requires_exact_correlated_tool_result(self):
        request={'messages':[{'role':'user','content':fixture.CLARIFY_PROMPT}],
                 'tools':[{'type':'function','function':{'name':'clarify'}}]}
        message, reason=fixture.clarify_message(request)
        self.assertEqual(reason,'tool_calls')
        self.assertEqual(json.loads(message['tool_calls'][0]['function']['arguments'])['questions'][0]['question'],fixture.CLARIFY_QUESTION)
        request['messages'] += [message,{'role':'tool','tool_call_id':fixture.CLARIFY_CALL,
            'content':json.dumps({'responses':[{'question':fixture.CLARIFY_QUESTION,'user_response':fixture.CLARIFY_ANSWER}]})}]
        self.assertEqual(fixture.clarify_message(request),({'role':'assistant','content':fixture.CLARIFY_REPLY},'stop'))
        for value in ({'responses':[]},{'responses':[{'question':fixture.CLARIFY_QUESTION,'user_response':'wrong'}]},
                      {'responses':[{'question':fixture.CLARIFY_QUESTION,'user_response':fixture.CLARIFY_ANSWER}],'timed_out':True}):
            request['messages'][-1]['content']=json.dumps(value)
            with self.assertRaises(ValueError):fixture.clarify_message(request)

    def test_clarify_http_roundtrip_in_stream_and_json_modes(self):
        for stream in [False,True]:
            with tempfile.TemporaryDirectory() as tmp:
                path=Path(tmp)/'config.yaml';path.write_text('original');path.chmod(0o600)
                with fixture.model_fixture(path) as state:
                    request={'stream':stream,'messages':[{'role':'user','content':fixture.CLARIFY_PROMPT}],
                             'tools':[{'function':{'name':'clarify'}}]}
                    def post():
                        req=urllib.request.Request(f"http://127.0.0.1:{state['port']}/v1/chat/completions",data=json.dumps(request).encode())
                        with urllib.request.urlopen(req,timeout=2) as response: data=response.read().decode()
                        if not stream:return json.loads(data)['choices'][0]['message']
                        chunk=json.loads(next(line[6:] for line in data.splitlines() if line.startswith('data: {')))
                        value=chunk['choices'][0]['delta']
                        for call in value.get('tool_calls',[]):call.pop('index')
                        return value
                    call=post();self.assertEqual(call['tool_calls'][0]['function']['name'],'clarify')
                    request['messages'] += [call,{'role':'tool','tool_call_id':fixture.CLARIFY_CALL,
                        'content':json.dumps({'responses':[{'question':fixture.CLARIFY_QUESTION,'user_response':fixture.CLARIFY_ANSWER}]})}]
                    self.assertEqual(post()['content'],fixture.CLARIFY_REPLY)
                    self.assertEqual(state['requests'],2);self.assertEqual(state['clarifyAnswers'],1)
                self.assertTrue(state['restored'])

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
