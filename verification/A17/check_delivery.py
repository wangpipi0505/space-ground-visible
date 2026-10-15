"""A17 最终核验：计算主体、接口合同、独立对照及包源字节的可复核绑定。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
from run_validation import ROOT, OUT, RUN, save


def java_body(text):
    """去除说明、包和导入用于机械迁移对比；字符串字面量原样保留。"""
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
    text = tokens.sub(lambda m: '' if m[0].startswith(('/*', '//')) else m[0], text)
    text = re.sub(r'(?m)^\s*(?:package|import) [^;]+;', '', text)
    return re.sub(r'\s+', ' ', text).strip()


before = RUN / 'before/backend/src/main/java/com/tx/alg'
after = ROOT / 'backend/src/main/java/com/runisys/orbit/spacevisibility/domain/ballistic'
math = []
for path in sorted(after.glob('*.java')):
    source = before / ('trajectory/' + path.name if path.stem == 'TargetTrajectoryFitter' else path.name)
    left = source.read_text(encoding='utf-8')
    right = path.read_text(encoding='utf-8')
    if path.stem == 'OrbitCalculator':
        start = left.index('    public static LaunchSearchResultV2 searchLaunchOffsetsV2(List<BallisticTargetInput> targets,')
        end = left.index('    /**\n     * 每个目标的源加载边界', start)
        left = left[:start] + left[end:]
        start = left.index('    /**\n     * 按位置字符串解析弹道文件：')
        end = left.index('    /**\n     * 从UTF-8字节流解析弹道CSV。', start)
        left = left[:start] + left[end:]
    if path.stem == 'TargetTrajectoryFitter':
        for old, new in [('FitRequest', 'FitInput'), ('SimulationRequest', 'SimulationInput'), ('RetimeTrajectoryRequest', 'RetimeInput')]:
            left = re.sub(r'\b' + old + r'\b', new, left)
            left = left.replace('public static final class ' + new, 'public static class ' + new)
    same = java_body(left) == java_body(right)
    math.append(dict(file=path.name, unchanged=same))
assert all(x['unchanged'] for x in math), math
base = ROOT / 'backend/src/main/java/com/runisys/orbit/spacevisibility'
for part, scope in [('domain', list((base / 'domain/ballistic').glob('*.java'))),
                    ('application', list((base / 'application/ballistic').glob('BallisticVisibility*.java')))]:
    for file in scope:
        text = file.read_text(encoding='utf-8')
        assert not re.search(r'^import com\.runisys\.orbit\.spacevisibility\.api\.', text, re.M), file
        if part == 'domain':
            assert not re.search(r'^import com\.runisys\.orbit\.spacevisibility\.infrastructure\.', text, re.M), file
assert not (ROOT / 'backend/src/main/java/com/tx').exists()
assert not any('com.tx.alg' in p.read_text(encoding='utf-8') for p in (ROOT / 'backend/src/main/java').rglob('*.java'))

def strip_sources(value):
    if isinstance(value, dict):
        return {k: strip_sources(v) for k, v in value.items() if k != 'x-source'}
    if isinstance(value, list):
        return [strip_sources(x) for x in value]
    return value
old = json.loads(subprocess.run(['git', 'show', '192902f:docs/openapi/openapi.json'], cwd=ROOT, capture_output=True, check=True).stdout)
new = json.loads((ROOT / 'docs/openapi/openapi.json').read_text(encoding='utf-8'))
assert strip_sources(old) == strip_sources(new), 'OpenAPI实际合同发生变化'
serial = {}
for stage in ('before', 'after-v2'):
    serial[stage] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in (RUN / ('serialization-' + stage) / 'output').iterdir()}
assert serial['before'] == serial['after-v2'] and len(serial['before']) == 48
save('serialization-compare', dict(passed=True, files=48, sha256=serial))
unchanged = []
for path in (base / 'domain/orbit').glob('*.java'):
    old_path = RUN / 'before/backend/src/main/java' / path.relative_to(ROOT / 'backend/src/main/java')
    assert old_path.read_bytes() == path.read_bytes(), path
    unchanged.append(path.name)
originals = []
for name, directory in [('space', Path('G:/5-YG/target-visible-alg/space-target-visible-main-集成交付')),
                         ('ballistic', Path('G:/Code/Code-V3/alg/deliverables/alg-0723'))]:
    manifest = json.loads((ROOT / ('verification/T02/' + name + '-manifest.json')).read_text(encoding='utf-8-sig'))
    bad = [item['path'] for item in manifest if hashlib.sha256((directory / item['path']).read_bytes()).hexdigest() != item['sha256']]
    originals.append(dict(name=name, files=len(manifest), differences=bad))
assert all(not i['differences'] for i in originals)
save('结构与合同核验', dict(passed=True, domainBodies=math, unchangedOrbitFiles=unchanged,
     noOldProductPackage=True, noBallisticDomainOrVisibilityApplicationToApiDependency=True,
     boundaryScope='本轮领域计算与窗口应用输入/服务；既有文件预览DTO依赖及空间应用代码不在此断言范围',
     openApiOnlySourceLocationsChanged=True,
     openApiOperations=sum(k in ('get','post','put','patch','delete','head','options') for v in new['paths'].values() for k in v),
     originalDirectories=originals))
print('PASS 8算法主体、外推源码、OpenAPI合同、48序列化及697原目录保护')
