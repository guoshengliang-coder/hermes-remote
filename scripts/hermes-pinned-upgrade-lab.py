#!/usr/bin/env python3
"""Run actual Core pinned-install stages on a disposable copy, then prove native restoration.

The untouched original lab user is retained and returned after all owned processes exit. This is
not a production maintenance-window certificate, service drain or installed Desktop UI journey.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import time
import uuid

SOURCE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_recovery', SOURCE / 'scripts/hermes-recovery-point-lab.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
base = native.base


def idle(home):
    result = subprocess.run(['/usr/sbin/lsof', '-t', '+D', str(home)], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=60)
    return result.returncode in (0, 1) and not result.stdout.strip()


def probe_history(root, workspace, env, launcher, name, expected_version):
    # base.probe_history deliberately creates its log exclusively. Each invocation gets a
    # fresh name; previous ready markers and retained evidence are never reused or deleted.
    unique = name + '-' + str(uuid.uuid4())
    profile = workspace / (unique + '.sb')
    log = root / (unique + '.private.log')
    profile.write_text(base.sandbox_profile(root, Path.home())
        + '(allow file-write* (literal "' + str(log) + '"))\n'
        + '(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n')
    return base.probe_history(root, profile, env, launcher, unique, expected_version)


def checked_material_roots(root, run_id, material, expected_roots):
    user = root / 'user'
    if user.resolve(strict=True) != user:
        raise base.LabRefusal('material_path_mismatch')
    points = (root / ('native-recovery-' + run_id) / 'points').resolve(strict=True)
    if material.get('runID', '').lower() != run_id.lower() or len(material.get('roots', [])) != len(expected_roots):
        raise base.LabRefusal('material_path_mismatch')
    result = []
    for item, expected in zip(material['roots'], expected_roots):
        # Foundation may return /var while pathlib returns /private/var on macOS.
        # Compare real locations, then require the exact captured roots and private points tree.
        original, staged = Path(item['original']).resolve(strict=True), Path(item['staged']).resolve(strict=True)
        if (original != expected or not original.is_relative_to(user)
                or not staged.is_relative_to(points) or staged == points):
            raise base.LabRefusal('material_path_mismatch')
        result.append((original, staged))
    if not idle(root / 'user'):
        raise base.LabRefusal('lab_process_in_use')
    return result


def run(root, mode):
    root = root.resolve()
    info = root.lstat()
    if (root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('hermes-compat-lab-')
            or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077):
        raise base.LabRefusal('disposable_lab_required')
    if any(root.glob('*cleanup-required*')):
        raise base.LabRefusal('previous_cleanup_required')
    user = root / 'user'
    home = user / '.hermes'
    checkout = home / 'hermes-agent'
    if user.resolve() != user or base.git_read(checkout, 'rev-parse', 'HEAD').decode().strip() != base.RUNTIME_SOURCE:
        raise base.LabRefusal('pinned_source_required')
    if not idle(user):
        raise base.LabRefusal('lab_process_in_use')
    if native.digest(root / 'verified-install.sh') != '99cb3691d55019c6973a7acb775a915648666cc24e483ca827a1cdbb3531a674':
        raise base.LabRefusal('installer_digest_mismatch')
    runtime = (checkout / 'venv/bin/python').resolve().parent.parent
    before = base.tree_digest(user)
    run_id = str(uuid.uuid4())
    workspace = root / ('pinned-upgrade-' + run_id)
    workspace.mkdir(mode=0o700)
    retained = workspace / 'untouched-user'
    report = {'schemaVersion': 1, 'kind': 'desktop-native-pinned-upgrade', 'mode': mode,
              'status': 'running', 'compatibilityApproved': False, 'sourceCommit': base.RUNTIME_SOURCE,
              'targetCommit': 'bf0203a794a07aac3ff764972ab9087baa4e4f4e', 'coreSourceSha256': native.core_digest(),
              'helperSourceSha256': native.digest(SOURCE / 'scripts/hermes-pinned-upgrade-helper.swift'),
              'recoveryHelperSha256': native.digest(SOURCE / 'scripts/hermes-recovery-point-helper.swift'),
              'driverSha256': native.digest(Path(__file__)), 'checks': {},
              'notExecuted': ['productionServiceDrain', 'realRecommendationPublication', 'workerUILifecycle',
                              'automaticLivePathCommit', 'newBusinessDataRecovery', 'installedDesktopJourney']}
    report_path = root / ('pinned-upgrade-report-' + str(time.time_ns()) + '.json')

    def save():
        report_path.write_text(json.dumps(report, indent=2) + '\n')
        report_path.chmod(0o600)

    moved = False
    uncertain = False
    try:
        save()
        log = workspace / 'build.private.log'
        log.touch(mode=0o600)
        with log.open('wb') as output:
            subprocess.run(['swift', 'build', '--package-path', str(SOURCE / 'desktop'), '--target', 'HermesGoDesktopCore'],
                           check=True, stdout=output, stderr=output, timeout=300)
            binary = Path(subprocess.run(['swift', 'build', '--package-path', str(SOURCE / 'desktop'), '--show-bin-path'],
                                        check=True, text=True, stdout=subprocess.PIPE, stderr=output, timeout=30).stdout.strip())
            objects = sorted((binary / 'HermesGoDesktopCore.build').glob('*.o'))
            if not objects:
                raise base.LabRefusal('core_objects_missing')
            for name in ['hermes-recovery-point-helper', 'hermes-pinned-upgrade-helper']:
                subprocess.run(['swiftc', '-parse-as-library', '-I', str(binary / 'Modules'),
                                str(SOURCE / ('scripts/' + name + '.swift')), *map(str, objects), '-o', str(workspace / name)],
                               check=True, stdout=output, stderr=output, timeout=120)
        report['helperBinarySha256'] = native.digest(workspace / 'hermes-pinned-upgrade-helper')
        install_profile = workspace / 'native-install.sb'
        install_profile.write_text(base.sandbox_profile(root, Path.home()))
        with tempfile.NamedTemporaryFile(prefix='hermes-native-upgrade-fence-') as outside:
            outside.write(b'unchanged')
            outside.flush()
            result = subprocess.run(['/usr/bin/sandbox-exec', '-f', str(install_profile), '/bin/sh', '-c',
                'if echo changed > "$1"; then exit 1; fi', 'probe', outside.name], stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, timeout=15)
            if result.returncode or Path(outside.name).read_bytes() != b'unchanged':
                raise base.LabRefusal('sandbox_fence_failed')
        report['checks']['writeFence'] = True
        # Execute at the same synthetic absolute paths. The upstream installer never sees or
        # has write access to the untouched source, its native backup or this controller's evidence.
        user.rename(retained)
        moved = True
        shutil.copytree(retained, user, symlinks=True)
        if base.tree_digest(user) != before:
            raise base.LabRefusal('copy_mismatch')
        report['checks']['sourceCopy'] = True
        env = base.isolated_environment(root)
        env['TMPDIR'] = tempfile.gettempdir()  # native helper's root guard, not installer environment
        env['HOME'] = str(workspace)
        helper = workspace / 'hermes-recovery-point-helper'

        def recovery(action, expected):
            result = subprocess.run([str(helper), action, str(root), run_id], env=env,
                                    stdin=subprocess.DEVNULL, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, timeout=600)
            if result.returncode or json.loads(result.stdout) != {'status': expected}:
                raise base.LabRefusal('native_' + action + '_failed')
            report['checks'][action] = True
            save()

        recovery('capture', 'captured')
        result = subprocess.run([str(workspace / 'hermes-pinned-upgrade-helper'), str(root), run_id, mode], env=env,
                                stdin=subprocess.DEVNULL, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=6000)
        frames = [json.loads(line) for line in result.stdout.splitlines()]
        report['stages'] = [frame for frame in frames if set(frame) == {'stage', 'completed'}]
        report['checkpoints'] = [frame['checkpoint'] for frame in frames if set(frame) == {'checkpoint'}]
        report['helperResult'] = frames[-1] if frames else None
        expected = {'status': 'installed', 'intentRetained': True} if mode == 'normal' else {'status': 'refused', 'code': 'HR-HERMESUPDATE-003'}
        if not frames or frames[-1] != expected or result.returncode != (0 if mode == 'normal' else 2):
            raise base.LabRefusal('native_update_failed')
        completed = [f['stage'] for f in report['stages'] if f['completed']]
        if completed != (['repository', 'venv', 'python-deps', 'config', 'products', 'complete'] if mode == 'normal' else ['repository', 'venv']):
            raise base.LabRefusal('stage_receipt_mismatch')
        report['checks']['officialStages'] = True
        report['checks']['targetCommit'] = base.git_read(checkout, 'rev-parse', 'HEAD').decode().strip() == report['targetCommit']
        if not report['checks']['targetCommit']:
            raise base.LabRefusal('wrong_target')
        if mode == 'normal':
            report['checks']['candidateHistory'] = probe_history(root, workspace, base.isolated_environment(root),
                checkout / '.hermes/bin/hermes', 'candidateHistory', '0.22.5')['historyPresent']
        recovery('materialize', 'materialized')
        material = json.loads((root / ('native-recovery-' + run_id) / 'material.json').read_text())
        roots = checked_material_roots(root, run_id, material, [home, runtime])
        for index, (original, staged) in enumerate(roots):
            original.rename(workspace / ('candidate-root-' + str(index)))
            staged.rename(original)
        report['checks']['restoredHistory'] = probe_history(root, workspace, base.isolated_environment(root),
            checkout / 'venv/bin/hermes', 'restoredHistory', '0.21.3')['historyPresent']
        report['checks']['restoredConfiguration'] = all((home / name).read_bytes() == (retained / '.hermes' / name).read_bytes()
                                                     for name in ['.env', 'config.yaml'])
        report['checks']['sourceCommit'] = base.git_read(checkout, 'rev-parse', 'HEAD').decode().strip() == base.RUNTIME_SOURCE
        if not all(report['checks'].values()):
            raise base.LabRefusal('acceptance_failed')
        report['status'] = 'passed'
    except Exception as error:
        uncertain = isinstance(error, subprocess.TimeoutExpired)
        report['status'] = 'failed'
        report['reason'] = error.reason if isinstance(error, base.LabRefusal) else 'native_upgrade_probe_failed'
    finally:
        if moved:
            if not uncertain and idle(user):
                user.rename(workspace / 'exercised-user')
                retained.rename(user)
                report['checks']['originalUserReturned'] = base.tree_digest(user) == before
            else:
                report['checks']['originalUserReturned'] = False
            if not report['checks']['originalUserReturned']:
                (root / 'pinned-upgrade-cleanup-required').write_text('retained source and exercised trees require inspection\n')
                report['status'] = 'cleanup_failed'
        save()
    return report, report_path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab', type=Path, required=True)
    parser.add_argument('--mode', choices=['normal', 'dependency-failure'], default='normal')
    args = parser.parse_args()
    try:
        report, path = run(args.lab, args.mode)
        print(json.dumps({'status': report['status'], 'report': str(path)}))
        raise SystemExit(0 if report['status'] == 'passed' else 2)
    except (base.LabRefusal, OSError, ValueError):
        print(json.dumps({'error': {'code': 'HR-HERMESUPDATE-002', 'summaryZh': '请检查隔离测试目录后重试。',
                                   'summaryEn': 'Check the isolated test directory and retry.', 'retryable': False}}))
        raise SystemExit(2)
