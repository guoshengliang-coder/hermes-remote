import importlib.util
import json
import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('agent_fixture',Path(__file__).parents[1]/'hermes-compatibility-launchagent-fixture.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)


class LaunchAgentTests(unittest.TestCase):
    def prepare(self,root,port=23456):
        (root/'tmp').mkdir(exist_ok=True)
        sandbox=root/'tmp/test.sb';sandbox.write_text('synthetic')
        log=root/'tmp/test.log';log.write_text('')
        def native(_helper,action,workspace,_version):
            self.assertEqual(action,'prepare')
            launcher=workspace/'hermes-local-serve';launcher.write_text("[ \"$6\" = '9119' ]\n")
            (workspace/(fixture.PRODUCTION_LABEL+'.plist')).write_bytes(plistlib.dumps({'Label':fixture.PRODUCTION_LABEL,
                'ProgramArguments':[str(launcher),str(root/'user/.hermes/hermes-agent/venv/bin/hermes'),'serve','--host','127.0.0.1','--port','9119'],
                'EnvironmentVariables':{'HERMES_SESSION_TOKEN_FILE':str(workspace/'session.token'),'HERMES_DESKTOP':'1'},
                'RunAtLoad':True,'ProcessType':'Background','ThrottleInterval':30}))
        with mock.patch.object(fixture,'invoke',side_effect=native):
            return fixture.prepare_agent(root,Path('/synthetic/helper'),'0.21.3',sandbox,
                {'HOME':str(root/'user'),'HTTPS_PROXY':'synthetic-private','HERMES_DASHBOARD_SESSION_TOKEN':'never-in-plist'},
                'a'*43,log,port)

    def test_adapter_changes_only_isolated_label_port_environment_and_keeps_token_in_private_file(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();agent=self.prepare(root)
            data=(agent.workspace/(fixture.PRODUCTION_LABEL+'.plist')).read_bytes();config=plistlib.loads(data)
            self.assertNotEqual(config['Label'],fixture.PRODUCTION_LABEL)
            self.assertTrue(config['Label'].startswith('com.hermesgo.compat-lab.'))
            self.assertEqual(config['ProgramArguments'][:2],['/usr/bin/env','-i'])
            self.assertEqual(config['ProgramArguments'][-1],'23456')
            self.assertNotIn('EnvironmentVariables',config)
            for secret in [b'a'*43,b'synthetic-private',b'never-in-plist']:self.assertNotIn(secret,data)
            self.assertEqual((agent.workspace/'session.token').stat().st_mode&0o777,0o600)
            self.assertEqual(config['ThrottleInterval'],30)

    def test_production_port_and_daily_paths_are_refused(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve()
            with self.assertRaisesRegex(ValueError,'unsafe_test_port'):self.prepare(root,9119)
        with self.assertRaisesRegex(ValueError,'not_disposable_lab'):
            fixture.prepare_agent(Path.home(),Path('/synthetic/helper'),'0.21.3',Path('/tmp/x'),{},'a'*43,Path('/tmp/y'))

    def test_collision_refuses_before_bootstrap_and_cleanup_uses_only_owned_label(self):
        with tempfile.TemporaryDirectory(prefix='hermes-compat-lab-') as directory:
            root=Path(directory).resolve();agent=self.prepare(root)
            with mock.patch.object(agent,'inspect',return_value=321),mock.patch.object(fixture,'invoke') as command:
                with self.assertRaisesRegex(ValueError,'launchagent_collision'):agent.start()
                command.assert_not_called();self.assertFalse(agent.started)
            with mock.patch.object(agent,'inspect',side_effect=[None,321]),mock.patch.object(fixture,'invoke') as command:
                agent.start();self.assertEqual(agent.pid,321)
                command.assert_called_once_with(agent.helper,'start',agent.workspace,agent.version)
            with mock.patch.object(agent,'inspect',side_effect=[321,None]),mock.patch.object(fixture,'invoke') as command, \
                mock.patch.object(fixture.os,'kill',side_effect=ProcessLookupError):
                agent.stop();command.assert_called_once_with(agent.helper,'stop',agent.workspace,agent.version)
                self.assertTrue(all(agent.checks.values()))


if __name__=='__main__':unittest.main()
