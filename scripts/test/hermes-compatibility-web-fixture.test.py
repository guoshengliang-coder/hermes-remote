import base64
import importlib.util
from pathlib import Path
import os
import tempfile
import unittest
from unittest import mock
import subprocess

spec=importlib.util.spec_from_file_location('web_fixture',Path(__file__).parents[1]/'hermes-compatibility-web-fixture.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

class WebFixtureTests(unittest.TestCase):
    def test_synthetic_webhook_uses_standard_base64_for_actual_svix_decoder(self):
        values=fixture.gateway_environment({'origin':'https://127.0.0.1:1234','dist':'/synthetic/dist'})
        self.assertEqual(len(base64.b64decode(values['ACCOUNT_RESEND_WEBHOOK_SECRET'][6:],validate=True)),32)
        self.assertEqual(values['ACCOUNT_WEB_ORIGIN'],'https://127.0.0.1:1234')
        self.assertEqual(values['ACCOUNT_WEB_DEVICE_ACCESS_ENABLED'],'1')

    def test_untrusted_permissions_and_missing_bundle_refuse_before_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);module=root/'module.mjs';module.write_text('synthetic');module.chmod(0o644)
            browser=root/'browser';browser.write_text('synthetic');browser.chmod(0o777)
            with mock.patch.object(fixture.subprocess,'run') as command:
                with self.assertRaisesRegex(ValueError,'unsafe_browser_toolchain'):
                    fixture.prepare(root,root/'fence',{'playwright':str(module),'chromium':str(browser)})
                command.assert_not_called()
                browser.chmod(0o755)
                with self.assertRaisesRegex(ValueError,'browser_bundle_required'):
                    fixture.prepare(root,root/'fence',{'playwright':str(module),'chromium':str(browser)})
                command.assert_not_called()

    def test_daily_browser_refusal_keeps_installed_mode_and_precedes_certificate_creation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);module=root/'module.mjs';module.write_text('synthetic');module.chmod(0o644)
            browser=root/'Browser.app/Contents/MacOS/Browser';browser.parent.mkdir(parents=True)
            browser.write_text('synthetic');browser.chmod(0o775)
            with mock.patch.object(fixture.subprocess,'run') as command:
                with self.assertRaisesRegex(ValueError,'pinned_test_browser_required'):
                    fixture.prepare(root,root/'fence',{'playwright':str(module),'chromium':str(browser)})
            command.assert_not_called()
            self.assertEqual(browser.stat().st_mode&0o777,0o775)
            self.assertFalse((root/'tls-key.pem').exists())

    def test_uncertain_browser_cleanup_quarantines_lab_without_exposing_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);workspace=root/'tmp/web';workspace.mkdir(parents=True)
            child=mock.Mock();child.communicate.return_value=('{}',None);child.returncode=0;child.poll.return_value=0
            with mock.patch.object(fixture.subprocess,'Popen',return_value=child) as launch:
                with self.assertRaisesRegex(ValueError,'browser_cleanup_unconfirmed'):
                    fixture.probe('/synthetic/node',{'workspace':str(workspace)},1234,{'token':'synthetic-private'},root)
            self.assertEqual(launch.call_args.kwargs['env']['MAC_CHROMIUM_TMPDIR'],str(root/'tmp'))
            self.assertEqual(launch.call_args.kwargs['env']['HOME'],str(root/'user'))
            marker=root/'tmp/launchagent-cleanup-required.json'
            self.assertTrue(marker.exists());self.assertEqual(marker.stat().st_mode&0o777,0o600)
            self.assertNotIn('synthetic-private',marker.read_text())

if __name__=='__main__':unittest.main()
