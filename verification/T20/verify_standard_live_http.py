"""独立最终包HTTP验收：标准原值、混合可见层级、启动元数据和空结果。

本文件验证新增约束协议；旧算法零差由 verify_legacy_live_http.py 单独验证。
不以新模型输出生成原版黄金，也不把矩形LVLH窗口认作旧圆锥的全球等价。
"""
import argparse
import copy
import hashlib
import json
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/T20/standard-live-http'
CONES = ('simpleConeHalfAngleDeg', 'halfConeAngleDeg', 'innerConeAngleDeg', 'outerConeAngleDeg')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    args = parser.parse_args()
    RUNTIME.mkdir(parents=True, exist_ok=True)
    statuses = []

    def post(path, request, name, expected_status=200):
        body = json.dumps(request, ensure_ascii=False, allow_nan=False).encode('utf-8')
        (RUNTIME / (name + '-request.json')).write_bytes(body)
        try:
            response = urlopen(Request(args.base_url + path, data=body,
                                      headers={'Content-Type': 'application/json'}), timeout=120)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read()
            status = response.status
        (RUNTIME / (name + '-response.json')).write_bytes(raw)
        statuses.append({'name': name, 'path': path, 'status': status, 'bytes': len(raw)})
        assert status == expected_status, (name, status, raw[:1000])
        return json.loads(raw, parse_float=Decimal), raw

    def standard(identity, payload_type):
        return {'payloadId': identity, 'payloadName': '标准载荷' + identity,
                'payloadType': payload_type, 'minDistanceKm': 0, 'maxDistanceKm': 0,
                'azimuthMinDeg': None, 'azimuthMaxDeg': None,
                'elevationMinDeg': None, 'elevationMaxDeg': None,
                'angleRateDegPerSec': -1 if payload_type == 'SPACE' else 180,
                'sunAvoidHalfAngleDeg': 0, 'startupAngleRateDegPerSec': -1}

    for payload_type in ('SPACE', 'GROUND'):
        payload = standard(payload_type, payload_type)
        echoed, _ = post('/api/v1/ballistic/payloads/preview', payload, 'preview-' + payload_type)
        assert echoed == payload and len(echoed) == 12
    invalid = standard('BAD', 'GROUND')
    invalid['angleRateDegPerSec'] = -1
    error, _ = post('/api/v1/ballistic/payloads/preview', invalid, 'preview-invalid', 400)
    assert error['code'] == 'INVALID_ARGUMENT'

    request = json.loads((ROOT / 'verification/runtime/T20/live-http/request.json').read_bytes())
    second = copy.deepcopy(request['satellites'][0])
    second.update(satelliteId='SAT-B', name='SAT-B')
    request['satellites'][1] = second
    for satellite in request['satellites']:
        hidden = standard('NO-WINDOW', 'SPACE')
        hidden['minDistanceKm'] = 1000000000
        satellite['payloads'] = [standard('STD-SPACE', 'SPACE'), standard('STD-GROUND', 'GROUND'),
                                 {'payloadId': 'LEGACY', 'payloadName': '旧对空跟踪',
                                  'payloadType': '对空跟踪', 'sunExclusionAngleDeg': 0}, hidden]

    results = []
    for output in (0, 1):
        request['trajectoryPointOutput'] = output
        result, raw = post('/api/orbit/visibility/search', request, 'mixed-points' + str(output))
        assert set(result) == {'referenceUtc', 'payloadType', 'visibilityConstraint', 'best', 'allResults'}
        assert result['payloadType'] == 'SPACE'
        assert all(result['visibilityConstraint'][field] is None for field in CONES)
        assert len(result['allResults']) == 3 and len(result['best']['targets']) == 2
        assert result['best']['visibleArcCount'] > 0
        for offset in result['allResults']:
            assert len(offset['visibleSatellites']) == 2
            for satellite in offset['visibleSatellites']:
                assert {payload['payloadId'] for payload in satellite['payloadResults']} == {
                    'STD-SPACE', 'STD-GROUND', 'LEGACY'}
                for payload in satellite['payloadResults']:
                    assert len(payload['targets']) == 2
                    if payload['payloadType'] in ('SPACE', 'GROUND'):
                        assert all(payload['visibilityConstraint'][field] is None for field in CONES)
                    else:
                        assert payload['visibilityConstraint']['innerConeAngleDeg'] == 53
                    for target in payload['targets']:
                        for arc in target['visibleArcs']:
                            assert arc['payloadId'] == payload['payloadId']
                            assert arc['payloadType'] == payload['payloadType']
                            assert bool(arc['trajectoryPoints']) == bool(output)
        results.append(result)

    # 两种点开关仅改变点数组；全部窗口边界、身份、统计与顺序仍严格比较。
    def strip_points(value):
        if isinstance(value, dict):
            return {key: ([] if key == 'trajectoryPoints' else strip_points(item))
                    for key, item in value.items()}
        if isinstance(value, list):
            return [strip_points(item) for item in value]
        return value
    assert strip_points(results[0]) == strip_points(results[1])
    request['trajectoryPointOutput'] = 0
    for startup in (None, 0, 0.1, 1000000):
        for satellite in request['satellites']:
            for payload in satellite['payloads']:
                if payload['payloadType'] in ('SPACE', 'GROUND'):
                    payload['startupAngleRateDegPerSec'] = startup
        actual, _ = post('/api/orbit/visibility/search', request, 'startup-' + str(startup))
        assert actual == results[0], '仅启动角速度元数据变化导致计算结果变化'

    # 不可见配置完整身份仅通过预览返回，计算可见层级不补空身份。
    hidden = copy.deepcopy(request['satellites'][0]['payloads'][-1])
    echoed, _ = post('/api/v1/ballistic/payloads/preview', hidden, 'hidden-preview')
    assert echoed == hidden
    for satellite in request['satellites']:
        satellite['payloads'] = [copy.deepcopy(hidden)]
    empty, _ = post('/api/orbit/visibility/search', request, 'empty-standard')
    assert empty['payloadType'] == 'SPACE'
    assert all(empty['visibilityConstraint'][field] is None for field in CONES)
    assert empty['best']['visibleArcCount'] == 0
    assert empty['best']['visibleSatellites'] == []
    for offset in empty['allResults']:
        assert offset['visibleSatellites'] == [] and offset['visibleArcCount'] == 0
    request['satellites'][0]['payloads'][0]['minDistanceKm'] = -1
    error, _ = post('/api/orbit/visibility/search', request, 'compute-invalid', 400)
    assert set(error) == {'error'}

    jar = ROOT / 'verification/runtime/T20/space-target-visible-T20.jar'
    evidence = {'passed': True, 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                'requests': statuses, 'offsetCount': 3, 'targetCount': 2, 'satelliteCount': 2,
                'configuredPayloadsPerSatellite': 4, 'visiblePayloadsPerSatellite': 3,
                'bestOffsetSecond': results[0]['best']['offsetSecond'],
                'visibleArcCount': results[0]['best']['visibleArcCount'],
                'visiblePointCount': results[0]['best']['visiblePointCount'],
                'startupMetadataVariants': [-1, None, 0, 0.1, 1000000],
                'comparison': '点开关除轨迹点数组外完整Decimal JSON一致；startup各值完整JSON一致',
                'scope': '新标准协议正确性与最终包真实HTTP，原版零差另由独立冻结控制器验证'}
    (ROOT / 'verification/T20/standard-live-http.json').write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
