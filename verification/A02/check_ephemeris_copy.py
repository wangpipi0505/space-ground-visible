"""限定核对星历风险文案与现行筛选/传播源码；不运行 Java 或 curl。"""
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A02'
doc=json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
field=doc['components']['schemas']['VisibilityPlanResponse']['properties']['targetFilterBreakdown']
description=field['description']
assert '星历过期目标并未进入传播' not in description, '公开字段仍复制过期源注释'
assert '不作为硬筛选排除' in description and '仍尝试传播' in description
assert '历史过期说明' in field['x-source-comment-status']
markdown=(ROOT/'docs/接口文档.md').read_text(encoding='utf-8')
assert '星历过期目标并未进入传播' not in markdown
assert description.replace('|','\\|') in markdown

source_path=ROOT/'backend/src/main/java/com/runisys/orbit/spacevisibility/application/PlanningFacadeService.java'
source=source_path.read_text(encoding='utf-8')
start=source.index('List<SpaceApiModels.EphemerisWarningResponse> ephemerisWarnings = ephemerisWarnings(targetEntities,')
end=source.index('for (TargetEntity target : targetEntities)',start)
between=source[start:end]
assert 'ephemerisWarnings.size()' in between
assert 'targetEntities.remove' not in between and 'targetEntities =' not in between and '.filter(' not in between
attempt=source[end:source.index('List<SpaceApiModels.ObservationConstraintRequest> requestedConstraints',end)]
assert 'orbitStateProviderFactory.fromTarget(target)' in attempt
assert 'targetOrbit.positionAt(request.getStartTime())' in attempt
assert 'targetOrbit.positionAt(request.getEndTime())' in attempt
assert 'targets.add(new VisibilityTarget(targetId, targetOrbit))' in attempt
assert 'ephemerisWarnings' not in attempt, '风险清单不能作为传播循环排除条件'

reference_path=ROOT/'backend/src/main/java/com/runisys/orbit/spacevisibility/application/ReferenceDataService.java'
reference=reference_path.read_text(encoding='utf-8')
assert '任务规划时间与发射日期不参与目标筛选' in reference
assert 'ephemerisWarningHit, maxApproximationDays' in reference
inventory=json.loads((OUT/'source-inventory.json').read_text(encoding='utf-8'))
original=inventory['classes']['VisibilityPlanResponse']['fields']['targetFilterBreakdown']['description']
assert '星历过期目标并未进入传播' in original, '源清单应保留只读原注释，不能改造源码来源'
print(json.dumps({'passed':True,'scope':'只读交付描述/正文与现行筛选、风险计数、传播尝试源码；不是新业务测试',
                  'publicDescription':description,'staleSourceCommentPreservedOnlyInInventory':True,
                  'sourceChecks':['风险计数与目标集合分离','风险后未修改目标集合','逐目标仍尝试两端传播','发射日期不参与目录筛选'],
                  'sourceSha256':{p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in [source_path,reference_path]}},ensure_ascii=False,indent=2))
