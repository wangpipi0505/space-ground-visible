"""核对原格式、JSON与真实DTO，以及本轮不修改产品的边界；不重跑或冒称数值验收。"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE_OUT = ROOT / 'verification/A12'
OUT = BASE_OUT / os.environ.get('A12_ROUND', '')
RUN = ROOT / 'verification/runtime/A12'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')


def save(name, value):
    (OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def execute(name, args):
    if (OUT / (name + '-command.json')).exists():
        raise FileExistsError('验证记录已存在，请设置新的A12_ROUND，保留原命令证据')
    env = dict(os.environ, JAVA_HOME=str(JAVA))
    result = subprocess.run(list(map(str, args)), cwd=ROOT, env=env, capture_output=True)
    (OUT / (name + '-stdout.txt')).write_bytes(result.stdout)
    (OUT / (name + '-stderr.txt')).write_bytes(result.stderr)
    save(name + '-command', dict(args=list(map(str, args)), exitCode=result.returncode,
         stdoutSha256=hashlib.sha256(result.stdout).hexdigest(), stderrSha256=hashlib.sha256(result.stderr).hexdigest()))
    if result.returncode:
        print(result.stdout.decode('utf-8', errors='replace'))
        print(result.stderr.decode('utf-8', errors='replace'))
    result.check_returncode()


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    OUT.mkdir(parents=True, exist_ok=True)
    RUN.mkdir(parents=True, exist_ok=True)
    schema = json.loads((ROOT / 'docs/openapi/openapi.json').read_text(encoding='utf-8-sig'))
    records, examples, routes = [], [], []
    originals = json.loads((BASE_OUT / 'original-documents.json').read_text(encoding='utf-8'))
    standard = {'payloadType', 'minDistanceKm', 'maxDistanceKm', 'azimuthMinDeg', 'azimuthMaxDeg',
                'elevationMinDeg', 'elevationMaxDeg', 'angleRateDegPerSec', 'sunAvoidHalfAngleDeg', 'startupAngleRateDegPerSec'}
    for item in originals:
        original = Path(item['path'])
        assert hashlib.sha256(original.read_bytes()).hexdigest() == item['sha256'], original
        path = ROOT / 'docs' / original.name
        source, text = original.read_text(encoding='utf-8-sig'), path.read_text(encoding='utf-8')
        # 保留原二、三级标题顺序，表格与JSON代码块继续就地说明字段与输入输出。
        headings = re.findall(r'^#{2,3} .+$', text, re.M)
        assert headings == re.findall(r'^#{2,3} .+$', source, re.M), original.name
        blocks = re.findall(r'```json\s*\n([\s\S]*?)\n```', text)
        kinds = []
        for index, block in enumerate(blocks, 1):
            value = json.loads(block)
            kind = None
            if 'satellites' in value:
                kind = 'visibility'
                for satellite in value['satellites']:
                    for payload in satellite.get('payloads', []):
                        assert standard <= payload.keys(), (original.name, index, payload)
            elif 'package102' in value:
                kind = 'fit'
            elif 'launchPointX' in value:
                kind = 'simulate'
            elif 'trajectoryCsvPath' in value:
                kind = 'retime'
            elif 'files' in value:
                kind = 'fit-result'
            if kind:
                if kind in ('fit', 'simulate', 'retime'):
                    assert 'outputDir' not in value, '网页样例使用受管理输出目录'
                examples.append(dict(document=original.name, block=index, kind=kind, value=value))
                kinds.append(kind)
        for method, route in re.findall(r'\b(GET|POST|PUT|DELETE)\s+(/api/[^\s`]+)', text):
            assert route in schema['paths'] and method.lower() in schema['paths'][route], (method, route)
            routes.append(dict(method=method, path=route))
        for link in re.findall(r'\]\(([^)]+)\)', text):
            if not re.match(r'https?://', link):
                target = link.split('#', 1)[0]
                assert not target or (path.parent / target).exists(), (path, link)
        records.append(dict(document=path.relative_to(ROOT).as_posix(), originalSha256=item['sha256'],
                       currentSha256=hashlib.sha256(path.read_bytes()).hexdigest(), headings=headings,
                       jsonBlocks=len(blocks), boundKinds=kinds))
    save('request-examples', examples)
    # A11前后正式HTTP控制器与窗口DTO完全相同；兼容轨迹控制器只改注释，公开ABI另有A11完整证据。
    api = ROOT / 'backend/src/main/java/com/runisys/orbit/spacevisibility/api'
    protected = [*sorted(api.rglob('*.java')), ROOT / 'backend/src/main/java/com/tx/alg/api/VisibilityRequest.java']
    api_sources = []
    for path in protected:
        before = subprocess.check_output(['git', 'show', 'b8a0e3f:' + path.relative_to(ROOT).as_posix()], cwd=ROOT)
        assert before.replace(b'\r\n', b'\n') == path.read_bytes().replace(b'\r\n', b'\n'), path
        api_sources.append(dict(path=path.relative_to(ROOT).as_posix(),
                                sha256=hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()))
    save('protected-api-sources', dict(baseline='b8a0e3f', normalizedLineEndings=True, files=api_sources))
    execute('product-no-change', ['git', 'diff', '--exit-code', 'HEAD', '--', 'backend', 'frontend', 'docs/openapi/openapi.json'])
    classes = ROOT / 'verification/runtime/A11/reviewed/candidate/backend/target/classes'
    dependencies = (ROOT / 'verification/runtime/A11/reviewed/ballistic-runner/classpath.txt').read_text().strip()
    classpath = str(classes) + os.pathsep + dependencies
    execute('dto-compile', [JAVA / 'bin/javac.exe', '-encoding', 'UTF-8', '-cp', classpath, '-d', RUN, BASE_OUT / 'DocumentDtoProbe.java'])
    execute('dto-run', [JAVA / 'bin/java.exe', '-Dfile.encoding=UTF-8', '-cp', str(RUN) + os.pathsep + classpath,
                       'DocumentDtoProbe', OUT / 'request-examples.json', RUN / OUT.name])
    save('validation', dict(passed=True, records=records, routes=routes, dtoExamples=len(examples),
         originalApiFilesByteEqual=len(protected), productChanged=False,
         distinctOperations=len({(item['method'], item['path']) for item in routes}),
         fullFitExamples=sum(item['kind'] == 'fit' for item in examples),
         scope='文档格式/JSON/真实DTO与标准载荷校验；2个拟合示例完整计算写入隔离目录；未运行HTTP、窗口搜索、STK或性能测试'))
    print('PASS', len(records), '文档，', len(examples), 'DTO对象，', len(routes), '路由引用，', len(protected), 'API源文件无变化')


if __name__ == '__main__':
    main()
