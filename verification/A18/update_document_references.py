"""只更新当前文档的Java归属与源码位置；历史基线、HTTP schema和字段章节保持。"""
import difflib
import json
import re
from pathlib import Path
from run_validation import ROOT, OUT, RUN, save

mapping = json.loads((OUT/'package-map.json').read_text(encoding='utf-8'))


def references(text):
    for item in sorted(mapping, key=lambda x: -len(x['old'])):
        text = text.replace(item['old'],item['new']).replace(item['oldPath'],item['newPath'])
    text = text.replace('domain.ballistic.TargetTrajectoryFitter','domain.ballistic.trajectory.TargetTrajectoryFitter')
    text = text.replace('domain.ballistic.OrbitCalculator','domain.ballistic.visibility.OrbitCalculator')
    return text


names = ['ORBIT_VISIBILITY_API.md','TARGET_TRAJECTORY_FIT_API.md','接口文档.md','接口使用说明.md',
    '独立外推工具与后续方案.md','载荷时间与文件口径.md','前端载荷配置说明.md',
    '软件功能使用说明.md','Swing与Web参数对照.md']
for name in names:
    path=ROOT/'docs'/name
    raw=path.read_bytes()
    text=references(raw.decode('utf-8'))
    if name=='接口使用说明.md':
        text=text.replace('正式 `api` Controller','正式 `api.space` / `api.ballistic` Controller')
        text=text.replace('`TargetTrajectoryFitter.FitRequest`','`BallisticTrajectoryRequests.FitRequest`')
        text=text.replace('`TargetTrajectoryFitter.SimulationRequest`','`BallisticTrajectoryRequests.SimulationRequest`')
        text=text.replace('`TargetTrajectoryFitter.RetimeTrajectoryRequest`','`BallisticTrajectoryRequests.RetimeRequest`')
    path.write_bytes(text.encode('utf-8'))

# x-source指向实际声明行，行号随导入整理变化；其余OpenAPI值严格保持。
data=json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
line_maps={}
for item in mapping:
    old=(RUN/'before/backend/src/main/java'/item['oldPath']).read_text(encoding='utf-8')
    old=references(old).replace('SpaceApiModels','SpacePlanningModels').splitlines()
    new=(ROOT/'backend/src/main/java'/item['newPath']).read_text(encoding='utf-8').splitlines()
    matches={}
    for block in difflib.SequenceMatcher(None,old,new,autojunk=False).get_matching_blocks():
        for offset in range(block.size):matches[block.a+offset+1]=block.b+offset+1
    line_maps['backend/src/main/java/'+item['oldPath']]=(item,matches)
records=[]
def walk(value):
    if isinstance(value,dict):
        for key,item in value.items():
            if key=='x-source' and isinstance(item,str):
                match=re.fullmatch(r'(.+\.java):(\d+)',item)
                if match and match[1] in line_maps:
                    record,lines=line_maps[match[1]]
                    old_line=int(match[2]);assert old_line in lines,item
                    updated='backend/src/main/java/'+record['newPath']+':'+str(lines[old_line])
                    value[key]=updated
                    records.append(dict(before=item,after=updated))
                else:
                    assert not match, item
            else:walk(item)
    elif isinstance(value,list):
        for item in value:walk(item)
walk(data)
(ROOT/'docs/openapi/openapi.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
save('接口源码位置迁移',dict(entries=len(records),allMatchedActualLines=True,changes=records))
print('当前文档引用及实际源码行',len(records))
