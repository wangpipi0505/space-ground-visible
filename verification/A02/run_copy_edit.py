"""仅记录 A02 P3 文案修正所需命令；不覆盖上一轮六命令，不启动 curl/业务服务。"""
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A02'
def dump(name,value):
    (OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

commands=[('generate',[sys.executable,'-X','utf8','verification/A02/build_contract.py']),
          ('compile',[sys.executable,'-m','py_compile','verification/A02/build_contract.py','verification/A02/validate_contract.py',
                      'verification/A02/check_review_fixes.py','verification/A02/check_ephemeris_copy.py','verification/A02/run_copy_edit.py','verification/A02/audit_evidence.py']),
          ('contract',[sys.executable,'-X','utf8','verification/A02/validate_contract.py']),
          ('review-fixes',[sys.executable,'-X','utf8','verification/A02/check_review_fixes.py']),
          ('ephemeris-copy',[sys.executable,'-X','utf8','verification/A02/check_ephemeris_copy.py'])]
records=[]
for name,args in commands:
    start=datetime.now(timezone.utc).isoformat()
    result=subprocess.run(args,cwd=ROOT,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
    prefix='copy-edit-'+name
    stdout=OUT/(prefix+'.txt');stderr=OUT/(prefix+'-stderr.txt')
    stdout.write_bytes(result.stdout);stderr.write_bytes(result.stderr)
    records.append({'name':name,'command':args,'startTime':start,'endTime':datetime.now(timezone.utc).isoformat(),
                    'exitCode':result.returncode,'stdout':stdout.name,'stderr':stderr.name,
                    'stdoutSha256':hashlib.sha256(stdout.read_bytes()).hexdigest(),'stderrSha256':hashlib.sha256(stderr.read_bytes()).hexdigest()})
    dump('copy-edit-commands.json',{'passed':all(x['exitCode']==0 for x in records),'scope':'只生成/编译/静态合同与文案回归；原35 curl证据不改写、不重跑。','commands':records})
    print(name+': exit '+str(result.returncode))
    if result.returncode:
        print(result.stdout.decode('utf-8',errors='replace'));print(result.stderr.decode('utf-8',errors='replace'))
        raise SystemExit(result.returncode)
# 当前 validation-result 的最近来源是本轮 review-fixes 内部实际合同核验；独立副本明确区分旧记录。
(OUT/'copy-edit-validation-result.json').write_bytes((OUT/'validation-result.json').read_bytes())
print('current schema SHA256 '+hashlib.sha256((ROOT/'docs/openapi/openapi.json').read_bytes()).hexdigest())
