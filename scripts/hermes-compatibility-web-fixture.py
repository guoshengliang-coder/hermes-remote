"""Private local HTTPS/browser adapter for the existing disposable account relay."""
import base64
import secrets
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import socket
import stat
import subprocess


def prepare(workspace, sandbox, options):
    workspace=Path(workspace)
    source=Path(__file__).resolve().parent.parent
    module=Path(options['playwright']).resolve();browser=Path(options['chromium']).resolve()
    for path in (module,browser):
        if not path.is_file() or path.stat().st_uid not in (0,os.getuid()) or path.stat().st_mode&0o002:
            raise ValueError('unsafe_browser_toolchain')
    if module.stat().st_mode&0o020:raise ValueError('unsafe_browser_module')
    # Only the browser revision downloaded by the pinned Playwright package is accepted.
    # Chrome for Testing archives are test tooling, not notarized application releases.
    bundle=next((parent for parent in browser.parents if parent.suffix=='.app'),None)
    if bundle is None:raise ValueError('browser_bundle_required')
    if bundle.name!='Google Chrome for Testing.app':raise ValueError('pinned_test_browser_required')
    package=json.loads((module.parent/'package.json').read_text())
    expected=json.loads((source/'package.json').read_text())['devDependencies']['playwright']
    if package.get('name')!='playwright' or package.get('version')!=expected:raise ValueError('playwright_version_mismatch')
    registry=json.loads((module.parent.parent/'playwright-core/browsers.json').read_text())
    pinned=next(item for item in registry['browsers'] if item['name']=='chromium')
    if bundle.parent.name!='chrome-mac-arm64' or bundle.parent.parent.name!='chromium-'+pinned['revision'] or \
            not (bundle.parent.parent/'INSTALLATION_COMPLETE').is_file():raise ValueError('browser_revision_mismatch')
    if not os.access(browser,os.X_OK):raise ValueError('browser_not_executable')
    dist=source/'web/dist'
    if not (dist/'index.html').is_file():raise ValueError('web_build_missing')
    files=[]
    for path in sorted(dist.rglob('*')):
        if path.is_symlink():raise ValueError('unsafe_web_build')
        if path.is_file():files.append([str(path.relative_to(dist)),hashlib.sha256(path.read_bytes()).hexdigest()])
    config=workspace/'tls.conf'
    config.write_text('[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=127.0.0.1\n[extensions]\nsubjectAltName=IP:127.0.0.1\n')
    config.chmod(0o600)
    key=workspace/'tls-key.pem';cert=workspace/'tls-cert.pem'
    subprocess.run(['/usr/bin/openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1',
        '-keyout',str(key),'-out',str(cert),'-config',str(config)],check=True,
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=20)
    key.chmod(0o600);cert.chmod(0o600)
    browser_fence=workspace/'browser.sb'
    browser_fence.write_text(Path(sandbox).read_text()+'\n(allow file-read* (subpath '+json.dumps(str(bundle))+'))\n')
    browser_fence.chmod(0o600)
    wrapper=workspace/'browser-sandbox'
    wrapper.write_text('#!/bin/sh\nexec /usr/bin/sandbox-exec -f '+shlex.quote(str(browser_fence))+' '+shlex.quote(str(browser))+' "$@"\n')
    wrapper.chmod(0o700)
    with socket.socket() as reserve:
        reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
    return {'port':port,'origin':f'https://127.0.0.1:{port}','dist':str(dist),'wrapper':str(wrapper),
        'module':str(module),'expectedBrowserVersion':pinned['browserVersion'],'playwrightVersion':expected,'key':str(key),'cert':str(cert),'workspace':str(workspace),
        'webBuildSha256':hashlib.sha256(json.dumps(files,separators=(',',':')).encode()).hexdigest(),
        'browserBinarySha256':hashlib.sha256(browser.read_bytes()).hexdigest()}


def gateway_environment(options):
    return {'ACCOUNT_MULTI_DEVICE_ENABLED':'1','ACCOUNT_IDENTITY_MANAGEMENT_ENABLED':'1',
        'ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED':'1','ACCOUNT_EMAIL_OTP_ENABLED':'1',
        'ACCOUNT_EMAIL_OTP_HASH_KEY':secrets.token_urlsafe(32),'ACCOUNT_EMAIL_OTP_ISSUER':options['origin'],
        'ACCOUNT_RESEND_WEBHOOK_ENABLED':'1',
        'ACCOUNT_RESEND_WEBHOOK_SECRET':'whsec_'+base64.b64encode(secrets.token_bytes(32)).decode(),
        'ACCOUNT_RESEND_API_KEY':'re_synthetic_no_mail','ACCOUNT_EMAIL_FROM':'Synthetic <test@example.invalid>',
        'ACCOUNT_WEB_SESSION_ENABLED':'1','ACCOUNT_WEB_ORIGIN':options['origin'],
        'ACCOUNT_WEB_DEVICE_ACCESS_ENABLED':'1','WEB_APP_ENABLED':'1','WEB_APP_DIR':options['dist']}


def probe(node, options, gateway_port, session, root):
    script=Path(__file__).with_name('hermes-compatibility-web-probe.mjs')
    log=Path(options['workspace'])/'browser.private.log'
    with log.open('xb') as output:
        log.chmod(0o600)
        child=subprocess.Popen([node,str(script)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=output,
            text=True,start_new_session=True,env={'PATH':'/usr/bin:/bin','HOME':str(root/'user'),'TMPDIR':str(root/'tmp'),
                # Chromium on macOS deliberately ignores TMPDIR for singleton sockets.
                'MAC_CHROMIUM_TMPDIR':str(root/'tmp')})
        try:
            result,_=child.communicate(json.dumps({**options,'gatewayPort':gateway_port,'session':session}),timeout=180)
            if child.returncode:raise ValueError('web_probe_failed')
            value=json.loads(result)
            if value.get('checks',{}).get('browserClosed') is not True:raise ValueError('browser_cleanup_unconfirmed')
            return {**value,'webBuildSha256':options['webBuildSha256'],'browserBinarySha256':options['browserBinarySha256'],
                'probeSha256':hashlib.sha256(script.read_bytes()).hexdigest(),
                'fixtureSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
        except Exception:
            # Let Playwright's SIGTERM handler close its owned browser before a bounded kill.
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=15)
                except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait()
            marker=root/'tmp/launchagent-cleanup-required.json'
            if not marker.exists():
                fd=os.open(str(marker),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
                with os.fdopen(fd,'w') as stream:json.dump({'schemaVersion':1,'reason':'browser_cleanup_unconfirmed','workspace':options['workspace']},stream)
            raise
