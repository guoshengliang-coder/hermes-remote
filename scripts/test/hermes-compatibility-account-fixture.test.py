import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest

spec=importlib.util.spec_from_file_location('postgres_fixture',Path(__file__).parents[1]/'hermes-compatibility-postgres-fixture.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

class AccountFixtureTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('HERMES_COMPATIBILITY_POSTGRES_BIN') and Path('/usr/bin/sandbox-exec').exists(),
                         'explicit disposable PostgreSQL toolchain and macOS sandbox required')
    def test_reconnect_waits_for_actual_route_when_previous_health_is_still_positive(self):
        scripts=Path(__file__).parents[1]
        def load(name):
            spec=importlib.util.spec_from_file_location(name,scripts/(name+'.py'))
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
        relay=load('hermes-compatibility-relay-fixture');lab=load('hermes-compatibility-lab')
        state={'token':secrets.token_urlsafe(32),'delay':0}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                if self.path=='/api/health':time.sleep(state['delay'])
                if self.headers.get('X-Hermes-Session-Token')!=state['token']:status,value=401,{}
                elif self.path=='/api/health':status,value=200,{'ok':True,'version':'0.22.5'}
                elif self.path=='/api/profiles/active':status,value=200,{'active':'default'}
                elif self.path=='/api/status':status,value=200,{'version':'0.22.5','gateway_running':False}
                elif self.path.startswith('/api/sessions'):status,value=200,{'sessions':[]}
                else:status,value=404,{}
                body=json.dumps(value).encode();self.send_response(status)
                self.send_header('Content-Length',str(len(body)));self.end_headers()
                try:self.wfile.write(body)
                except (BrokenPipeError,ConnectionResetError):pass
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();(root/'tmp').mkdir();(root/'user').mkdir()
            sandbox=root/'tmp/reconnect.sb'
            sandbox.write_text(lab.sandbox_profile(root,Path.home())+'(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n')
            server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            try:
                with relay.relay_fixture(root,shutil.which('node'),sandbox,server.server_port,state['token'],'readiness',
                    postgres_bin=os.environ['HERMES_COMPATIBILITY_POSTGRES_BIN']) as observation:
                    state.update(token=secrets.token_urlsafe(32),delay=1.0)
                    observation['reconnect'](state['token'])
                    diagnostic=observation['diagnose'](state['token'])
                    self.assertEqual(diagnostic['relayStatus'],200)
                    self.assertTrue(diagnostic['connectorOnline']);self.assertTrue(diagnostic['hermesReachable'])
                self.assertTrue(observation['stopped']);self.assertTrue(observation['account']['checks']['databaseStopped'])
            finally:
                server.shutdown();server.server_close();thread.join(timeout=3)

    def test_daily_directory_is_rejected_without_starting_database(self):
        with self.assertRaises(ValueError):
            with fixture.postgres_fixture(Path.home(),'/missing'):self.fail('unsafe cluster started')

    def test_writable_or_missing_binary_is_refused_before_cluster_creation(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory);workspace=root/'tmp/account-test';workspace.mkdir(parents=True,mode=0o700)
            binaries=root/'bin';binaries.mkdir()
            for name in ['initdb','postgres','pg_isready']:
                (binaries/name).write_text('not executable');(binaries/name).chmod(0o777)
            with self.assertRaisesRegex(ValueError,'unsafe_postgresql_binary'):
                with fixture.postgres_fixture(workspace,binaries):self.fail('unsafe cluster started')
            self.assertFalse((workspace/'postgres-data').exists())

    @unittest.skipUnless(os.environ.get('HERMES_COMPATIBILITY_POSTGRES_BIN'),'explicit disposable PostgreSQL toolchain required')
    def test_real_fresh_cluster_identity_seed_repeat_refusal_and_cleanup_on_failure(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();workspace=root/'tmp/account-test';workspace.mkdir(parents=True,mode=0o700)
            with self.assertRaisesRegex(RuntimeError,'injected'):
                with fixture.postgres_fixture(workspace,os.environ['HERMES_COMPATIBILITY_POSTGRES_BIN']) as database:
                    source=Path(__file__).parents[1]/'hermes-compatibility-account-fixture.mjs'
                    def seed(data):return subprocess.run([shutil.which('node'),str(source)],input=json.dumps(data),
                        text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
                    values={'url':database['url'],'dataDirectory':database['dataDirectory'],'workspace':str(workspace)}
                    rejected=seed({**values,'dataDirectory':str(root/'elsewhere')})
                    self.assertNotEqual(rejected.returncode,0)
                    first=seed(values);self.assertEqual(first.returncode,0,first.stderr)
                    account=json.loads(first.stdout)
                    self.assertTrue(account['databaseIdentityVerified']);self.assertTrue(account['emptyDatabaseVerified'])
                    self.assertEqual((workspace/'account-credential.json').stat().st_mode&0o777,0o600)
                    self.assertNotEqual(account['token'],account['otherToken'])
                    again=seed(values);self.assertNotEqual(again.returncode,0)
                    for response in [rejected,again]:
                        self.assertIn('HR-HERMESCOMPAT-004',response.stderr)
                        self.assertIn('账号验证环境未就绪',response.stderr)
                        self.assertIn('Account verification environment is not ready',response.stderr)
                        self.assertNotIn(database['url'],response.stderr)
                        self.assertEqual(response.stdout,'')
                    self.assertFalse(database['stopped'])
                    raise RuntimeError('injected')
            self.assertTrue(database['stopped'])
            self.assertFalse((workspace/'postgres-data/postmaster.pid').exists())

if __name__=='__main__':unittest.main()
