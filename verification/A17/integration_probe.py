"""从当前分层目录追踪 Java 依赖，复用原空间四个公共类进行增量编译及 HTTP 验证。"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys
from run_validation import ROOT, OUT, RUN, execute, save
sys.stdout.reconfigure(encoding='utf-8')

SOURCE = ROOT / 'backend/src/main/java'
PREFIX = 'com/runisys/orbit/spacevisibility/'
roots = [PREFIX + p for p in ('api/ballistic/', 'application/ballistic/', 'domain/ballistic/', 'infrastructure/files/')]
all_files = {p.relative_to(SOURCE).as_posix().removesuffix('.java').replace('/', '.'): p
             for p in SOURCE.rglob('*.java')}
selected = {p for p in all_files.values() if any(p.relative_to(SOURCE).as_posix().startswith(r) for r in roots)}
direct_count = len(selected)
pending = list(selected)
while pending:
    text = pending.pop().read_text(encoding='utf-8')
    package = re.search(r'^package ([^;]+);', text)[1]
    for name, file in all_files.items():
        same_package_reference = name.rsplit('.', 1)[0] == package and re.search(r'\b' + file.stem + r'\b', text)
        if (name in text or same_package_reference) and file not in selected:
            selected.add(file)
            pending.append(file)
original = Path('G:/5-YG/target-visible-alg/space-target-visible-main-集成交付/backend/src/main/java')
reused = []
for file in selected:
    relative = file.relative_to(SOURCE)
    if (original / relative).exists():
        reused.append(relative.as_posix())
records = [dict(path=p.relative_to(SOURCE).as_posix(), reusedOriginal=p.relative_to(SOURCE).as_posix() in reused,
                sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted(selected)]
save('后端依赖闭包-r2', dict(roots=roots, directFiles=direct_count, sharedFiles=len(selected)-direct_count,
     totalFiles=len(selected), newFiles=len(selected)-len(reused), reusedFiles=reused, files=records))
work = RUN / 'integration-probe-r2'
assert not work.exists()
source = work / 'src/main/java'
source.mkdir(parents=True)
for file in selected:
    relative = file.relative_to(SOURCE)
    target = source / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(original / relative if relative.as_posix() in reused else file, target)
template = ROOT / 'verification/runtime/A16/integration-probe'
shutil.copy2(template / 'pom.xml', work / 'pom.xml')
shutil.copytree(template / 'src/main/java/integration', source / 'integration')
settings = ROOT / 'backend/maven-settings.xml'
execute('incremental-r2-build', [shutil.which('mvn.cmd'), '-B', '-o', '-s', settings, '-gs', settings,
    '-Dmaven.repo.local=' + str(ROOT / 'verification/runtime/A15/prepare-source/backend/maven-repository'),
    '-Dstyle.color=never', 'clean', 'package'], work)
# 沿用 A16 的 11 路由探针条件，只改变运行和证据目录，旧证据不覆写。
probe = (ROOT / 'verification/runtime/A16/verify_increment.py').read_text(encoding='utf-8')
probe = probe.replace("Path(__file__).resolve().parents[3]", "Path(__file__).resolve().parents[2]")
probe = probe.replace("RUN = Path(__file__).resolve().parent", "RUN = ROOT / 'verification/runtime/A17'")
probe = probe.replace("verification/A16", "verification/A17")
probe = probe.replace("RUN / 'integration-probe'", "RUN / 'integration-probe-r2'")
probe = probe.replace("RUN / 'isolated-data'", "RUN / 'isolated-data-r2'")
probe = probe.replace('43新增', '44新增').replace('43新增文件', '44新增文件')
(OUT / 'integration_http.py').write_text(probe, encoding='utf-8')
execute('incremental-r2-http', [sys.executable, OUT / 'integration_http.py'])
print('增量闭包', direct_count, len(selected), len(reused), flush=True)
