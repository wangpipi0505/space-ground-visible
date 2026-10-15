"""验证逐类迁移不改变Java主体，公共依赖闭合且领域层没有上层依赖。"""
import hashlib
import json
import re
from pathlib import Path
from run_validation import ROOT, OUT, RUN, save

mapping = json.loads((OUT / 'package-map.json').read_text(encoding='utf-8'))
source = ROOT / 'backend/src/main/java'


def body(text):
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
    text = tokens.sub(lambda m: '' if m[0].startswith(('/*', '//')) else m[0], text)
    text = re.sub(r'(?m)^\s*(?:package|import) [^;]+;', '', text)
    return re.sub(r'\s+', ' ', text).strip()


records = []
for item in mapping:
    left = (RUN / 'before/backend/src/main/java' / item['oldPath']).read_text(encoding='utf-8')
    right = (source / item['newPath']).read_text(encoding='utf-8')
    for entry in sorted(mapping, key=lambda x: -len(x['old'])):
        left = left.replace(entry['old'], entry['new'])
    left = re.sub(r'\bSpaceApiModels\b', 'SpacePlanningModels', left)
    left = left.replace('com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper',
                        'com.runisys.orbit.spacevisibility.infrastructure.space.persistence.mapper')
    name = item['new'].rsplit('.', 1)[1]
    if name == 'ObservationSampleEvaluator':
        left = left.replace('final class ObservationSampleEvaluator', 'public final class ObservationSampleEvaluator')
        left = re.sub(r'(?m)^    VisibilitySample evaluate\(', '    public VisibilitySample evaluate(', left)
    if name == 'OrbitPositionCache':
        left = left.replace('final class OrbitPositionCache', 'public final class OrbitPositionCache')
        left = left.replace('    OrbitPositionCache(int capacity)', '    public OrbitPositionCache(int capacity)')
        left = left.replace('    synchronized Vector3 positionAt(', '    public synchronized Vector3 positionAt(')
    if name == 'OrbitState':
        left = left.replace('    OrbitState(OrbitTime time,', '    public OrbitState(OrbitTime time,')
    same = body(left) == body(right)
    records.append(dict(old=item['oldPath'], new=item['newPath'], bodyUnchanged=same,
        sha256=hashlib.sha256((source/item['newPath']).read_bytes()).hexdigest()))
assert len(list(source.rglob('*.java'))) == len(records) == 121
assert all(x['bodyUnchanged'] for x in records), [x for x in records if not x['bodyUnchanged']]
base = source / 'com/runisys/orbit/spacevisibility'
assert not list((base/'application').glob('*.java'))
assert not list((base/'api').glob('*.java'))
for file in (base/'domain').rglob('*.java'):
    text = file.read_text(encoding='utf-8')
    assert not re.search(r'\bcom\.runisys\.orbit\.spacevisibility\.(api|application|infrastructure)\.', text), file
for file in list((base/'application').rglob('*.java'))+list((base/'infrastructure').rglob('*.java')):
    text = file.read_text(encoding='utf-8')
    assert not re.search(r'\bcom\.runisys\.orbit\.spacevisibility\.api\.', text), file
for group in ('geometry','orbit','payload','time','window'):
    for file in (base/'domain'/group).glob('*.java'):
        assert not re.search(r'\bcom\.runisys\.orbit\.spacevisibility\.domain\.(space|ballistic)\.',
                             file.read_text(encoding='utf-8')), file
for business, other in [('space', 'ballistic'), ('ballistic','space')]:
    for file in (base/'domain'/business).rglob('*.java'):
        assert '.domain.'+other+'.' not in file.read_text(encoding='utf-8'), file
save('结构与计算主体核验',dict(passed=True, javaFiles=len(records), bodiesUnchanged=True,
    allowedChanges='包、导入、SpacePlanningModels名称、MapperScan包及3类必要访问权限；主体原语句不变',
    noRootBusinessClasses=True, noDomainUpperLayerDependency=True, noApiReverseDependency=True,
    sharedDomainHasNoBusinessImplementationDependency=True, files=records))
print('PASS 121类主体保持、分包及层间依赖边界')
