import hashlib
import importlib.util
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('fixture',Path(__file__).parents[1]/'hermes-compatibility-relay-fixture.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

class RelayFixtureTests(unittest.TestCase):
    def prepare(self, directory):
        root=Path(directory);(root/'tmp').mkdir()
        sandbox=root/'tmp/isolation.sb';sandbox.write_text('(version 1)\n(allow default)\n')
        node=root/'selected-node';node.write_bytes(b'synthetic executable bytes')
        return root,sandbox,node

    def test_build_digest_binds_all_components_and_lockfile(self):
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)
            for component in ['gateway','connector','protocol']:
                (source/component/'dist').mkdir(parents=True)
                (source/component/'dist/index.js').write_text(component)
            (source/'package-lock.json').write_text('{}')
            before=fixture.build_digest(source)
            (source/'connector/dist/index.js').write_text('changed')
            self.assertNotEqual(before,fixture.build_digest(source))
            (source/'gateway/dist/link').symlink_to(source/'package-lock.json')
            with self.assertRaises(ValueError):fixture.build_digest(source)

    def test_clean_environments_separate_roles_and_cleanup_even_on_probe_failure(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root,sandbox,node=self.prepare(directory);children=[];envs=[];commands=[]
            def launch(command,**options):
                child=types.SimpleNamespace(pid=12000+len(children),code=None)
                child.poll=lambda:child.code
                def wait(timeout):child.code=0
                child.wait=wait;children.append(child);envs.append(options['env']);commands.append(command)
                self.assertTrue(options['start_new_session'])
                return child
            with mock.patch.object(fixture,'build_digest',return_value='a'*64),mock.patch.object(fixture,'wait_ready'), \
                    mock.patch.object(fixture,'get_json',return_value=(401,None)),mock.patch.object(fixture.subprocess,'Popen',side_effect=launch), \
                    mock.patch.object(fixture.os,'killpg') as kill:
                with self.assertRaises(RuntimeError):
                    with fixture.relay_fixture(root,str(node),sandbox,12345,'synthetic-local-token','test') as report:
                        self.assertTrue(report['roleSeparation'])
                        self.assertEqual(report['nodeSha256'],hashlib.sha256(node.read_bytes()).hexdigest())
                        self.assertTrue(all(c[:3]==['/usr/bin/sandbox-exec','-f',str(sandbox)] for c in commands))
                        self.assertNotEqual(commands[0][3],str(node))
                        self.assertNotIn('HERMES_SESSION_TOKEN',envs[0]);self.assertNotIn('APP_TOKEN',envs[1])
                        self.assertEqual(envs[1]['HERMES_MODE'],'live');self.assertEqual(envs[1]['SESSION_OBSERVER_ENABLED'],'1')
                        self.assertEqual(envs[0]['CONNECTOR_TOKEN'],envs[1]['CONNECTOR_TOKEN'])
                        self.assertEqual(envs[1]['HERMES_SESSION_TOKEN'],'synthetic-local-token')
                        for env in envs:self.assertNotIn('NODE_OPTIONS',env)
                        with mock.patch.object(fixture,'get_json',side_effect=[
                            (502,{'error':{'code':'HR-CONN-006','message':'synthetic-private'}}),(200,{'secret':'synthetic-private'})]):
                            diagnostic=report['diagnose']('n'*43)
                            self.assertEqual(diagnostic,{'relayStatus':502,'registeredErrorCode':'HR-CONN-006','localStatus':200})
                            self.assertNotIn('synthetic-private',str(diagnostic))
                        raise RuntimeError('injected probe failure')
                self.assertTrue(report['stopped'])
                self.assertEqual({call.args[0] for call in kill.call_args_list},{12000,12001})
                self.assertFalse((root/'tmp/relay-test/node').exists())

    def test_failed_child_start_still_stops_previously_started_child(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root,sandbox,node=self.prepare(directory)
            child=types.SimpleNamespace(pid=13000,code=None)
            child.poll=lambda:child.code
            def wait(timeout):child.code=0
            child.wait=wait
            with mock.patch.object(fixture,'build_digest',return_value='a'*64),mock.patch.object(fixture,'wait_ready'), \
                    mock.patch.object(fixture.subprocess,'Popen',side_effect=[child,OSError('injected')]),mock.patch.object(fixture.os,'killpg') as kill:
                with self.assertRaises(OSError):
                    with fixture.relay_fixture(root,str(node),sandbox,12345,'synthetic','failed'):self.fail('unexpected ready')
                self.assertEqual(child.code,0);self.assertTrue(all(c.args[0]==13000 for c in kill.call_args_list))
                self.assertFalse((root/'tmp/relay-failed/node').exists())

    def test_recovery_keeps_gateway_and_public_credentials_but_replaces_only_connector(self):
        for fail in (False,True):
            with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
                root,sandbox,node=self.prepare(directory);children=[];envs=[]
                def launch(command,**options):
                    if fail and len(children)==2:raise OSError('injected restart failure')
                    child=types.SimpleNamespace(pid=14000+len(children),code=None)
                    child.poll=lambda:child.code
                    def wait(timeout):child.code=0
                    child.wait=wait;children.append(child);envs.append(options['env'])
                    return child
                with mock.patch.object(fixture,'build_digest',return_value='a'*64),mock.patch.object(fixture,'wait_ready') as ready, \
                    mock.patch.object(fixture,'get_json',return_value=(403,None)),mock.patch.object(fixture.subprocess,'Popen',side_effect=launch), \
                    mock.patch.object(fixture.os,'killpg'):
                    with fixture.relay_fixture(root,str(node),sandbox,12345,'a'*43,'recovery') as report:
                        original=(report['port'],report['token'])
                        if fail:
                            with self.assertRaises(OSError):report['reconnect']('b'*43)
                        else:
                            self.assertTrue(all(report['reconnect']('b'*43).values()))
                            self.assertEqual(envs[1]|{'HERMES_SESSION_TOKEN':'b'*43},envs[2])
                            self.assertEqual(original,(report['port'],report['token']))
                            self.assertIsNone(children[0].poll())
                            self.assertEqual(children[1].poll(),0)
                            self.assertTrue(any(c.kwargs.get('online') is False for c in ready.call_args_list))
                            with self.assertRaises(ValueError):report['reconnect']('c'*43)
                    self.assertTrue(report['stopped'])
                    self.assertTrue(all(c.poll() is not None for c in children))
                    self.assertFalse((root/'tmp/relay-recovery/node').exists())

    def test_account_online_is_not_ready_until_hermes_health_is_accepted(self):
        child=types.SimpleNamespace(poll=lambda:None)
        def state(reachable):return (200,{'state':'bound','binding':{'connector':{'online':True},'hermes':{'reachable':reachable}}})
        with mock.patch.object(fixture,'get_json',side_effect=[state(False),state(True),(200,{})]) as read, \
            mock.patch.object(fixture.time,'sleep'):
            fixture.wait_ready([child],'http://127.0.0.1:12345','synthetic',account_token='synthetic')
            self.assertEqual(read.call_count,3)
        with mock.patch.object(fixture,'get_json',return_value=state(False)), \
            mock.patch.object(fixture.time,'monotonic',side_effect=[0,0,46]),mock.patch.object(fixture.time,'sleep'):
            with self.assertRaisesRegex(ValueError,'relay_start_timeout'):
                fixture.wait_ready([child],'http://127.0.0.1:12345','synthetic',account_token='synthetic')

    def test_stale_healthy_binding_cannot_signal_ready_before_account_route_is_registered(self):
        child=types.SimpleNamespace(poll=lambda:None)
        state=(200,{'state':'bound','binding':{'connector':{'online':True},'hermes':{'reachable':True}}})
        origin='http://127.0.0.1:12345'
        with mock.patch.object(fixture,'get_json',side_effect=[state,(503,{}),state,(200,{})]) as read, \
            mock.patch.object(fixture.time,'sleep'):
            fixture.wait_ready([child],origin,'synthetic',account_token='synthetic')
            self.assertEqual(read.call_args_list,[
                mock.call(origin+'/v2/connector-binding','synthetic',account=True),
                mock.call(origin+'/api/status','synthetic',account=True),
                mock.call(origin+'/v2/connector-binding','synthetic',account=True),
                mock.call(origin+'/api/status','synthetic',account=True)])
        with mock.patch.object(fixture,'get_json',side_effect=[state,(503,{})]), \
            mock.patch.object(fixture.time,'monotonic',side_effect=[0,0,46]),mock.patch.object(fixture.time,'sleep'):
            with self.assertRaisesRegex(ValueError,'relay_start_timeout'):
                fixture.wait_ready([child],origin,'synthetic',account_token='synthetic')

    def test_exited_child_fails_readiness_before_network_access(self):
        child=types.SimpleNamespace(poll=lambda:1)
        with mock.patch.object(fixture,'get_json') as read:
            with self.assertRaisesRegex(ValueError,'relay_process_exited'):fixture.wait_ready([child],'http://127.0.0.1:1')
            read.assert_not_called()

if __name__=='__main__':unittest.main()
