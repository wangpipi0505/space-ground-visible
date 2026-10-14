"""顺序记录A02实际命令、退出码及原输出；运行数据和校验文件只归属本项。"""
import hashlib
import json
import platform
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A02'
def write(name,value):
    (OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def refresh_manifest():
    """报告追加后仅更新实际字节清单，不重复启动接收器或改写历史命令日志。"""
    manifest=[]
    for path in sorted([ROOT/'README.md',ROOT/'docs/接口文档.md',ROOT/'docs/openapi/openapi.json']+list(OUT.rglob('*'))):
        if path.is_file() and '__pycache__' not in path.parts and path.name!='artifact-manifest.json':
            manifest.append({'path':path.relative_to(ROOT).as_posix(),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    write('artifact-manifest.json',manifest)
    print('artifact files: '+str(len(manifest)))

if '--manifest-only' in sys.argv:
    refresh_manifest()
    raise SystemExit(0)

environment={'time':datetime.now(timezone.utc).isoformat(),'cwd':str(ROOT),'platform':platform.platform(),'python':sys.version,'shell':'真实powershell.exe -NoProfile -NonInteractive','curl':'真实curl.exe，版本见curl-version.txt','frontendLockSha256':hashlib.sha256((ROOT/'frontend/pnpm-lock.yaml').read_bytes()).hexdigest(),'sourceBaseline':'ba5821a692e6a60e6cd9148ec88999cd08c339d2','javaSpringOrekit':'保持产品Java8/Spring Boot2.7.18/Orekit12.0；本项未启动Java或重建，A01JAR重新计算SHA绑定源码清单。','scope':'仅A02文档、原证据读取、35 curl传输语法；不运行A01业务或压测，不修改产品/Git/台账/远端。'}
write('environment.json',environment)
write('commands.json',{'running':True,'commands':[]})
commands=[('curl-version',['curl.exe','--version']),('generate',[sys.executable,'-X','utf8','verification/A02/build_contract.py']),('compile',[sys.executable,'-m','py_compile','verification/A02/build_contract.py','verification/A02/validate_contract.py','verification/A02/verify_curl.py','verification/A02/run_verification.py','verification/A02/check_review_fixes.py','verification/A02/audit_evidence.py']),('curl-transport',[sys.executable,'-X','utf8','verification/A02/verify_curl.py']),('contract',[sys.executable,'-X','utf8','verification/A02/validate_contract.py']),('review-fixes',[sys.executable,'-X','utf8','verification/A02/check_review_fixes.py'])]
results=[]
for name,args in commands:
    start=datetime.now(timezone.utc).isoformat()
    p=subprocess.run(args,cwd=ROOT,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
    output=p.stdout.decode('utf-8',errors='replace')
    error=p.stderr.decode('utf-8',errors='replace')
    # 原 stdout/stderr 必须逐字节保存；Windows 文本换行转换会将 CRLF 写成 CRCRLF。
    stdout_path=OUT/(name+'.txt')
    stderr_path=OUT/(name+'-stderr.txt')
    stdout_path.write_bytes(p.stdout)
    stderr_path.write_bytes(p.stderr)
    results.append({'name':name,'command':args,'startTime':start,'endTime':datetime.now(timezone.utc).isoformat(),'exitCode':p.returncode,'stdout':name+'.txt','stderr':name+'-stderr.txt','stdoutSha256':hashlib.sha256(stdout_path.read_bytes()).hexdigest(),'stderrSha256':hashlib.sha256(stderr_path.read_bytes()).hexdigest()})
    write('commands.json',{'running':False,'passed':all(x['exitCode']==0 for x in results),'commands':results})
    print(name+': exit '+str(p.returncode))
    if p.returncode:
        print(output)
        print(error)
        raise SystemExit(p.returncode)

refresh_manifest()
