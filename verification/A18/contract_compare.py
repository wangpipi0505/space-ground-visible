"""运行同一Spring/Jackson探针，规范化仅Java包名后严格比较合同。"""
import json
import os
from pathlib import Path
from run_validation import ROOT, OUT, RUN, JAVA, execute, save

mapping = json.loads((OUT / 'package-map.json').read_text(encoding='utf-8'))
names = [
    'com.runisys.orbit.spacevisibility.api.SpaceApiModels',
    'com.runisys.orbit.spacevisibility.api.ballistic.VisibilityRequest',
    'com.runisys.orbit.spacevisibility.api.ballistic.BallisticTrajectoryRequests',
    'com.runisys.orbit.spacevisibility.api.ballistic.BallisticFileModels',
    'com.runisys.orbit.spacevisibility.api.ballistic.BallisticErrorResponse',
    'com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter']
forward = {x['old']: x['new'] for x in mapping}
for stage in ('before', 'after-v3'):
    runtime = RUN / ('contract-' + stage)
    runtime.mkdir()
    cp = str(RUN / stage / 'backend/target/classes') + os.pathsep + (RUN / ('numeric-' + stage) / 'ballistic-runner/classpath.txt').read_text().strip()
    execute('contract-' + stage + '-compile', [JAVA / 'bin/javac.exe', '-encoding', 'UTF-8', '-cp', cp,
        '-d', runtime, OUT / 'ApiContractProbe.java'])
    types = names if stage == 'before' else [forward.get(x, x) for x in names]
    execute('contract-' + stage + '-run', [JAVA / 'bin/java.exe', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
        '-Dorekit.data.path=' + str(RUN / stage / 'backend/orekit-data'), '-cp', str(runtime) + os.pathsep + cp,
        'ApiContractProbe', runtime / 'output', ','.join(types)])
results = {}
for name in ('routes.json', 'jackson.json'):
    left = json.loads((RUN / 'contract-before/output' / name).read_text(encoding='utf-8'))
    right = (RUN / 'contract-after-v3/output' / name).read_text(encoding='utf-8')
    for item in sorted(mapping, key=lambda x: -len(x['new'])):
        right = right.replace(item['new'], item['old'])
    right = json.loads(right)
    assert left == right, name
    results[name] = dict(equal=True, entries=len(left))
save('http-jackson-contract-compare', dict(passed=True, comparisons=results,
    normalization='仅逐类Java名称映射；HTTP路由、JSON字段、类型、读写方向与日期格式严格相等'))
print(results)
