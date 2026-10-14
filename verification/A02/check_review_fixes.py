"""A02 独审限定回归：只读交付合同/源码与既有证据，不启动业务服务。"""
import contextlib
import copy
import hashlib
import io
import json
import re
import runpy
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A02'
doc = json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
schemas = doc['components']['schemas']
# 复用实际核验入口；其只读原业务字节，写 A02 结构核验结果，不运行 Java。
with contextlib.redirect_stdout(io.StringIO()):
    validator = {'__file__': str(OUT/'validate_contract.py')}
    try:
        exec(compile((OUT/'validate_contract.py').read_text(encoding='utf-8'), str(OUT/'validate_contract.py'), 'exec'), validator)
    except SystemExit as final:
        assert final.code == 0, '基本结构核验失败，先修基本合同'

results = []

def check(name, action):
    """逐项保存真实断言结果，失败也完整列出，不把预期红灯写成通过。"""
    try:
        details = action()
        results.append({'finding': name, 'passed': True, 'details': details})
    except (AssertionError, KeyError) as ex:
        results.append({'finding': name, 'passed': False, 'error': str(ex)})

def accepts(value, schema):
    """调用交付核验器的真实递归校验，隔离每个限定案例产生的错误。"""
    errors = validator['errors']
    saved = list(errors)
    errors.clear()
    validator['validate'](value, schema, '限定案例')
    actual = not errors
    errors[:] = saved
    return actual

def output_dir():
    for owner in ['FitRequest', 'SimulationRequest', 'RetimeTrajectoryRequest']:
        field = schemas[owner]['properties']['outputDir']
        assert 'default' not in field, owner+' 仍把 Java 初始化值写成 HTTP 默认值'
        assert field['x-java-initial-value'] == '.'
        assert '独占目录' in field['description'] and '存在性' in field['description']
    for name in ['BallisticFitController','BallisticSimulationController','BallisticRetimeController']:
        source=(ROOT/('backend/src/main/java/com/runisys/orbit/spacevisibility/api/ballistic/'+name+'.java')).read_text(encoding='utf-8')
        assert 'body.has("outputDir")' in source, name+' 原字段存在性分支缺失'
    return {'schemas': 3, 'httpDefaults': 0, 'controllerPresenceBranches':3}

def downlink_arrays():
    schema = schemas['DownlinkPlanRequest']
    valid = {'startTime':'2026-07-01T00:00:00Z', 'endTime':'2026-07-01T01:00:00Z',
             'satelliteIds':[1], 'stations':[{'stationId':'S1'}], 'links':[{'satelliteId':1,'stationId':'S1'}]}
    cases = []
    for field in ['satelliteIds','stations','links']:
        assert field in schema['required'] and schema['properties'][field]['minItems'] == 1
        assert not schema['properties'][field].get('nullable'), field+' 允许 null'
        for label in ['缺失','null','空数组','非空']:
            value = copy.deepcopy(valid)
            if label == '缺失': value.pop(field)
            elif label == 'null': value[field] = None
            elif label == '空数组': value[field] = []
            assert accepts(value, schema) == (label == '非空'), field+' '+label
            cases.append(field+'/'+label)
    source = (ROOT/'backend/src/main/java/com/runisys/orbit/spacevisibility/application/PlanningFacadeService.java').read_text(encoding='utf-8')
    for clause in ['request.getStations() == null || request.getStations().isEmpty()',
                   'selectedSatelliteIds == null || selectedSatelliteIds.isEmpty()', 'links == null || links.isEmpty()']:
        assert clause in source, '源业务空值/空数组检查缺失'
    return {'cases': cases, 'sourceChecks': 3}

def payload_ranges():
    legacy = {'payloadType':'对地监视','minDistanceKm':-1,'maxDistanceKm':-2,'sunAvoidHalfAngleDeg':-3}
    assert accepts(legacy, schemas['PayloadDef']), '旧中文入口被无关标准数值拒绝'
    assert '标准分支' in schemas['PayloadDef']['description'] and '不能' in schemas['PayloadDef']['description']
    source = (ROOT/'backend/src/main/java/com/tx/alg/api/VisibilityRequest.java').read_text(encoding='utf-8')
    assert '? OrbitCalculator.SensorVisibilityConfig.standard(toStandardConstraint())' in source
    assert ': OrbitCalculator.SensorVisibilityConfig.forPayload(payloadType, maxDetectRangeKm, sunExclusionAngleDeg)' in source
    base = {'payloadType':'SPACE','minDistanceKm':1,'maxDistanceKm':2,'angleRateDegPerSec':0,'sunAvoidHalfAngleDeg':0}
    cases = []
    for owner in ['StandardPayloadRequest','PayloadProfileRequest']:
        assert accepts(base, schemas[owner]), owner+' 有效标准输入失败'
        for field, bad in [('minDistanceKm',-1),('maxDistanceKm',-1),('sunAvoidHalfAngleDeg',-1),('sunAvoidHalfAngleDeg',181)]:
            value = dict(base, **{field:bad})
            assert not accepts(value, schemas[owner]), owner+' 非法 '+field+' 被放行'
            cases.append(owner+'/'+field+'/'+str(bad))
    return {'legacyIgnoredFields': list(legacy)[1:], 'strictRejectedCases': cases,
            'boundary':'共享 PayloadDef 是兼容宽 DTO；标准分支及跨字段约束以源码/正文为准，不能冒充全部机器校验。'}

def descriptions():
    pollution = []
    count = 0
    def walk(value, path=''):
        nonlocal count
        if isinstance(value, dict):
            if isinstance(value.get('description'), str):
                count += 1
                if re.search(r'\b(?:private|public|protected)\s+', value['description']): pollution.append(path)
            for key, child in value.items(): walk(child, path+'/'+key)
        elif isinstance(value, list):
            for i, child in enumerate(value): walk(child,path+'/'+str(i))
    walk(doc)
    assert not pollution, 'description 源码污染 '+str(len(pollution))
    markdown = (ROOT/'docs/接口文档.md').read_text(encoding='utf-8')
    assert not re.search(r'\b(?:private|public|protected)\s+',markdown), 'Markdown 源码污染'
    assert '源注释：' in markdown and '最近距离' in markdown, '中文源业务说明丢失'
    # 直接验证共享提取器：长注释保留完整，不能把前一字段/getter 串到后一字段。
    contract=validator['contract']
    long_comment='完整中文业务说明。'*100
    fixture='public class ReviewJavadocFixture {\n/** 最近距离业务说明。 */\npublic Double minDistanceKm;\npublic Double getMinDistanceKm() { return minDistanceKm; }\n/** '+long_comment+' */\n@Deprecated\npublic Double maxDistanceKm;\n}'
    with tempfile.NamedTemporaryFile(dir=OUT,suffix='.java',delete=False) as file:
        fixture_path=Path(file.name)
        file.write(fixture.encode('utf-8'))
    try:
        contract.extract(fixture_path)
        fields=contract.CLASSES['ReviewJavadocFixture']['fields']
        assert fields['minDistanceKm']['description']=='最近距离业务说明。'
        assert fields['maxDistanceKm']['description']==long_comment, '紧邻长 Javadoc 未完整提取'
    finally:
        fixture_path.unlink()
        contract.CLASSES.pop('ReviewJavadocFixture',None)
    return {'descriptionsScanned':count,'polluted':len(pollution),'adjacentLongJavadocPreserved':True}

def log_bytes():
    commands = json.loads((OUT/'commands.json').read_text(encoding='utf-8'))['commands']
    mismatches = []
    for command in commands:
        for stream in ['stdout','stderr']:
            data = (OUT/command[stream]).read_bytes()
            if hashlib.sha256(data).hexdigest() != command[stream+'Sha256']: mismatches.append(command[stream])
    assert not mismatches, '日志实际字节 SHA 不一致 '+repr(mismatches)
    return {'logs':len(commands)*2,'mismatches':mismatches}

def download_media():
    content = doc['paths']['/api/v1/ballistic/files/{fileId}/download']['get']['responses']['200']['content']
    assert '*/*' in content, '缺少登记动态媒体范围'
    assert 'select_media' in validator, '未按实际 Content-Type 匹配'
    cases = []
    for value, expected in [('text/csv; charset=UTF-8','text/csv'),('text/plain','*/*'),
                            ('application/vnd.ms-excel','*/*'),('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','*/*')]:
        key, media = validator['select_media'](content,value)
        assert key == expected and media['schema']['format'] == 'binary', value+' 匹配错误'
        cases.append({'actualContentType':value,'matched':key})
    assert validator['select_media']({'text/*':{'schema':{'type':'string'}}},'text/plain')[0] == 'text/*'
    return {'cases':cases,'specificBeforeRange':True}

def published_fields():
    assert 'source_field_errors' in validator, '未对加载的 schema 比较源字段'
    assert not validator['source_field_errors'](schemas)
    changed = copy.deepcopy(schemas)
    changed['ObservationConstraintRequest']['properties'].pop('minDurationSec')
    failures = validator['source_field_errors'](changed)
    assert any('ObservationConstraintRequest' in e and 'minDurationSec' in e for e in failures), '未检出 A01 未出现字段的删除'
    keys = set()
    def walk(value):
        if isinstance(value,dict):
            keys.update(value)
            for child in value.values(): walk(child)
        elif isinstance(value,list):
            for child in value: walk(child)
    contract = validator['contract']
    for event in contract.EVENTS:
        example = contract.load_event(event)
        walk(example['request']); walk(example['response'])
    assert 'minDurationSec' not in keys, '指定字段已出现 A01 样例，须更换限定案例'
    inherited = copy.deepcopy(schemas)
    inherited['WindowResponse']['properties'].pop('durationSec')
    errors = validator['source_field_errors'](inherited)
    assert any('CoverageWindowResponse' in e and 'durationSec' in e for e in errors), '继承父模型字段未展开'
    return {'mutatedMissingField':'ObservationConstraintRequest.minDurationSec','absentFromA01AllSamples':True,
            'inheritedDeletion':'CoverageWindowResponse <- WindowResponse.durationSec','mutationsDetected':2}

def curl_bytes():
    source = (OUT/'verify_curl.py').read_text(encoding='utf-8')
    assert 'def assert_transfer(' in source, '没有可核验原字节/媒体类型的传输断言'
    scope = runpy.run_path(str(OUT/'verify_curl.py'),run_name='a02_transport_regression')
    cases = json.loads((OUT/'curl-cases.json').read_text(encoding='utf-8'))
    case = next(x for x in cases if x['body'] is not None)
    filename = case['arguments'][case['arguments'].index('--data-binary')+1][1:]
    body = (ROOT/filename).read_bytes()
    event = {'body':body,'contentType':'application/json'}
    scope['assert_transfer'](case,event)
    rejects = 0
    for changed in [dict(event,body=b' '+body),dict(event,contentType='text/plain')]:
        try: scope['assert_transfer'](case,changed)
        except AssertionError: rejects += 1
    assert rejects == 2, 'JSON 结构相同但字节不同/媒体类型错误未被拒绝'
    return {'validOriginalBytes':True,'transportMutationsRejected':rejects}

for name, action in [('I01',output_dir),('I02',downlink_arrays),('I03',payload_ranges),('I04',descriptions),
                     ('I05',log_bytes),('I06',download_media),('M01',published_fields),('M02',curl_bytes)]:
    check(name,action)
output = {'passed':all(x['passed'] for x in results),'scope':'A02 文档及核验辅助限定覆盖；不证明新的业务 HTTP 成功。','results':results}
print(json.dumps(output,ensure_ascii=False,indent=2))
sys.exit(0 if output['passed'] else 1)
