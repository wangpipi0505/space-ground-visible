"""A04历史本机实现自审：依赖ignored类/JAR/runtime，并写入selfcheck-summary。

此入口不是只读交付核验。修复后历史manifest保留修复前指纹，不应再次执行本入口；
仓库交付证据使用delivery_check.py核验，历史原字节由delivery-fix/history佐证。
"""
from pathlib import Path
from datetime import datetime
from zipfile import ZipFile
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A04'
RUN = ROOT / 'verification/runtime/A04'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    """每条断言使用当前实际文件，不接受旧阶段通过标志作为本轮通过证据。"""
    artifacts = load(OUT/'artifact-manifest.json')
    for item in artifacts['files']:
        path=ROOT/item['path']
        assert path.stat().st_size==item['bytes'] and sha(path)==item['sha256'], item['path']
    inputs = load(OUT/'input-bindings.json')
    for item in inputs['inputs']:
        path=Path(item['path'])
        assert path.stat().st_size==item['bytes'] and sha(path)==item['sha256'], item['path']
    commands=[]
    for path in sorted(OUT.glob('*-command.json')):
        command=load(path)
        assert datetime.fromisoformat(command['endedUtc'])>=datetime.fromisoformat(command['startedUtc'])
        assert Path(command['cwd']).is_dir() and command['argv']
        for stream in ('stdout','stderr'):
            assert sha(OUT/command[stream+'File'])==command[stream+'Sha256']
        assert command['exitCode']==(1 if path.name=='measurement-r1-command.json' else 0), path.name
        commands.append(dict(file=path.name,exitCode=command['exitCode']))
    totals=dict(tests=0,failures=0,errors=0,skipped=0)
    reports=list((OUT/'surefire-reports').glob('TEST-*.xml'))
    for path in reports:
        root=ET.parse(path).getroot()
        for key in totals: totals[key]+=int(root.attrib[key])
    assert len(reports)==23 and totals==dict(tests=77,failures=0,errors=0,skipped=0)
    numeric=load(OUT/'numeric-summary.json')
    assert numeric['passed'] and [(x['name'],x['actualFiles']) for x in numeric['comparisons']]==[('ballistic15',15),('space9',9),('spaceComplete21',21)]
    assert all(not x['differences'] for x in numeric['comparisons'])
    build=load(OUT/'new-build-bindings.json')
    jar=Path(build['jarPath'])
    assert sha(jar)==build['jarSha256']
    for item in build['classes']: assert sha(RUN/'current-source'/item['path'])==item['sha256']
    with ZipFile(jar) as archive:
        classes=[name for name in archive.namelist() if name.startswith('BOOT-INF/classes/') and name.endswith('.class')]
        for name in classes:
            assert archive.read(name)==(RUN/'current-source/backend/target/classes'/name[len('BOOT-INF/classes/'):]).read_bytes(), name
    before=load(OUT/'protected-before.json')
    for item in before['files']: assert sha(ROOT/item['path'])==item['sha256'], item['path']
    report=(OUT/'精度测试报告.md').read_bytes()
    assert not report.startswith(b'\xef\xbb\xbf') and b'\r' not in report
    for name in ('run_precision.py','measure_precision.py','finalize_precision.py','selfcheck_precision.py','CoordinateConsistency.java','HoldoutInterpolation.java','PublicGridDiagnostics.java'):
        raw=(OUT/name).read_bytes()
        assert b'\r' not in raw and not raw.startswith(b'\xef\xbb\xbf'), name
    summary=dict(passed=True,artifactsChecked=len(artifacts['files']),inputsChecked=len(inputs['inputs']),
        commands=commands,junit=totals,junitClasses=len(reports),jarClassesCompared=len(classes),
        protectedFilesChecked=len(before['files']),note='实现自审；独立规格/质量复审由根处理')
    (OUT/'selfcheck-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print('PASS actual bytes/input/JAR/classes/XML/commands/protected files; '+str(len(commands))+' command records')


if __name__=='__main__':
    main()
