"""A18 一次性逐类迁移；只更改Java归属、引用和必要访问权限，数值主体保持。"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'backend/src/main/java'
BASE = 'com.runisys.orbit.spacevisibility'
OUT = ROOT / 'verification/A18'
assert (ROOT / 'verification/runtime/A18/before/backend/src/main/java').is_dir()
assert not (OUT / 'package-map.json').exists(), '迁移只执行一次，禁止覆盖映射基线'

visibility = {'VisibilityArc', 'VisibilityObserver', 'VisibilityTarget', 'VisibilityPairResult',
    'VisibilityPairStatistics', 'VisibilityObservationResult', 'VisibilityConstraintPlanner',
    'ObservationSampleEvaluator', 'OrbitPositionCache'}
space_orbit = {'KeplerOrbitStateProvider', 'TleOrbitStateProvider', 'SpaceOrbitStateProvider',
    'SpaceTimeFrames', 'OrekitDataInitializer'}
space_illumination = {'SolarGeometry', 'IlluminationConstraint', 'IlluminationState'}
space_geometry = {'GroundStationCoordinate', 'GroundStationGeometry', 'TemeEarthFixedFrames'}
space_visibility = {'CombinationConstraint', 'CombinationWindow', 'CombinationWindowCalculator',
    'PayloadVisibilityEvaluator', 'VisibilitySample'}


def destination(package, name):
    if package == BASE + '.api':
        if name == 'SpaceApiModels':
            return BASE + '.application.space.contract', 'SpacePlanningModels'
        return BASE + ('.api.common' if name in {'ApiCorsConfiguration', 'ApiExceptionHandler'} else '.api.space'), name
    if package == BASE + '.api.ballistic' and name == 'BallisticFileModels':
        return BASE + '.application.ballistic.contract', name
    if package == BASE + '.application':
        if name in visibility:
            return BASE + '.domain.space.visibility', name
        if name in {'DownlinkPair', 'DownlinkPairResult'}:
            return BASE + '.domain.space.downlink', name
        return BASE + '.application.space', name
    if package == BASE + '.domain.ballistic':
        return package + ('.trajectory' if name == 'TargetTrajectoryFitter' else '.visibility'), name
    if package == BASE + '.domain.downlink':
        return BASE + '.domain.space.downlink', name
    if package == BASE + '.domain.orbit':
        if name in space_orbit:
            return BASE + '.domain.space.orbit', name
        if name in {'BallisticOrbitPropagation', 'BallisticCartesianPropagation'}:
            return BASE + '.domain.ballistic.propagation', name
    if package == BASE + '.domain.geometry' and name in space_geometry:
        return BASE + '.domain.space.geometry', name
    if name in space_illumination and package == BASE + '.domain.payload':
        return BASE + '.domain.space.illumination', name
    if name in space_visibility and package.startswith(BASE + '.domain.'):
        return BASE + '.domain.space.visibility', name
    if package.startswith(BASE + '.infrastructure.persistence'):
        return package.replace('.infrastructure.persistence', '.infrastructure.space.persistence'), name
    if package == BASE + '.infrastructure.config':
        return BASE + '.infrastructure.space.persistence', name
    if package.startswith(BASE + '.infrastructure.files'):
        return package.replace('.infrastructure.files', '.infrastructure.ballistic.files'), name
    if package.startswith(BASE + '.infrastructure.web'):
        return package.replace('.infrastructure.web', '.infrastructure.common.web'), name
    return package, name


records = []
for path in sorted(SOURCE.rglob('*.java')):
    old = path.read_text(encoding='utf-8')
    package = re.search(r'^package ([^;]+);', old, re.M)[1]
    target, name = destination(package, path.stem)
    records.append(dict(old=package + '.' + path.stem, new=target + '.' + name,
        oldPath=path.relative_to(SOURCE).as_posix(), newPath=target.replace('.', '/') + '/' + name + '.java'))
mapping = {x['old']: x['new'] for x in records}
by_package = {}
for record in records:
    by_package.setdefault(record['old'].rsplit('.', 1)[0], []).append(record)


def rewrite(text, old_package, new_package):
    """先补原同包及通配导入中的依赖，再更新完整类名，保留字面量里的业务内容。"""
    original = text
    used = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*', '', text)
    imports = set()
    sources = [old_package] + re.findall(r'^import ([\w.]+)\.\*;', original, re.M)
    for package in sources:
        for item in by_package.get(package, []):
            name = item['old'].rsplit('.', 1)[1]
            target_package = item['new'].rsplit('.', 1)[0]
            if target_package != new_package and re.search(r'\b' + name + r'\b', used):
                imports.add(item['new'])
    for old, new in sorted(mapping.items(), key=lambda x: -len(x[0])):
        text = text.replace(old, new)
    # 此次唯一外部类改名；契约字段与嵌套类型均保留，不建立重复DTO。
    text = re.sub(r'\bSpaceApiModels\b', 'SpacePlanningModels', text)
    text = re.sub(r'^package [^;]+;', 'package ' + new_package + ';', text, count=1, flags=re.M)
    for package, items in by_package.items():
        if all(x['new'].rsplit('.', 1)[0] != package for x in items):
            text = text.replace('import ' + package + '.*;\n', '')
    # @MapperScan按包字符串发现接口，须与逐类迁移同步。
    text = text.replace(BASE + '.infrastructure.persistence.mapper', BASE + '.infrastructure.space.persistence.mapper')
    present = set(re.findall(r'^import ([\w.]+);', text, re.M))
    additions = sorted(imports - present)
    if additions:
        match = re.search(r'^package [^;]+;', text, re.M)
        text = text[:match.end()] + '\n\n' + '\n'.join('import ' + x + ';' for x in additions) + text[match.end():]
    return text


main_changes = []
for record in records:
    path = SOURCE / record['oldPath']
    original = path.read_text(encoding='utf-8')
    package, name = record['new'].rsplit('.', 1)
    text = rewrite(original, record['old'].rsplit('.', 1)[0], package)
    if name == 'ObservationSampleEvaluator':
        text = text.replace('final class ObservationSampleEvaluator', 'public final class ObservationSampleEvaluator')
        text = re.sub(r'(?m)^    VisibilitySample evaluate\(', '    public VisibilitySample evaluate(', text)
    if name == 'OrbitPositionCache':
        text = text.replace('final class OrbitPositionCache', 'public final class OrbitPositionCache')
        text = text.replace('    OrbitPositionCache(int capacity)', '    public OrbitPositionCache(int capacity)')
        text = text.replace('    synchronized Vector3 positionAt(', '    public synchronized Vector3 positionAt(')
    if name == 'SpacePlanningModels':
        text = text.replace('空间HTTP请求/响应模型及JavaBean访问器集合；JSON名称与旧合同一致，不因注释整理改字段。',
            '空间用例输入/输出契约及JavaBean访问器集合；由HTTP直接绑定，统一维护字段与序列化口径。')
    if name == 'BallisticFileModels':
        text = text.replace('浏览器文件辅助接口的数据结构；原计算接口继续使用原 DTO，不增加文件包装层。',
            '弹道文件用例输入输出契约；HTTP与存储适配器复用字段，计算结果不增加文件包装层。')
    target = SOURCE / record['newPath']
    if text != original or target != path:
        main_changes.append((path, target, text))
for path, target, text in main_changes:
    if path != target:
        path.unlink()
for path, target, text in main_changes:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8', newline='\n')

tests = ROOT / 'backend/src/test/java'
test_changes = []
for path in sorted(tests.rglob('*.java')):
    original = path.read_text(encoding='utf-8')
    old_package = re.search(r'^package ([^;]+);', original, re.M)[1]
    owners = [x for x in records if path.stem.startswith(x['old'].rsplit('.', 1)[1])]
    owner = max(owners, key=lambda x: len(x['old'].rsplit('.', 1)[1])) if owners else None
    if owner and owner['old'].rsplit('.', 1)[0] == old_package:
        package = owner['new'].rsplit('.', 1)[0]
    elif old_package == BASE + '.application':
        package = BASE + '.application.space'
    elif old_package == BASE + '.api':
        package = BASE + ('.api.ballistic' if path.stem.startswith('Ballistic') else '.api.space')
    elif old_package == BASE + '.domain.ballistic':
        package = BASE + ('.domain.ballistic.trajectory' if path.stem.startswith('TargetTrajectory') else '.domain.ballistic.visibility')
    elif old_package.startswith(BASE + '.infrastructure.'):
        package, _ = destination(old_package, path.stem)
    else:
        package = old_package
    text = rewrite(original, old_package, package)
    target = tests / package.replace('.', '/') / path.name
    if text != original or target != path:
        test_changes.append((path, target, text))
for path, target, text in test_changes:
    if target != path:
        path.unlink()
for path, target, text in test_changes:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8', newline='\n')

(OUT / 'package-map.json').write_text(json.dumps(records, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
print('迁移主代码', len(main_changes), '测试', len(test_changes), '逐类映射', len(records))
