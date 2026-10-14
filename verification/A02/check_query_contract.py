"""限定核对目录查询兼容字段与三种统计上下文；静态/机器合同检查，不运行 Java HTTP。"""
import contextlib
import hashlib
import io
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'verification/A02'
doc = json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
schemas = doc['components']['schemas']
with contextlib.redirect_stdout(io.StringIO()):
    validator = {'__file__':str(OUT/'validate_contract.py')}
    try:
        exec(compile((OUT/'validate_contract.py').read_text(encoding='utf-8'), str(OUT/'validate_contract.py'), 'exec'), validator)
    except SystemExit as finished:
        assert finished.code == 0, '基础合同核验失败'

SOURCE = ROOT/'backend/src/main/java/com/runisys/orbit/spacevisibility'
paths = [SOURCE/'api/SpaceApiModels.java', SOURCE/'api/SpaceVisibilityController.java',
         SOURCE/'application/PlanningFacadeService.java', SOURCE/'application/ReferenceDataService.java']
models, controller, planning, reference = [p.read_text(encoding='utf-8') for p in paths]
markdown = (ROOT/'docs/接口文档.md').read_text(encoding='utf-8')

def section(source, start, end):
    begin = source.index(start)
    return source[begin:source.index(end, begin)]

def accepts(value, schema):
    """使用既有真实递归校验器，隔离合成合同案例；结果不冒称 Java 绑定实测。"""
    errors = validator['errors']; saved = list(errors); errors.clear()
    validator['validate'](value, schema, '限定合成合同案例')
    accepted = not errors
    errors[:] = saved
    return accepted

def published(owner, name):
    field = schemas[owner]['properties'][name]
    assert field['description'].replace('|','\\|') in markdown, owner+'.'+name+' 中文/schema不同步'
    return field

def query_compatibility():
    query = section(planning, 'public SpaceApiModels.TargetQueryResponse queryTargets(',
                    '/** 返回由目标表实时生成')
    dto = section(models, 'public static class TargetQueryRequest {', 'public static class TargetQueryResponse {')
    assert 'return planningFacadeService.queryTargets(request);' in controller
    for name in ['PlanningStartTime','PlanningEndTime','MaxApproximationDays']:
        assert 'get'+name+'(' not in query
        assert 'set'+name+'(' in dto
    assert 'resolveMaxApproximationDays' not in query and 'validateTimeRange' not in query
    assert 'private Instant planningStartTime;' in dto and 'private Instant planningEndTime;' in dto
    assert 'private Long maxApproximationDays;' in dto
    query_schema = schemas['TargetQueryRequest']
    for name in ['planningStartTime','planningEndTime','maxApproximationDays']:
        field = published('TargetQueryRequest', name)
        assert field['nullable'] and name not in query_schema.get('required', [])
        assert '兼容' in field['description'] and '不读取' in field['description'], name+' 未公开业务忽略'
        assert '源注释：' not in field['description'] and '历史过期说明' in field['x-source-comment-status']
    for name in ['planningStartTime','planningEndTime']:
        assert query_schema['properties'][name]['type']=='string'
        assert query_schema['properties'][name]['format']=='date-time'
    days = query_schema['properties']['maxApproximationDays']
    assert days['type']=='integer' and days['format']=='int64'
    assert 'minimum' not in days and 'default' not in days, '查询兼容阈值被错误限制或默认'
    cases = [{}, {'maxApproximationDays':-1}, {'maxApproximationDays':None},
             {'planningStartTime':'2026-10-14T01:00:00Z'}, {'planningEndTime':'2026-10-14T01:00:00Z'},
             {'planningStartTime':'2026-10-15T01:00:00Z','planningEndTime':'2026-10-14T01:00:00Z'},
             {'planningStartTime':None,'planningEndTime':None}]
    for value in cases:
        assert accepts(value,query_schema), '兼容合法格式合成案例被拒 '+repr(value)
    invalid = [{'planningStartTime':'invalid-Instant'}, {'planningEndTime':'invalid-Instant'}, {'maxApproximationDays':'bad-Long'}]
    for value in invalid:
        assert not accepts(value,query_schema), '忽略业务不可等同于忽略格式 '+repr(value)
    plan_days = published('VisibilityPlanRequest','maxApproximationDays')
    assert plan_days['minimum']==0
    assert not accepts(-1,plan_days) and accepts(0,plan_days) and accepts(None,plan_days)
    assert 'resolveMaxApproximationDays(request.getMaxApproximationDays())' in planning
    resolver = section(planning, 'private long resolveMaxApproximationDays(', '/** 解析 TLE')
    assert 'requestValue == null ? maxOrbitApproximationDays : requestValue.longValue()' in resolver
    assert 'if (value < 0L)' in resolver and 'throw new IllegalArgumentException' in resolver
    return {'acceptedSyntheticQueryCases':cases,'rejectedSyntheticBindingCases':invalid,
            'planThresholdCases':{'negativeRejected':True,'zeroAccepted':True,'nullAccepted':True},
            'sourceChecked':'queryTargets完整方法/DTO普通setter/Controller委派/规划有效阈值解析'}

def statistics_contexts():
    constructor = section(models, 'public TargetFilterBreakdownResponse(long belongsHit,\n', 'public long getBelongsHit()')
    assert 'this.epochConstraintHit = epochHit;' in constructor
    query = section(reference, 'public SpaceApiModels.TargetQueryResponse findTargetPage(',
                    '/**', )
    assert 'filteredTargets.size(), 0L, 0L)' in query and 'page.getTotal(), 0L, 0L)' in query
    snapshot = section(planning, 'boolean usesTargetSelectionSnapshot =', 'IlluminationConstraint illuminationConstraint')
    assert 'referenceDataService.findTargetsByIds(request.getTargetIds())' in snapshot
    assert 'long selectedTargetCount = targetEntities.size();' in snapshot
    assert 'new SpaceApiModels.TargetFilterBreakdownResponse(selectedTargetCount,\n                    selectedTargetCount, selectedTargetCount, selectedTargetCount, ephemerisWarnings.size(),\n                    effectiveMaxApproximationDays)' in snapshot
    assert 'targetEntities.size(), ephemerisWarnings.size(), effectiveMaxApproximationDays)' in snapshot
    counts = section(reference, 'public SpaceApiModels.TargetFilterBreakdownResponse buildTargetFilterBreakdown(',
                     '/**')
    for line in ['countTargets(ids, belongs, null, null, null, null)',
                 'countTargets(ids, belongs, minWeight, maxWeight, null, null)',
                 'countTargets(ids, belongs, minWeight, maxWeight, epochStartTime, epochEndTime)']:
        assert line in counts
    for name in ['belongsHit','weightHit','epochHit','epochConstraintHit','excludeHit']:
        description = published('TargetFilterBreakdownResponse',name)['description']
        assert '显式目标ID快照规划' in description and 'selectedTargetCount' in description, name+' 漏快照口径'
    epoch = schemas['TargetFilterBreakdownResponse']['properties']['epochConstraintHit']['description']
    assert '恒等epochHit' in epoch and '无单独任务时间或发射过滤' in epoch
    for name in ['ephemerisWarningHit','maxApproximationDays']:
        description = published('TargetFilterBreakdownResponse',name)['description']
        assert '目录查询固定0' in description and '规划' in description
        assert '默认' not in description and '≥0' not in description
    for owner,name in [('TargetQueryResponse','filterBreakdown'),('VisibilityPlanResponse','targetFilterBreakdown')]:
        description = published(owner,name)['description']
        assert '目录' in description
        if owner=='VisibilityPlanResponse':
            assert '显式目标ID快照' in description and '不作为硬筛选排除' in description and '仍尝试传播' in description
    assert '任务时刻和发射条件筛选后命中数' not in markdown
    saved = OUT/'query-fix-history/verification/A02/source-inventory.json'
    assert (OUT/'source-inventory.json').read_bytes()==saved.read_bytes(), '源清单原Javadoc留存发生变化'
    return {'queryBranchesChecked':2,'directoryCountCallsChecked':3,
            'snapshotCandidateFieldsChecked':5,'epochConstraintAssignment':'epochHit',
            'riskCountsChecked':'目录查询固定0/目录规划风险数与实际阈值/快照规划风险数与实际阈值',
            'sourceInventoryOriginalBytesPreserved':True}

results=[]
for name,action in [('I07',query_compatibility),('I08',statistics_contexts)]:
    try:
        results.append({'finding':name,'passed':True,'details':action()})
    except (AssertionError,KeyError,ValueError) as failure:
        results.append({'finding':name,'passed':False,'error':str(failure)})
output={'passed':all(r['passed'] for r in results), 'results':results,
        'sourceSha256':{p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
        'currentSchemaSha256':hashlib.sha256((ROOT/'docs/openapi/openapi.json').read_bytes()).hexdigest(),
        'boundary':'限定合成schema案例与只读源码断言；不是实跑Java绑定、HTTP或传播测试。'}
print(json.dumps(output,ensure_ascii=False,indent=2))
sys.exit(0 if output['passed'] else 1)
