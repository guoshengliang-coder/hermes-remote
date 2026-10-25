"""Loopback-only deterministic model fixture; never uses real provider credentials."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import stat
import threading

PROMPT = 'Hermes GO compatibility synthetic turn'
ANSWER = 'Hermes GO synthetic model reply'
CLARIFY_PROMPT = 'Hermes GO compatibility synthetic clarification'
CLARIFY_QUESTION = 'Choose the synthetic compatibility answer'
CLARIFY_ANSWER = 'Synthetic choice A'
CLARIFY_REPLY = 'Hermes GO synthetic clarification completed'
CLARIFY_CALL = 'call_go_compat_clarify'


def clarify_message(request):
    """Only complete after Hermes returns the exact answer to our one-question batch."""
    messages = request['messages']
    tools = [m for m in messages if m.get('role') == 'tool']
    if not tools:
        if not any(t.get('function', {}).get('name') == 'clarify' for t in request.get('tools', []) if isinstance(t, dict)):
            raise ValueError('clarify_not_available')
        return {'role': 'assistant', 'content': None, 'tool_calls': [{'id': CLARIFY_CALL, 'type': 'function',
            'function': {'name': 'clarify', 'arguments': json.dumps({'questions': [
                {'question': CLARIFY_QUESTION, 'choices': [CLARIFY_ANSWER, 'Synthetic choice B']}]})}}]}, 'tool_calls'
    if len(tools) != 1 or tools[0].get('tool_call_id') != CLARIFY_CALL:
        raise ValueError('unexpected_tool_result')
    value = json.loads(tools[0]['content'])
    responses = value.get('responses')
    if value.get('timed_out') or not isinstance(responses, list) or len(responses) != 1 or \
            responses[0].get('question') != CLARIFY_QUESTION or responses[0].get('user_response') != CLARIFY_ANSWER:
        raise ValueError('clarify_answer_not_observed')
    calls = [c for m in messages if m.get('role') == 'assistant' for c in m.get('tool_calls', [])]
    if len(calls) != 1 or calls[0].get('id') != CLARIFY_CALL or calls[0].get('function', {}).get('name') != 'clarify':
        raise ValueError('clarify_call_not_observed')
    return {'role': 'assistant', 'content': CLARIFY_REPLY}, 'stop'


@contextmanager
def model_fixture(config_path):
    config_path = Path(config_path)
    info = config_path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
        raise ValueError('unsafe_synthetic_config')
    original = config_path.read_bytes()
    observation = {'requests': 0, 'clarifyAnswers': 0, 'restored': False}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def reply(self, status, body, content_type='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_GET(self):
            if self.path == '/v1/models':
                self.reply(200, json.dumps({'object':'list','data':[{'id':'go-compat-model','object':'model','owned_by':'fixture'}]}).encode())
            else: self.reply(404, b'{}')
        def do_POST(self):
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if self.path != '/v1/chat/completions' or not 0 < size <= 4*1024*1024:
                    self.reply(400,b'{}'); return
                request=json.loads(self.rfile.read(size))
                if not isinstance(request,dict) or not isinstance(request.get('messages'),list) or not all(isinstance(m,dict) for m in request['messages']):
                    self.reply(400,b'{}'); return
                users=[m for m in request.get('messages',[]) if m.get('role')=='user']
                if not users or users[-1].get('content') not in (PROMPT, CLARIFY_PROMPT):
                    self.reply(400,b'{}'); return
                message, finish = clarify_message(request) if users[-1]['content'] == CLARIFY_PROMPT else ({'role':'assistant','content':ANSWER}, 'stop')
                observation['requests'] += 1
                if message.get('content') == CLARIFY_REPLY: observation['clarifyAnswers'] += 1
                base={'id':'chatcmpl-hermes-compat','created':1792926000,'model':'go-compat-model'}
                if request.get('stream'):
                    delta = dict(message)
                    if 'tool_calls' in delta:
                        delta['tool_calls'] = [{**call, 'index': i} for i, call in enumerate(delta['tool_calls'])]
                    chunks=[{**base,'object':'chat.completion.chunk','choices':[{'index':0,'delta':part,'finish_reason':reason}]}
                            for part,reason in [(delta,None),({},finish)]]
                    body=''.join('data: '+json.dumps(chunk)+'\n\n' for chunk in chunks)+'data: [DONE]\n\n'
                    self.reply(200,body.encode(),'text/event-stream')
                else:
                    self.reply(200,json.dumps({**base,'object':'chat.completion','choices':[{'index':0,
                        'message':message,'finish_reason':finish}],
                        'usage':{'prompt_tokens':10,'completion_tokens':8,'total_tokens':18}}).encode())
            except (ValueError, TypeError, KeyError): self.reply(400,b'{}')
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    server.daemon_threads=True
    thread=threading.Thread(target=server.serve_forever,daemon=True)
    observation['port']=server.server_port
    config={'model':{'default':'go-compat-model','provider':'custom','base_url':f'http://127.0.0.1:{server.server_port}/v1',
                     'api_key':'synthetic-local-only','api_mode':'chat_completions'},
            'agent':{'max_turns':2},'display':{'show_reasoning':False}}
    try:
        config_path.write_text(json.dumps(config));config_path.chmod(0o600)
        thread.start()
        yield observation
    finally:
        if thread.is_alive(): server.shutdown();thread.join(timeout=5)
        server.server_close()
        config_path.write_bytes(original);config_path.chmod(stat.S_IMODE(info.st_mode))
        observation['restored']=config_path.read_bytes()==original and stat.S_IMODE(config_path.stat().st_mode)==stat.S_IMODE(info.st_mode)
