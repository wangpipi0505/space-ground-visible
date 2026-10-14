"""独立记录 I07/I08 必要生成和限定核验；保护原六命令及文案五命令，不运行 curl/业务。"""
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A02'
commands=[('generate',[sys.executable,'-X','utf8','verification/A02/build_contract.py']),
          ('compile',[sys.executable,'-m','py_compile','verification/A02/build_contract.py',
                      'verification/A02/validate_contract.py','verification/A02/check_review_fixes.py',
                      'verification/A02/check_ephemeris_copy.py','verification/A02/check_query_contract.py',
                      'verification/A02/run_query_fix.py','verification/A02/audit_query_fix.py']),
          ('contract',[sys.executable,'-X','utf8','verification/A02/validate_contract.py']),
          ('review-fixes',[sys.executable,'-X','utf8','verification/A02/check_review_fixes.py']),
          ('ephemeris-copy',[sys.executable,'-X','utf8','verification/A02/check_ephemeris_copy.py']),
          ('query-contract',[sys.executable,'-X','utf8','verification/A02/check_query_contract.py'])]
records=[]
for name,args in commands:
    started=datetime.now(timezone.utc).isoformat()
    result=subprocess.run(args,cwd=ROOT,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
    record={'name':name,'command':args,'startTime':started,'endTime':datetime.now(timezone.utc).isoformat(),
            'exitCode':result.returncode}
    for stream,data in [('stdout',result.stdout),('stderr',result.stderr)]:
        path=OUT/('query-fix-'+name+('-stderr' if stream=='stderr' else '')+'.txt')
        path.write_bytes(data);record[stream]=path.name
        record[stream+'Sha256']=hashlib.sha256(data).hexdigest()
    records.append(record)
    (OUT/'query-fix-commands.json').write_text(json.dumps({'passed':all(r['exitCode']==0 for r in records),
        'scope':'仅必要生成、py_compile、合同样例/路由/ref、原限定回归及I07/I08检查；无新curl/业务/压测。',
        'commands':records},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(name+': exit '+str(result.returncode))
    if result.returncode:
        print(result.stdout.decode('utf-8'));print(result.stderr.decode('utf-8'))
        raise SystemExit(result.returncode)
(OUT/'query-fix-validation-result.json').write_bytes((OUT/'validation-result.json').read_bytes())
print('current schema SHA256 '+hashlib.sha256((ROOT/'docs/openapi/openapi.json').read_bytes()).hexdigest())
