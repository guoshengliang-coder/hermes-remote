#!/usr/bin/env python3
"""Actual Desktop Core recovery material, tested only against a retained disposable I0 lab.

No official upgrade, daily installation, model call, launchd operation or production access.
The Core never replaces live paths; this explicit lab controller temporarily installs its
material at the original synthetic paths and always puts the untouched originals back.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import uuid

SOURCE=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('recovery_lab_base',SOURCE/'scripts/hermes-compatibility-lab.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)


def digest(file):
    result=hashlib.sha256()
    with file.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):result.update(block)
    return result.hexdigest()


def core_digest():
    entries=[[str(p.relative_to(SOURCE)),digest(p)] for p in sorted((SOURCE/'desktop/Sources/HermesGoDesktopCore').glob('*.swift'))]
    return hashlib.sha256(json.dumps(entries,separators=(',',':')).encode()).hexdigest()


def run(root):
    root=root.resolve();info=root.lstat()
    if (root.parent!=Path(tempfile.gettempdir()).resolve() or not root.name.startswith('hermes-compat-lab-')
            or not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode&0o077):
        raise base.LabRefusal('disposable_lab_required')
    home=root/'user/.hermes';checkout=home/'hermes-agent'
    if home.resolve()!=home or base.git_read(checkout,'rev-parse','HEAD').decode().strip()!=base.RUNTIME_SOURCE:
        raise base.LabRefusal('pinned_source_required')
    runtime=(checkout/'venv/bin/python').resolve().parent.parent
    if runtime.parent!=root/'user/.local/share/uv/python' or not runtime.name.startswith('cpython-'):
        raise base.LabRefusal('audited_uv_runtime_required')
    for marker in root.glob('*cleanup-required*'):
        raise base.LabRefusal('previous_cleanup_required')
    # Do not start when another process has either synthetic SQLite file open. This is an
    # additional laboratory preflight, not a production service-drain/ownership certificate.
    check=subprocess.run(['/usr/sbin/lsof','-t',str(home/'state.db')],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    if check.returncode not in (0,1) or check.stdout.strip():raise base.LabRefusal('synthetic_database_in_use')
    run_id=str(uuid.uuid4());workspace=root/('native-recovery-'+run_id)
    before=[base.tree_digest(home),base.tree_digest(runtime)]
    source_hash=core_digest()
    report={'schemaVersion':1,'kind':'desktop-native-recovery-material','status':'running','compatibilityApproved':False,
        'sourceCommit':base.RUNTIME_SOURCE,'sourceVersion':'0.21.3','coreSourceSha256':source_hash,
        'driverSha256':digest(Path(__file__)),'helperSourceSha256':digest(SOURCE/'scripts/hermes-recovery-point-helper.swift'),
        'checks':{},'notExecuted':['officialUpgrade','automaticRestoreCommit','upstreamOwnership',
            'crashDuringLivePathCommit','newDataDowngradeAuthorization','installedDesktopJourney']}
    retained=[]
    with tempfile.TemporaryDirectory(prefix='hermes-native-recovery-build-') as build_dir:
        build_root=Path(build_dir);helper=build_root/'probe';log=build_root/'build.private.log'
        log.touch(mode=0o600)
        with log.open('wb') as output:
            subprocess.run(['swift','build','--package-path',str(SOURCE/'desktop'),'--target','HermesGoDesktopCore'],check=True,stdout=output,stderr=output,timeout=300)
            binary=Path(subprocess.run(['swift','build','--package-path',str(SOURCE/'desktop'),'--show-bin-path'],check=True,
                text=True,stdout=subprocess.PIPE,stderr=output,timeout=30).stdout.strip())
            objects=sorted((binary/'HermesGoDesktopCore.build').glob('*.o'))
            if not objects:raise base.LabRefusal('core_objects_missing')
            subprocess.run(['swiftc','-parse-as-library','-I',str(binary/'Modules'),
                str(SOURCE/'scripts/hermes-recovery-point-helper.swift'),*map(str,objects),'-o',str(helper)],
                check=True,stdout=output,stderr=output,timeout=120)
        report['helperBinarySha256']=digest(helper)
        env={'PATH':'/usr/bin:/bin','HOME':str(build_root),'TMPDIR':tempfile.gettempdir()}
        try:
            for action,status in [('capture','captured'),('materialize','materialized')]:
                result=subprocess.run([str(helper),action,str(root),run_id],env=env,text=True,
                    stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=600)
                if result.returncode!=0 or json.loads(result.stdout)!={'status':status}:
                    raise base.LabRefusal('native_'+action+'_refused')
                report['checks'][action]={'passed':True}
            if [base.tree_digest(home),base.tree_digest(runtime)]!=before:
                raise base.LabRefusal('materialization_changed_originals')
            report['checks']['originalsUnchangedBeforeCommit']={'passed':True}
            plan=json.loads((workspace/'material.json').read_text())
            if plan['runID'].lower()!=run_id or len(plan['roots'])!=2:raise base.LabRefusal('material_identity_mismatch')
            roots=[home,runtime]
            for index,item in enumerate(plan['roots']):
                original=Path(item['original']).resolve();staged=Path(item['staged']).resolve()
                if original!=roots[index] or not staged.is_relative_to(workspace/'points'):
                    raise base.LabRefusal('material_path_mismatch')
                if base.tree_digest(staged)!=before[index]:raise base.LabRefusal('material_bytes_mismatch')
            for index,item in enumerate(plan['roots']):
                original=roots[index];saved=workspace/('retained-original-'+str(index))
                retained.append((original,saved,index))
                original.rename(saved)
                Path(item['staged']).rename(original)
            report['checks']['restoredAtOriginalPaths']={'passed':True,'treeDigests':before}
            name='nativeRecovery-'+run_id
            profile=workspace/'isolation.sb'
            profile.write_text(base.sandbox_profile(root,Path.home())+
                '(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n'+
                '(allow file-write* (literal '+json.dumps(str(root/(name+'.private.log')))+'))\n')
            profile.chmod(0o600)
            report['checks']['restoredHistory']=base.probe_history(root,profile,base.isolated_environment(root),
                checkout/'venv/bin/hermes',name,'0.21.3')
            report['checks']['restoredConfiguration']={'passed':(home/'.env').read_bytes()==(workspace/'retained-original-0/.env').read_bytes()
                and (home/'config.yaml').read_bytes()==(workspace/'retained-original-0/config.yaml').read_bytes()}
        except Exception as error:
            report['status']='failed'
            report['reason']=error.reason if isinstance(error,base.LabRefusal) else 'recovery_probe_failed'
        finally:
            restored=True
            if retained:
                live=subprocess.run(['/usr/sbin/lsof','-t',str(home/'state.db'),str((checkout/'venv/bin/python').resolve())],
                    stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
                restored=live.returncode in (0,1) and not live.stdout.strip()
            for original,saved,index in reversed(retained) if restored else []:
                try:
                    if saved.exists():
                        if original.exists():original.rename(workspace/('tested-material-'+str(index)))
                        saved.rename(original)
                except OSError:restored=False
            restored=restored and [base.tree_digest(home),base.tree_digest(runtime)]==before
            report['checks']['untouchedOriginalsReturned']={'passed':restored}
            if not restored:
                report['status']='failed';report['reason']='original_path_recovery_required'
                marker=root/'native-recovery-cleanup-required.json'
                marker.write_text(json.dumps({'runID':run_id,'code':'HR-HERMESUPDATE-003'})+'\n');marker.chmod(0o600)
        report['checks']['sourceUnchanged']={'passed':source_hash==core_digest()}
        if report['status']!='failed' and all(v['passed'] for v in report['checks'].values()):report['status']='passed'
        else:report['status']='failed'
        if workspace.exists():
            destination=workspace/'report.json';destination.write_text(json.dumps(report,indent=2)+'\n');destination.chmod(0o600)
        return report,workspace/'report.json'


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--lab',type=Path,required=True);args=parser.parse_args()
    try:
        result,path=run(args.lab)
        output={'status':result['status'],'report':str(path)}
        if result['status']!='passed':output['error']=base.error_payload(recovery_required=True)
        print(json.dumps(output,ensure_ascii=False));raise SystemExit(0 if result['status']=='passed' else 1)
    except base.LabRefusal as error:
        print(json.dumps({'status':'refused','reason':error.reason,
            'error':base.error_payload(has_report=False)},ensure_ascii=False));raise SystemExit(2)
    except Exception:
        print(json.dumps({'status':'failed','reason':'recovery_probe_failed',
            'error':base.error_payload(recovery_required=True,has_report=False)},ensure_ascii=False));raise SystemExit(2)
