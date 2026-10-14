"""A04交付证据只读核验：仅Python标准库，仅仓库相对路径，不执行任何数值计算。

核验当前可提交字节及历史证据对应关系；不加载本机绝对输入、classes或JAR，
不写摘要、不创建目录。缺少ignored资产不会使交付检查失败，也不代表已重新计算。
"""
from pathlib import Path
from datetime import datetime
from decimal import Decimal
import argparse
import csv
import hashlib
import io
import json
import xml.etree.ElementTree as ET


def sha(path):
    """SHA以原字节计算；Windows原始双流禁止解码再编码后比较。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def inside(root, relative):
    """清单必须为仓库相对路径，不允许绝对路径、越界或ignored运行副本。"""
    path = Path(relative)
    assert not path.is_absolute() and '..' not in path.parts, '非法交付路径：' + relative
    assert not relative.startswith(('verification/runtime/', 'verification/baseline-copies/')), '不应绑定ignored资产：' + relative
    resolved = (root / path).resolve()
    assert resolved.is_relative_to(root.resolve()), '交付路径越界：' + relative
    return resolved


def check_observation_report(residuals, report):
    """原点表必须与逐点真实范数一致，非零反例不能被报告静默归零。"""
    for target in residuals['targets']:
        samples = target['samples']
        assert len(samples) == target['observations'] == target['originalRows']
        assert all(sample['isOriginal'] == '1' for sample in samples)
        values = [max(Decimal(sample[key]) for sample in samples) for key in ('positionNormM', 'velocityNormMps')]
        assert all(value.is_finite() and value >= 0 for value in values)
        row = '| '+target['targetId']+' | '+str(target['rows'])+' | '+str(target['originalRows'])+' | '+str(target['interpolationRows'])+' | '+str(target['outsideObservationRows'])+' | '+format(float(values[0]), '.9g')+' m / '+format(float(values[1]), '.9g')+' m/s |'
        assert row in report, '原点报告与指标不匹配：' + target['targetId']
    assert str(sum(len(target['samples']) for target in residuals['targets'])) + ' 个原始观测' in report


LEGACY_SOURCE = 'verification/T07/SpaceRegression.java'
LEGACY_CRLF = (15397, '22956f0df0970c5a67942906c1c3ff8e6aed7e204629794670000d2bf5a9ec15')
LEGACY_LF = (15202, '622687b8150a2795e788876c28b757558f39a351e618ba94370e625b0306ba81')


def check_legacy_source(path):
    """仅允许具名旧T07源的两个固定身份，并证明CRLF→LF转换后完全一致。

    原执行输入保持15397字节CRLF；Git交付保持15202字节LF。双向重建均需
    同时匹配固定长度和SHA，正文或其他行尾变化不能借此豁免。其他证据不调用此函数。
    """
    raw = path.read_bytes()
    identity = (len(raw), hashlib.sha256(raw).hexdigest())
    assert identity in (LEGACY_CRLF, LEGACY_LF), '交付字节不匹配：' + LEGACY_SOURCE
    lf = raw.replace(b'\r\n', b'\n')
    crlf = lf.replace(b'\n', b'\r\n')
    assert (len(lf), hashlib.sha256(lf).hexdigest()) == LEGACY_LF
    assert (len(crlf), hashlib.sha256(crlf).hexdigest()) == LEGACY_CRLF


def verify(root):
    """核验具名证据集合，返回作用域计数；不读取manifest内的runtime/绝对资产。"""
    root = root.resolve()
    out = root / 'verification/A04'
    delivery = load(out / 'candidate-delivery-manifest.json')
    assert delivery['fileCount'] == len(delivery['files'])
    assert len({item['path'] for item in delivery['files']}) == delivery['fileCount']
    # 870项快照不倒填；本次唯一修改的旧checker以新小备份绑定其原始身份。
    checker_path = 'verification/A04/delivery_check.py'
    checker_before = load(out / 'git-delivery-fix/history/before-bindings.json')['files']
    assert len(checker_before) == 1 and checker_before[0]['path'] == checker_path
    checker_before = checker_before[0]
    assert checker_before['beforePath'] == 'verification/A04/git-delivery-fix/history/delivery_check.py.before'
    for item in delivery['files']:
        path = inside(root, checker_before['beforePath'] if item['path'] == checker_path else item['path'])
        assert path.is_file(), '缺少交付证据：' + item['path']
        if item['path'] == LEGACY_SOURCE:
            assert (item['bytes'], item['sha256']) == LEGACY_CRLF
            check_legacy_source(path)
        else:
            assert path.stat().st_size == item['bytes'] and sha(path) == item['sha256'], '交付字节不匹配：' + item['path']
        if item['path'] == checker_path:
            assert (checker_before['bytes'], checker_before['sha256']) == (item['bytes'], item['sha256'])

    # 根最终交付清单绑定当前checker及所有后续证据；仅旧T07保留上述具名行尾兼容。
    final_delivery = load(out / '交付清单.json')
    final_paths = {item['path'] for item in final_delivery}
    assert len(final_paths) == len(final_delivery), '最终交付清单路径重复'
    assert {item['path'] for item in delivery['files']} <= final_paths, '最终清单缺少候选路径'
    assert {checker_before['beforePath'], 'verification/A04/git-delivery-fix/history/before-bindings.json'} <= final_paths
    for item in final_delivery:
        path = inside(root, item['path'])
        assert path.is_file(), '缺少最终交付证据：' + item['path']
        if item['path'] == LEGACY_SOURCE:
            assert (item['bytes'], item['sha256']) == LEGACY_LF
            check_legacy_source(path)
        else:
            assert path.stat().st_size == item['bytes'] and sha(path) == item['sha256'], '最终交付字节不匹配：' + item['path']

    # 历史本机清单不刷新：四个改动文件用原字节小备份匹配修复前SHA，其余只读核对。
    history = load(out / 'delivery-fix/history/before-bindings.json')
    backups = {item['path']: item for item in history['files']}
    old = load(out / 'artifact-manifest.json')
    runtime = [item for item in old['files'] if item['path'].startswith('verification/runtime/')]
    nonruntime = [item for item in old['files'] if not item['path'].startswith('verification/runtime/')]
    assert old['fileCount'] == len(old['files']) == 1426 and len(runtime) == 1206 and len(nonruntime) == 220
    for item in nonruntime:
        record = backups.get(item['path'])
        path = inside(root, record['beforePath'] if record else item['path'])
        assert path.stat().st_size == item['bytes'] and sha(path) == item['sha256'], '历史证据不匹配：' + item['path']
        if record:
            assert record['bytes'] == item['bytes'] and record['sha256'] == item['sha256']
    assert len(load(out / 'input-bindings.json')['inputs']) == 927

    source = load(out / 'current-source-manifest.json')
    for item in source['files']:
        assert sha(inside(root, item['path'])) == item['sha256'], '当前源码/必要输入不匹配：' + item['path']
    build = load(out / 'new-build-bindings.json')
    assert build['sourceManifestSha256'] == sha(out / 'current-source-manifest.json')
    assert build['commandSha256'] == sha(out / 'precision-maven-command.json')
    # build记录里的JAR/classes为本机历史绑定；本入口只确认记录未变，不读取不存在的资产。
    commands = sorted(out.glob('*-command.json'))
    assert len(commands) == 47
    for path in commands:
        command = load(path)
        start, end = [datetime.fromisoformat(command[key]) for key in ('startedUtc', 'endedUtc')]
        assert start.utcoffset().total_seconds() == end.utcoffset().total_seconds() == 0 and end >= start
        assert command['argv'] and command['cwd']
        assert command['exitCode'] == (1 if path.name == 'measurement-r1-command.json' else 0), path.name
        for stream in ('stdout', 'stderr'):
            file = command[stream + 'File']
            assert Path(file).name == file
            assert sha(out / file) == command[stream + 'Sha256'], path.name + ':' + stream

    tests = load(out / 'test-summary.json')
    selected = load(out / 'selected-tests.json')['classes']
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    actual_reports = sorted((out / 'surefire-reports').glob('TEST-*.xml'))
    assert len(actual_reports) == len(tests['reports']) == len(selected) == 23
    assert set(selected) == {item['file'].removeprefix('TEST-').removesuffix('.xml').split('.')[-1] for item in tests['reports']}
    assert '-Dtest=' + ','.join(selected) in load(out / 'precision-maven-command.json')['argv']
    for item in tests['reports']:
        path = out / 'surefire-reports' / item['file']
        assert sha(path) == item['sha256']
        node = ET.parse(path).getroot()
        assert [case.attrib['name'] for case in node.findall('testcase')] == item['cases']
        for key in totals:
            assert int(node.attrib[key]) == item[key]
            totals[key] += item[key]
    assert totals == tests['totals'] == dict(tests=77, failures=0, errors=0, skipped=0)

    numerical = load(out / 'numeric-summary.json')
    assert numerical['passed'] and [(item['name'], item['actualFiles']) for item in numerical['comparisons']] == [('ballistic15', 15), ('space9', 9), ('spaceComplete21', 21)]
    differences = load(out / 'complete-differences.json')['files']
    for item in numerical['comparisons']:
        batch = [record for record in differences if record['group'] == item['name']]
        assert len(batch) == item['actualFiles'] == item['expectedFiles'] and not item['differences']
        for record in batch:
            # JSON按原驱动的既有路径/审计时刻规范化比较，原始SHA可不同；CSV/报告必须字节相等。
            if record['file'].endswith('.json'):
                assert not record['differences'] and Decimal(record['maxAbsNumericDelta']) == 0
            else:
                assert record['byteEqual'] and record['expectedSha256'] == record['actualSha256']
    # 完整产物保存在ignored runtime，本入口核对已交付完整统计，不伪称再次比较原产物。
    held = load(out / 'holdout-metrics.json')
    assert held['absoluteAccuracyValidated'] is False
    for case in held['cases']:
        interval = str(case['observationIntervalSec'])
        original, current = [out / ('holdout-' + label + '-' + interval + 's.stdout.bin') for label in ('original', 'current')]
        assert original.read_bytes() == current.read_bytes()
        assert sha(original) == case['originalSha256'] == case['currentSha256']
        rows = list(csv.DictReader(io.StringIO(current.read_bytes().decode('utf-8-sig'))))
        assert len(rows) == case['totalReferenceRows'] == 8560
        assert all(int(row['row']) == i and row['queryBits'] == row['timeBits'] for i, row in enumerate(rows))
        assert sum(row['observed'] == '1' for row in rows) == case['observations']
        assert sum(row['observed'] == '0' for row in rows) == case['holdoutRows']
        assert case['outsideObservationRows'] == 0 and case['queryAndReferenceBitsEqual'] and case['originalCurrentByteEqual']
    grid = load(out / 'public-grid-metrics.json')
    original, current = [out / ('grid-' + label + '-run.stdout.bin') for label in ('original', 'current')]
    assert original.read_bytes() == current.read_bytes()
    assert sha(original) == grid['originalSha256'] == grid['currentSha256']
    assert len(current.read_bytes().splitlines()) - 1 == grid['originalOutputRows'] == grid['currentOutputRows'] == 9373
    assert grid['originalObservationRows'] + grid['nonOriginalRows'] == 9373
    report_raw = (out / '精度测试报告.md').read_bytes()
    assert not report_raw.startswith(b'\xef\xbb\xbf') and b'\r' not in report_raw
    report = report_raw.decode('utf-8')
    check_observation_report(load(out / 'package102-residuals.json'), report)
    assert '在空的新 A04 运行目录' not in report and 'delivery_check.py' in report
    assert build['jarSha256'] in report and 'protected-after.json' in report
    for case in held['cases']:
        p, v = case['stats']['positionM'], case['stats']['velocityMps']
        row = '| '+str(case['observationIntervalSec'])+' | '+str(case['observations'])+' | '+str(case['holdoutRows'])+' | '+' | '.join(format(float(value), '.9g') for value in (p['rmse'], p['maximum'], p['p95NearestRank'], v['rmse'], v['maximum'], v['p95NearestRank']))+' |'
        assert row in report, '留出报告与指标不匹配'
    return dict(passed=True, candidateFiles=delivery['fileCount'], finalDeliveryFiles=len(final_delivery), currentSourceInputs=len(source['files']),
                historicalNonruntimeFiles=len(nonruntime), historicalRuntimeFilesNotRead=len(runtime),
                historicalInputBindingsNotDereferenced=927, historicalCommands=len(commands), originalStreams=94,
                junitClasses=23, junit=totals, holdoutCases=len(held['cases']), gridRows=9373,
                scope='只读交付证据核验；没有执行精度计算、Java/Maven、JAR/classes检查或原产物双跑；外部物理真值未测')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='A04仓库交付证据只读核验，无runtime亦可运行')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2], help='已有仓库/最小交付证据树根；不会创建或写入')
    print(json.dumps(verify(parser.parse_args().root), ensure_ascii=False, indent=2))
