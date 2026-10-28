"""Preserve supplemental files from the fixed developer legacy installation, without overwrites."""
import argparse,os,json,hashlib,shutil,datetime
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--profile',choices=['development','operations'],required=True);a=p.parse_args()
source=root/'.local/storage'
if a.profile=='operations':
    config=json.loads((root/'.local/ruoyi-ops-test/development.json').read_text(encoding='utf-8-sig'))
    assert config['database'].startswith('mes_ruoyi_ops_test_');data=Path(config['dataDirectory'])
else:data=root/'.local/ruoyi/data'
records=[]
for folder in ['attachments','exports','backups','logs']:
    directory=source/folder
    if not directory.exists():continue
    for file in directory.rglob('*'):
        if file.is_symlink():raise SystemExit('Symbolic links are not allowed in legacy file migration')
        if not file.is_file():continue
        relative=file.relative_to(source);target=data/'files/legacy-archive'/relative
        sha=hashlib.sha256(file.read_bytes()).hexdigest()
        if target.exists():
            if hashlib.sha256(target.read_bytes()).hexdigest()!=sha:raise SystemExit('Refusing file collision: '+str(relative))
        else:target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)
        if hashlib.sha256(target.read_bytes()).hexdigest()!=sha:raise SystemExit('Copied file digest mismatch')
        records.append({'source':relative.as_posix(),'target':target.relative_to(data).as_posix(),'size':file.stat().st_size,'sha256':sha})
manifest={'source':'developer legacy storage','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'files':records,'compatibility':'Legacy .NET backup archives require the matching old program to restore. They are not Java/Flyway backup archives.'}
configuration=data/'configuration';configuration.mkdir(parents=True,exist_ok=True)
(configuration/'legacy-file-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'profile':a.profile,'verifiedSupplementalFiles':len(records),'bytes':sum(r['size'] for r in records)}))
