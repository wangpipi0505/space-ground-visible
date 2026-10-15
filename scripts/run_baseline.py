"""T04 可重复业务基准。标准库调度原 Java 入口，保留 DTO、全部偏移和 CSV 数值。

capture 生成输出；compare 严格比较两个输出目录；mutations 检验差异检测器。
--backend 可指定迁入后的 Maven 后端，沿用相同 Java runner，不替换算法。
路径仅将工作根规范化为 <WORKSPACE>；文件生成时间戳由 Java 入口规范化。
"""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from decimal import Decimal

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')


def run(command, cwd, log):
    """保存命令、标准输出及退出码；任一步失败立即终止，不能用旧结果宣称通过。"""
    env = os.environ.copy()
    env['JAVA_HOME'] = str(JAVA_HOME)
    env['PATH'] = str(JAVA_HOME / 'bin') + os.pathsep + env['PATH']
    with log.open('w', encoding='utf-8') as stream:
        stream.write(json.dumps(command, ensure_ascii=False) + '\n')
        stream.flush()
        outcome = subprocess.run(command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT)
        stream.write('\nexit=' + str(outcome.returncode) + '\n')
    if outcome.returncode:
        raise RuntimeError('执行失败，详见 ' + str(log))


def capture(backend, output, runtime=None, evidence=None):
    """复制原真实样例并冻结显式案例条件，执行三模型、点输出开关以及拟合/模拟/重定时。"""
    evidence = evidence or ROOT / 'verification/T04'
    runtime = runtime or ROOT / 'verification/runtime/T04'
    reference = ROOT / 'test-data/reference/ballistic'
    for directory in (evidence, runtime, reference, output):
        directory.mkdir(parents=True, exist_ok=True)
    data = ROOT / 'verification/baseline-copies/ballistic/data'
    for name in ('trajectory-0.1s-standard.csv',):
        shutil.copy2(data / name, reference / name)
    for name in ('target_trajectory_fit_package102_default.json', 'target_trajectory_simulate_default.json',
                 'orbit_visibility_multi_payload_default.json', '仿真弹道.csv'):
        shutil.copy2(data / 'examples' / name, reference / name)
    cp_file = runtime / 'classpath.txt'
    mvn = shutil.which('mvn.cmd') or shutil.which('mvn')
    run([mvn, 'dependency:build-classpath', '-Dmdep.outputFile=' + str(cp_file)], backend,
        evidence / 'runner-classpath.log')
    classpath = str(backend / 'target/classes') + os.pathsep + cp_file.read_text().strip()
    # A17 之后产品不再提供旧包。原版仍编译冻结探针，新版编译仅调整 Java 绑定的同条件探针。
    unified = (backend / 'target/classes/com/runisys/orbit/spacevisibility/domain/ballistic/OrbitCalculator.class').exists()
    runner_source = ROOT / ('verification/A17/BallisticBaseline.java' if unified else 'verification/T04/BallisticBaseline.java')
    runner_class = ('com.runisys.orbit.spacevisibility.api.ballistic.' if unified else 'com.tx.alg.api.') + 'BallisticBaseline'
    run([str(JAVA_HOME / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', classpath,
         '-d', str(runtime), str(runner_source)], ROOT, evidence / 'runner-compile.log')
    cases = []
    base = json.loads((reference / 'orbit_visibility_multi_payload_default.json').read_text(encoding='utf-8-sig'))
    # 保留真实轨迹全部原始点和 1s 可见性采样，仅缩小偏移个数，避免巨大 JSON。
    base.update(startOffsetSec=1200, stepSizeSec=10, numCalculations=3, targetSampleStepSec=1.0)
    trajectory = str(reference / 'trajectory-0.1s-standard.csv')
    base['ballistics'] = [dict(csvPath=trajectory, targetId='TGT-001', targetName='真实弹道首历元'),
                          dict(csvPath=trajectory, targetId='TGT-002', targetName='真实弹道独立发射历元',
                               baseLaunchUTC='2026/07/01 07:57:10.000')]
    # 各卫星两个原 DTO 的对地监视载荷，身份不同，累计与排序必须同时保留。
    for satellite in base['satellites']:
        satellite['payloads'] = [dict(payloadId='PAYLOAD-' + str(i), payloadName='基准载荷' + str(i),
                                       payloadType='对地监视',
                                       maxDetectRangeKm=0, sunExclusionAngleDeg=0) for i in (1, 2)]
    for model in ('TWO_BODY', 'J2_SECULAR', 'J2_NUMERICAL'):
        for points in (0, 1):
            request = copy.deepcopy(base)
            request.update(orbitModel=model, trajectoryPointOutput=points)
            cases.append(('visibility-' + model.lower() + '-points' + str(points), 'visibility-legacy', request))
    cases.append(('visibility-http-ground', 'visibility', copy.deepcopy(base)))
    for operation, filename in (('fit', 'target_trajectory_fit_package102_default.json'),
                                ('simulate', 'target_trajectory_simulate_default.json')):
        cases.append((operation, operation, json.loads((reference / filename).read_text(encoding='utf-8-sig'))))
    cases.append(('retime', 'retime', dict(taskName='T04-retime', targetId='RETIMED-TGT',
                 trajectoryCsvPath=str(runtime / 'retime-source.csv'), launchTimeUtc='2026-07-02 07:57:00.000')))
    manifest = []
    for name, operation, request in cases:
        if operation == 'retime':
            # 原真实 CSV 的 ECEF_X(m) 列名不被原 retime 接受，使用原模拟 API 的规范 CSV 原字节。
            shutil.copy2(output / 'simulate/trajectory-0.csv', runtime / 'retime-source.csv')
        request_file = runtime / (name + '-request.json')
        request_file.write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding='utf-8')
        destination = output / name
        run([str(JAVA_HOME / 'bin/java.exe'), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
             '-cp', str(runtime) + os.pathsep + classpath, runner_class, operation,
             str(request_file), str(destination)], ROOT, evidence / (name + '.log'))
        # 仅绝对工作根替换；数字、数组顺序、窗口和所有偏移不改。
        for file in destination.glob('*.json'):
            text = file.read_text(encoding='utf-8').replace(str(ROOT).replace('\\', '/'), '<WORKSPACE>')
            text = text.replace(str(ROOT).replace('\\', '\\\\'), '<WORKSPACE>')
            file.write_text(text, encoding='utf-8')
        manifest.append(dict(name=name, operation=operation, request=request))
        print(name + ' 已生成', flush=True)
    # retime的真实输入已按本次runtime执行；条件快照保留T04原逻辑路径，避免环境差异冒充业务差异。
    for case in manifest:
        if case['operation'] == 'retime':
            case['request']['trajectoryCsvPath'] = str(ROOT / 'verification/runtime/T04/retime-source.csv')
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2)
    encoded = encoded.replace(str(ROOT).replace('\\', '\\\\'), '<WORKSPACE>')
    (output / 'conditions.json').write_text(encoded, encoding='utf-8')


def differences(expected, actual, path='$'):
    """递归严格比较结构、字段、数组顺序和十进制数，不设置放宽容差。"""
    if type(expected) != type(actual):
        return [path + ': 类型不同']
    if isinstance(expected, dict):
        found = [path + ': 字段集合不同'] if expected.keys() != actual.keys() else []
        for key in expected.keys() & actual.keys():
            found += differences(expected[key], actual[key], path + '.' + key)
        return found
    if isinstance(expected, list):
        found = [path + ': 数组长度不同'] if len(expected) != len(actual) else []
        for index, (left, right) in enumerate(zip(expected, actual)):
            found += differences(left, right, path + '[' + str(index) + ']')
        return found
    return [] if expected == actual else [path + ': 值不同 ' + str(expected) + ' / ' + str(actual)]


def load_json(path):
    """用 Decimal 保留 JSON 数字实际精度，避免浮点读取掩盖末位差异。"""
    return json.loads(path.read_text(encoding='utf-8-sig'), parse_float=Decimal, parse_int=Decimal)


def compare(expected, actual):
    """比较完整文件集合；JSON 按结构，CSV 按字节，不跳过 best 或 allResults。"""
    left = {p.relative_to(expected) for p in expected.rglob('*') if p.is_file()}
    right = {p.relative_to(actual) for p in actual.rglob('*') if p.is_file()}
    found = ['文件集合不同'] if left != right else []
    for relative in sorted(left & right):
        if relative.suffix == '.json':
            found += [str(relative) + ': ' + d for d in differences(load_json(expected / relative), load_json(actual / relative))]
        elif (expected / relative).read_bytes() != (actual / relative).read_bytes():
            found.append(str(relative) + ': 文件字节不同')
    return found


def mutations(baseline):
    """对实际非空结果修改窗口、删除弧段、修改 best 和轨迹坐标，证明比较器能检出。"""
    original = load_json(baseline / 'visibility-two_body-points1/result.json')
    report = []
    for name in ('窗口边界', '删除弧段', '最佳偏移', '轨迹坐标'):
        changed = copy.deepcopy(original)
        if name == '最佳偏移':
            changed['best']['offsetSecond'] += 1
        else:
            target = next(t for s in changed['allResults'][0]['visibleSatellites']
                          for p in s['payloadResults'] for t in p['targets'] if t['visibleArcs'])
            if name == '删除弧段':
                target['visibleArcs'].pop()
            elif name == '窗口边界':
                target['visibleArcs'][0]['endJ2000Sec'] += Decimal('0.001')
            else:
                target['visibleArcs'][0]['trajectoryPoints'][0]['longitudeDeg'] += Decimal('0.000001')
        found = differences(original, changed)
        if not found:
            raise AssertionError('未检出变异：' + name)
        report.append(dict(mutation=name, detected=True, differences=found))
    return report


def main():
    # PowerShell 重定向时仍固定 UTF-8，中文差异证据不能受系统 GBK 代码页影响。
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['capture', 'compare', 'mutations'])
    parser.add_argument('--backend', type=Path, default=ROOT / 'verification/baseline-copies/ballistic/source')
    parser.add_argument('--output', type=Path, default=ROOT / 'test-data/baseline/ballistic')
    parser.add_argument('--actual', type=Path)
    parser.add_argument('--runtime', type=Path, help='本次运行的临时请求与编译目录；默认仍为T04')
    parser.add_argument('--evidence', type=Path, help='本次命令日志目录；默认仍为T04')
    args = parser.parse_args()
    if args.action == 'capture':
        capture(args.backend.resolve(), args.output.resolve(),
                args.runtime.resolve() if args.runtime else None,
                args.evidence.resolve() if args.evidence else None)
    elif args.action == 'compare':
        if args.actual is None:
            parser.error('compare 需要 --actual')
        found = compare(args.output, args.actual)
        print(json.dumps(dict(passed=not found, differences=found), ensure_ascii=False, indent=2))
        return bool(found)
    else:
        print(json.dumps(mutations(args.output), ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
