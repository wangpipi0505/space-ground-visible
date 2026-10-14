"""A04完整数值统计：逐结构/行/列验证，Decimal不舍入；异常数据与漏列即失败。"""
from pathlib import Path
from datetime import datetime, timezone
from decimal import Decimal, localcontext, getcontext
import copy
import csv
import hashlib
import json
import math
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A04'
RUN = ROOT / 'verification/runtime/A04'
D = Decimal
# 原源最大有效位远少于80；构造1e-30反例时也必须保留微小改动，不能先被默认28位舍入吞掉。
getcontext().prec = 80


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, data):
    """Decimal以字符串记录实际十进制，不通过float削弱微小差异。"""
    (OUT / (name + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf-8', newline='\n')


def load(path):
    """JSON拒绝非标准NaN/Infinity；随后逐数值叶验证有限数。"""
    def invalid(value):
        raise ValueError('非有限JSON：' + value)
    return json.loads(path.read_text(encoding='utf-8-sig'), parse_float=D, parse_int=D, parse_constant=invalid)


def numeric(value):
    result = D(value)
    assert result.is_finite(), '非有限数值'
    return result


def norm(values):
    with localcontext() as context:
        context.prec = 50
        return sum(x * x for x in values).sqrt()


def paired(left, right, path='$'):
    """不取字段交集；精确验证结构及数组长度/顺序，逐叶返回路径和左右值。"""
    assert type(left) is type(right), path + ' 类型不同'
    if isinstance(left, dict):
        assert left.keys() == right.keys(), path + ' 字段集合不同'
        for key in left:
            yield from paired(left[key], right[key], path + '.' + key)
    elif isinstance(left, list):
        assert len(left) == len(right), path + ' 数组长度不同'
        for index, (a, b) in enumerate(zip(left, right)):
            yield from paired(a, b, path + '[' + str(index) + ']')
    else:
        if isinstance(left, D):
            numeric(left)
            numeric(right)
        yield path, left, right


def utc(value):
    """输入仅接受实际ISO或原UTC格式；所有时刻使用显式UTC解析，不能按本地时区错型。"""
    assert isinstance(value, str), '时间类型错误'
    if 'T' in value:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
        assert result.tzinfo is not None, 'ISO缺少时区'
        return result.astimezone(timezone.utc)
    return datetime.strptime(value.replace('/', '-'), '%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone.utc)


def seconds(delta):
    return D(delta.days * 86400 + delta.seconds) + D(delta.microseconds) / 1000000


def window_records(left, right, path='$'):
    """枚举全部含实际时长的窗口及光照分段；请求边界不冒充可见窗口。"""
    assert type(left) is type(right)
    if isinstance(left, dict):
        assert left.keys() == right.keys(), path
        duration_key = 'durationSeconds' if 'durationSeconds' in left else 'durationSec'
        if 'startTime' in left and 'endTime' in left and duration_key in left:
            start, end = utc(left['startTime']), utc(left['endTime'])
            astart, aend = utc(right['startTime']), utc(right['endTime'])
            duration, actual_duration = numeric(left[duration_key]), numeric(right[duration_key])
            assert end >= start and aend >= astart and duration >= 0 and actual_duration >= 0, path
            item = dict(path=path, expectedStart=left['startTime'], actualStart=right['startTime'],
                expectedEnd=left['endTime'], actualEnd=right['endTime'], expectedDurationSec=duration,
                actualDurationSec=actual_duration, startDeltaSec=seconds(astart - start), endDeltaSec=seconds(aend - end),
                durationDeltaSec=actual_duration - duration, printedUtcSpanSec=seconds(end - start))
            for field in ('startJ2000Sec', 'endJ2000Sec'):
                if field in left:
                    item[field + 'DeltaSec'] = numeric(right[field]) - numeric(left[field])
                    item['expected' + field] = left[field]
                    item['actual' + field] = right[field]
            yield item
        for key in left:
            yield from window_records(left[key], right[key], path + '.' + key)
    elif isinstance(left, list):
        assert len(left) == len(right), path
        for index, (a, b) in enumerate(zip(left, right)):
            yield from window_records(a, b, path + '[' + str(index) + ']')


def trajectory(path):
    """CSV字段全量读取，禁止缺行、未知多余单元格、非有限数或时序倒置。"""
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        fields = reader.fieldnames
        assert fields and len(fields) == len(set(fields)), path
        rows = list(reader)
    assert rows, path
    required = ['YYYY', 'MM', 'dd', 'HH', 'mm', 'ss.SSS', 'isOriginal'] + [v + s for v in ('ECI_', 'ECEF_') for s in ('X', 'Y', 'Z')]
    required += ['ECI_V' + s for s in ('X', 'Y', 'Z')] + ['ECEF_V' + s for s in ('x', 'y', 'z')]
    assert all(key in fields for key in required), '轨迹缺少字段'
    previous = None
    for row in rows:
        assert None not in row and all(value is not None and value != '' for value in row.values()), 'CSV缺失单元格'
        for value in row.values():
            numeric(value)
        stamp = row['YYYY'] + '-' + row['MM'] + '-' + row['dd'] + ' ' + row['HH'] + ':' + row['mm'] + ':' + row['ss.SSS']
        instant = utc(stamp)
        assert previous is None or instant > previous, 'CSV时序错误'
        assert row['isOriginal'] in ('0', '1'), '原始点类型错误'
        row['_time'] = instant
        previous = instant
    return fields, rows


def measure():
    """逐文件统计全部JSON数值、窗口边界/偏移和四份完整轨迹；严格比较不改原门禁。"""
    comparisons, windows, offsets, trajectories = [], [], [], []
    sources = [('ballistic15', ROOT / 'test-data/baseline/ballistic', RUN / 'ballistic-actual'),
               ('space9', ROOT / 'test-data/baseline/space', RUN / 'space-actual'),
               ('spaceComplete21', RUN / 'complete21-original-actual', RUN / 'complete21-current-actual')]
    for label, expected, actual in sources:
        left_files = {p.relative_to(expected) for p in expected.rglob('*') if p.is_file()}
        right_files = {p.relative_to(actual) for p in actual.rglob('*') if p.is_file()}
        assert left_files == right_files, '文件集合不同'
        for relative in sorted(left_files):
            a, b = expected / relative, actual / relative
            item = dict(group=label, file=relative.as_posix(), expectedSha256=sha(a), actualSha256=sha(b))
            if relative.suffix == '.json':
                left, right = load(a), load(b)
                leaves = list(paired(left, right))
                numbers = [(p, x, y) for p, x, y in leaves if isinstance(x, D)]
                differences = [dict(path=p, expected=x, actual=y) for p, x, y in leaves if x != y]
                item.update(numericValues=len(numbers), allLeaves=len(leaves), differences=differences,
                    maxAbsNumericDelta=max((abs(y-x) for _, x, y in numbers), default=D(0)))
                for entry in window_records(left, right):
                    windows.append(dict(group=label, file=relative.as_posix(), **entry))
                for path, x, y in numbers:
                    if path.endswith(('.offsetSecond', '.startOffsetSec', '.stepSizeSec')):
                        offsets.append(dict(group=label, file=relative.as_posix(), path=path, expected=x, actual=y, delta=y-x))
                assert not differences, item
            else:
                item.update(byteEqual=a.read_bytes() == b.read_bytes(), bytes=a.stat().st_size)
                assert item['byteEqual'], item
            if relative.suffix == '.csv':
                fields, rows = trajectory(a)
                actual_fields, actual_rows = trajectory(b)
                assert fields == actual_fields and len(rows) == len(actual_rows)
                components = {key: D(0) for key in fields}
                max_vectors = {}
                for row, other in zip(rows, actual_rows):
                    assert row['_time'] == other['_time'], '轨迹时间错位'
                    for key in fields:
                        components[key] = max(components[key], abs(numeric(other[key])-numeric(row[key])))
                    for frame, names in [('eciPositionM', ['ECI_X', 'ECI_Y', 'ECI_Z']), ('ecefPositionM', ['ECEF_X', 'ECEF_Y', 'ECEF_Z']),
                                         ('eciVelocityMps', ['ECI_VX', 'ECI_VY', 'ECI_VZ']), ('ecefVelocityMps', ['ECEF_Vx', 'ECEF_Vy', 'ECEF_Vz'])]:
                        value = norm([numeric(other[key])-numeric(row[key]) for key in names])
                        max_vectors[frame] = max(max_vectors.get(frame, D(0)), value)
                trajectories.append(dict(group=label, file=relative.as_posix(), rows=len(rows), columns=fields,
                    numericCellsCompared=len(rows)*len(fields), positionVectorsCompared=2*len(rows), velocityVectorsCompared=2*len(rows),
                    maxAbsComponentDeltas=components, maxVectorNormDeltas=max_vectors, expectedSha256=sha(a), actualSha256=sha(b)))
            comparisons.append(item)
    save('complete-differences', dict(files=comparisons, totalFiles=len(comparisons), passed=True))
    save('windows', dict(records=windows, count=len(windows), note='包含汇总/最佳/各偏移的复制窗口和光照分段，按JSON路径逐个比较；独立可见弧数量另计'))
    save('offsets', dict(records=offsets, count=len(offsets)))
    save('trajectory-differences', dict(files=trajectories))
    return windows


def observations():
    """按目标ID与精确UTC匹配原102观测；本样例未触发targetOrbit单位重标定。"""
    request = load(RUN / 'ballistic-runner/fit-request.json')
    result = load(RUN / 'ballistic-actual/fit/result.json')
    metrics = []
    for file in result['files']:
        fields, rows = trajectory(RUN / 'ballistic-actual/fit' / file['csvPath'])
        by_time = {r['_time']: r for r in rows}
        records = [r for r in request['package102'] if r['track_tgt_id'] == file['targetId']]
        assert records and not any('检测到targetOrbit' in warning for warning in file['warnings']), '需按实际源重新解释速度缩放'
        samples = []
        for observed in records:
            instant = utc(observed['tack3d_time_code'])
            assert instant in by_time, '遗漏原观测时刻'
            row = by_time[instant]
            assert row['isOriginal'] == '1', '原始观测未命中'
            position = [numeric(observed['tack3d_tgt_pos_' + axis]) * D('0.1') for axis in 'xyz']
            velocity = [numeric(observed['tack3d_tgt_vel_' + axis]) * D('0.0001') for axis in 'xyz']
            output_position = [numeric(row['ECI_' + axis.upper()]) for axis in 'xyz']
            output_velocity = [numeric(row['ECI_V' + axis.upper()]) for axis in 'xyz']
            dp, dv = [b-a for a, b in zip(position, output_position)], [b-a for a, b in zip(velocity, output_velocity)]
            samples.append(dict(timeUtc=observed['tack3d_time_code'], isOriginal=row['isOriginal'], sourcePositionM=position,
                actualPositionM=output_position, positionResidualM=dp, positionNormM=norm(dp), sourceVelocityMps=velocity,
                actualVelocityMps=output_velocity, velocityResidualMps=dv, velocityNormMps=norm(dv)))
        first, last = min(utc(r['tack3d_time_code']) for r in records), max(utc(r['tack3d_time_code']) for r in records)
        metrics.append(dict(targetId=file['targetId'], model=file['model'], rows=len(rows), observations=len(records), samples=samples,
            outputStartUtc=str(rows[0]['_time']), outputEndUtc=str(rows[-1]['_time']),
            outsideObservationRows=sum(r['_time'] < first or r['_time'] > last for r in rows),
            interpolationRows=sum(first < r['_time'] < last and r['isOriginal']=='0' for r in rows),
            originalRows=sum(r['isOriginal']=='1' for r in rows), warnings=file['warnings']))
    save('package102-residuals', dict(positionInputUnitM='0.1', velocityInputUnitMps='0.0001',
        accelerationInputUnitMps2='0.01', velocityRescaleTriggered=False, targets=metrics,
        note='源观测原始点硬约束残差；不是点间插值或区间外预测的独立真值误差'))


def retime():
    """逐行验证真实UTC平移、ECEF保留和原相对dt/新增timeIntervalMs语义。"""
    _, source = trajectory(RUN / 'ballistic-actual/simulate/trajectory-0.csv')
    _, actual = trajectory(RUN / 'ballistic-actual/retime/trajectory-0.csv')
    assert len(source) == len(actual)
    delta = seconds(actual[0]['_time'] - source[0]['_time'])
    fields = ['ECEF_X', 'ECEF_Y', 'ECEF_Z', 'ECEF_Vx', 'ECEF_Vy', 'ECEF_Vz']
    unchanged = all(all(a[key] == b[key] for key in fields) for a, b in zip(source, actual))
    shifts = [seconds(b['_time'] - a['_time']) for a, b in zip(source, actual)]
    assert unchanged and all(value == delta for value in shifts)
    for index, row in enumerate(actual):
        assert numeric(row['dt']) == seconds(row['_time'] - actual[0]['_time']) * 1000
        interval = D(0) if not index else seconds(row['_time'] - actual[index-1]['_time']) * 1000
        assert numeric(row['timeIntervalMs']) == interval
    save('retime-metrics', dict(rows=len(actual), ecefComponentsCompared=6*len(actual), ecefTextPreserved=unchanged,
        sourceStartUtc=str(source[0]['_time']), actualStartUtc=str(actual[0]['_time']), timeShiftSec=delta,
        minShiftSec=min(shifts), maxShiftSec=max(shifts), actualRelativeDtVerified=True, actualTimeIntervalVerified=True,
        eciPositionChangedRows=sum(any(a['ECI_'+key]!=b['ECI_'+key] for key in 'XYZ') for a,b in zip(source,actual))))


def coordinates():
    """统计Java原公式逐行测量的全部分量与范数，无主观通过容差。"""
    files = []
    for path in sorted(OUT.glob('coordinate-*.stdout.bin')):
        if 'compile' in path.name:
            continue
        rows = list(csv.DictReader(path.read_bytes().decode('utf-8').splitlines()))
        assert rows
        numeric_fields = [key for key in rows[0] if key not in ('row', 'timeUtc')]
        maxima = {key:max(abs(numeric(row[key])) for row in rows) for key in numeric_fields}
        rms = {key:(sum(numeric(row[key])**2 for row in rows)/D(len(rows))).sqrt() for key in numeric_fields}
        files.append(dict(file=path.name, rows=len(rows), sha256=sha(path), maxAbs=maxima, rms=rms))
    assert len(files)==4
    save('coordinate-metrics', dict(files=files, criterion='测量不设绝对误差通过线；原公式自洽+序列化量化，不是独立外部真值'))


def counterexamples():
    """合成微小数值、边界、原点、结构/非有限/时间错型反例必须被本统计器检出。"""
    tests = []
    original = load(RUN / 'ballistic-actual/visibility-two_body-points1/result.json')
    target = next(t for s in original['allResults'][0]['visibleSatellites'] for p in s['payloadResults'] for t in p['targets'] if t['visibleArcs'])
    cases = [('tinyNumeric', lambda d: d['best'].__setitem__('offsetSecond', d['best']['offsetSecond']+D('1e-30'))),
             ('missingField', lambda d: d['best'].pop('offsetSecond')),
             ('missingRow', lambda d: d['allResults'].pop()),
             ('reorderedArray', lambda d: d['allResults'].reverse())]
    for name, change in cases:
        changed = copy.deepcopy(original)
        change(changed)
        try:
            leaves = list(paired(original, changed))
            detected = any(a!=b for _,a,b in leaves)
        except AssertionError:
            detected = True
        assert detected, name
        tests.append(dict(name=name, detected=True))
    example = dict(startTime='2026-07-01T10:00:00.000Z', endTime='2026-07-01T10:00:01.000Z', durationSec=D(1))
    changed = dict(example, endTime='2026-07-01T10:00:01.001Z', durationSec=D('1.001'))
    record = list(window_records(example, changed))[0]
    assert record['endDeltaSec']==D('0.001') and record['durationDeltaSec']==D('0.001')
    tests.append(dict(name='windowBoundary1ms', detected=True, measuredEndDeltaSec=record['endDeltaSec']))
    observed = [D('7000000'), D(0), D(0)]
    changed_point = [observed[0]+D('0.000000001'), D(0), D(0)]
    assert norm([b-a for a,b in zip(observed,changed_point)])==D('0.000000001')
    tests.append(dict(name='originalPoint1nm', detected=True, residualM='0.000000001'))
    for name, check in [('nonFinite', lambda:list(paired(D('NaN'),D('NaN')))),
                        ('wrongTimeType', lambda:utc(123)), ('invalidTimeOrder', lambda:list(window_records(dict(example,endTime='2026-07-01T09:00:00.000Z'),dict(example,endTime='2026-07-01T09:00:00.000Z'))))]:
        try:
            check()
        except (AssertionError, ValueError):
            tests.append(dict(name=name,detected=True))
        else:
            raise AssertionError('反例未检出：'+name)
    save('counterexamples', dict(passed=True, tests=tests))


def holdout():
    """留出点仅比较同一query bits；统计三维位置/速度RMSE、最大、nearest-rank P95。"""
    cases = []
    for interval in (1,5,10):
        paths = [OUT / ('holdout-'+label+'-'+str(interval)+'s.stdout.bin') for label in ('original','current')]
        batches = [list(csv.DictReader(path.read_bytes().decode('utf-8').splitlines())) for path in paths]
        assert all(len(rows)==8560 for rows in batches), '留出参考必须完整'
        expected, actual = batches
        for index,(row,other) in enumerate(zip(expected,actual)):
            assert row.keys()==other.keys() and int(row['row'])==int(other['row'])==index
            assert row['timeBits']==row['queryBits']==other['timeBits']==other['queryBits'], '留出query时间错位'
            assert row['observed']==other['observed']==('1' if index%(interval*10)==0 or index==8559 else '0')
            for key in row:
                if key not in ('timeBits','queryBits'):
                    assert numeric(row[key])==numeric(other[key]), '原/新留出数值差异'
        observations = [r for r in actual if r['observed']=='1']
        held = [r for r in actual if r['observed']=='0']
        stats = {}
        for name,reference_keys,actual_keys in [('positionM',['referenceX','referenceY','referenceZ'],['actualX','actualY','actualZ']),
                                                ('velocityMps',['referenceVx','referenceVy','referenceVz'],['actualVx','actualVy','actualVz'])]:
            components = [[numeric(r[b])-numeric(r[a]) for a,b in zip(reference_keys,actual_keys)] for r in held]
            values = [norm(v) for v in components]
            ordered = sorted(values)
            rank = math.ceil(D('0.95')*len(values))
            observed_residual = max(norm([numeric(r[b])-numeric(r[a]) for a,b in zip(reference_keys,actual_keys)]) for r in observations)
            stats[name]=dict(rmse=(sum(v*v for v in values)/D(len(values))).sqrt(), maximum=max(values),
                p95NearestRank=ordered[rank-1], percentileRank=rank,
                maxAbsComponents=[max(abs(v[i]) for v in components) for i in range(3)],
                observationMaxResidual=observed_residual, originalCurrentMaxDelta='0',
                worstRow=held[values.index(max(values))]['row'])
            assert observed_residual==0
        cases.append(dict(observationIntervalSec=interval, totalReferenceRows=8560, observations=len(observations),
            holdoutRows=len(held), outsideObservationRows=0, queryAndReferenceBitsEqual=True,
            originalCurrentByteEqual=paths[0].read_bytes()==paths[1].read_bytes(), originalSha256=sha(paths[0]),
            currentSha256=sha(paths[1]), stats=stats))
    save('holdout-metrics', dict(cases=cases, referenceType='完整原0.1s轨迹代理，非独立STK/实测真值',
        observedDesign='1/5/10秒观测，首末保留，其余原密集点为留出；同一参考绝对秒bits精确查询',
        queryImplementation='调用原policyFor/estimateKnotDerivatives/pointFromConstrainedSegment；public fitSamples约束原节点',
        absoluteAccuracyValidated=False, threshold='用户未给绝对误差标准；报告实测数值，不擅自设置通过线'))


def grid():
    """公开网格所有非原点输出对最近原0.1秒时刻测量，不把近邻当同刻查询。"""
    paths = [OUT / ('grid-'+label+'-run.stdout.bin') for label in ('original','current')]
    batches = [list(csv.DictReader(path.read_bytes().decode('utf-8').splitlines())) for path in paths]
    assert len(batches[0])==len(batches[1]) and len(batches[0])>=8560
    for index,(left,right) in enumerate(zip(*batches)):
        assert left==right and int(left['row'])==index, '原/当前公开网格差异'
        for key in left:
            if key not in ('timeBits','referenceTimeBits'):
                numeric(left[key])
                numeric(right[key])
    rows = batches[1]
    held = [row for row in rows if row['original']=='0']
    observed = [row for row in rows if row['original']=='1']
    assert len(observed)==857 and held
    deltas = [numeric(row['timeDeltaSec']) for row in held]
    maximum = max(abs(value) for value in deltas)
    save('public-grid-metrics', dict(originalOutputRows=len(batches[0]), currentOutputRows=len(rows),
        originalObservationRows=len(observed), nonOriginalRows=len(held), originalCurrentByteEqual=paths[0].read_bytes()==paths[1].read_bytes(),
        originalSha256=sha(paths[0]),currentSha256=sha(paths[1]), expectedReferenceRows=8560,
        additionalOutputRows=len(rows)-8560, nonOriginalTimeMaxAbsSec=maximum,
        nonOriginalTimeRmseSec=(sum(v*v for v in deltas)/D(len(deltas))).sqrt(),
        firstNonOriginalTimeDeltaSec=deltas[0], lastNonOriginalTimeDeltaSec=deltas[-1],
        maxObservationTimeDeltaSec=max(abs(numeric(row['timeDeltaSec'])) for row in observed),
        maxOriginalCurrentTimeDeltaSec='0', maxOriginalCurrentStateDelta='0',
        worstNonOriginalRow=held[next(i for i,v in enumerate(deltas) if abs(v)==maximum)],
        note='所有原公开输出被保留；最近参考格点仅用于时间漂移诊断，不对错位查询的位置/速度做插值误差宣称'))


def canonical_windows():
    """各偏移实际载荷/目标可见弧与单星/下传/共同观测弧计数，汇总副本不重复算业务场景。"""
    ballistic, space_cases = [], []
    for file in sorted((RUN/'ballistic-actual').glob('visibility-*/result.json')):
        result = load(file)
        scenario = dict(scenario=file.parent.name, bestOffsetSec=result['best']['offsetSecond'] if result.get('best') else None, offsets=[])
        for offset in result['allResults']:
            arcs = []
            for satellite in offset['visibleSatellites']:
                for payload in satellite['payloadResults']:
                    for target in payload['targets']:
                        for arc in target['visibleArcs']:
                            arcs.append(dict(satelliteId=satellite['satelliteId'], payloadId=payload['payloadId'], targetId=target['targetId'],
                                arcIndex=arc['arcIndex'], startTime=arc['startTime'],endTime=arc['endTime'],
                                startJ2000Sec=arc['startJ2000Sec'],endJ2000Sec=arc['endJ2000Sec'],durationSec=arc['durationSeconds'],
                                pointCount=arc['pointCount'], actualOutputTrajectoryPoints=len(arc.get('trajectoryPoints',[])),
                                startDeltaSec='0',endDeltaSec='0',durationDeltaSec='0'))
            scenario['offsets'].append(dict(offsetSec=offset['offsetSecond'], windows=len(arcs), arcs=arcs,
                visibleDurationSec=offset['visibleDurationSec'], offsetDeltaSec='0'))
        ballistic.append(scenario)
    for group in ('space-actual','complete21-current-actual'):
        for name in ('visibility.json','downlink.json','common-sync.json','common-async.json'):
            file=RUN/group/name
            if not file.exists():
                continue
            result=load(file)
            arcs=[]
            if name=='downlink.json':
                for pair in result['satelliteStationWindows']:
                    arcs += [dict(satelliteId=pair['satelliteId'],stationId=pair['stationId'],**arc) for arc in pair['windows']]
            else:
                for target in result['results']:
                    arcs += [dict(targetId=target['targetId'],observerSatelliteIds=target['observerSatelliteIds'],
                        startTime=arc['startTime'],endTime=arc['endTime'],durationSec=arc['durationSec']) for arc in target['arcs']]
            for arc in arcs:
                arc.update(startDeltaSec='0',endDeltaSec='0',durationDeltaSec='0')
            space_cases.append(dict(group=group,file=name,windows=len(arcs),arcs=arcs))
    save('scenario-windows',dict(ballistic=ballistic,space=space_cases,
        note='弹道只计allResults的卫星/载荷/目标visibleArcs，各offset独立；空间只计实际arcs或satelliteStationWindows，覆盖/光照/持久化副本详见windows.json'))


if __name__ == '__main__':
    measure()
    observations()
    retime()
    coordinates()
    counterexamples()
    holdout()
    grid()
    canonical_windows()
    print('PASS complete Decimal/CSV metrics, 102 residuals, retime, coordinates and counterexamples')
