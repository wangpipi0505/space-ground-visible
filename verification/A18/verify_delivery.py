"""核对最终HTTP文档、历史数据保护、序列化及测试/源码包编译绑定。"""
import hashlib
import json
import re
import subprocess
from pathlib import Path
from run_validation import ROOT, OUT, RUN, save


def strip_source(value):
    if isinstance(value,dict):return {k:strip_source(v) for k,v in value.items() if k!='x-source'}
    if isinstance(value,list):return [strip_source(x) for x in value]
    return value


previous=json.loads(subprocess.run(['git','show','d57c342:docs/openapi/openapi.json'],cwd=ROOT,capture_output=True,check=True).stdout)
current=json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
assert strip_source(previous)==strip_source(current)
headings={}
for name in ('ORBIT_VISIBILITY_API.md','TARGET_TRAJECTORY_FIT_API.md'):
    old=subprocess.run(['git','show','d57c342:docs/'+name],cwd=ROOT,capture_output=True,check=True).stdout.decode('utf-8')
    now=(ROOT/'docs'/name).read_text(encoding='utf-8')
    assert re.findall(r'^#{2,3} .+$',old,re.M)==re.findall(r'^#{2,3} .+$',now,re.M)
    headings[name]=len(re.findall(r'^#{2,3} .+$',now,re.M))
serial={}
for stage in ('before','after-v3'):
    serial[stage]={p.name:hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in (RUN/('serialization-'+stage)/'output').iterdir()}
assert serial['before']==serial['after-v3'] and len(serial['before'])==48
save('serialization-compare',dict(passed=True,files=48,sha256=serial))
originals=[]
for name,directory in [('space',Path('G:/5-YG/target-visible-alg/space-target-visible-main-集成交付')),
                       ('ballistic',Path('G:/Code/Code-V3/alg/deliverables/alg-0723'))]:
    items=json.loads((ROOT/('verification/T02/'+name+'-manifest.json')).read_text(encoding='utf-8-sig'))
    bad=[x['path'] for x in items if hashlib.sha256((directory/x['path']).read_bytes()).hexdigest()!=x['sha256']]
    originals.append(dict(name=name,files=len(items),differences=bad))
assert all(not x['differences'] for x in originals)
front=[]
paths=subprocess.run(['git','ls-files','-z','frontend'],cwd=ROOT,capture_output=True,check=True).stdout.decode().split('\0')
for name in paths:
    if not name:continue
    expected=subprocess.run(['git','show','d57c342:'+name],cwd=ROOT,capture_output=True,check=True).stdout
    actual=(ROOT/name).read_bytes()
    # 项目文本受Git LF约定管理；检查源码正文，防止本机CRLF误判为产品改动。
    assert expected.replace(b'\r\n',b'\n')==actual.replace(b'\r\n',b'\n'),name
    front.append(name)
save('合同与保护核验',dict(passed=True,openApiOnlySourceLocationsChanged=True,originalDocumentHeadings=headings,
    originalDirectories=originals,frontendFilesUnchanged=len(front),frontendLockUnchanged=True))

suffix='-r2' if (OUT/'package-record-r2.json').exists() else ''
record=json.loads((OUT/('package-record'+suffix+'.json')).read_text(encoding='utf-8'))
final=RUN/'final/backend/target/classes'
delivered=Path(record['extraction'])/'backend/target/classes'
def classes(root):return {p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*.class')}
assert classes(final)==classes(delivered) and len(classes(final))==298
sources=json.loads((OUT/('package-source-files'+suffix+'.json')).read_text(encoding='utf-8'))
for item in sources:
    assert hashlib.sha256((ROOT/item['path']).read_bytes()).hexdigest()==item['sha256'],item['path']
save('final-bindings',dict(passed=True,testedAndDeliveredClassFiles=298,classBytesIdentical=True,
    sourceFiles=len(sources),currentSourceBytesMatchPackage=True,packageSha256=record['sha256']))
print('PASS HTTP合同、原格式标题、48序列化、697原版、前端保护与298交付class绑定')
