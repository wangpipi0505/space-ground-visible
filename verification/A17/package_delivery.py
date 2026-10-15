"""按已交付白名单收集当前主代码与文档，重建源码ZIP并验证实际解压后的离线构建。"""
from pathlib import Path, PurePosixPath
from datetime import datetime
from collections import Counter
import hashlib
import json
import os
import re
import shutil
import zipfile
from run_validation import ROOT, OUT, RUN, execute, save

ROUND = os.environ.get('A17_PACKAGE_ROUND', '')
SUFFIX = '-' + ROUND if ROUND else ''

old = json.loads((ROOT / 'verification/A16/package-record.json').read_text(encoding='utf-8'))
archive_before = Path(old['archive'])
if not archive_before.exists():
    archive_before = next(p for p in archive_before.parent.glob('*.zip')
        if hashlib.sha256(p.read_bytes()).hexdigest() == old['sha256'])
assert hashlib.sha256(archive_before.read_bytes()).hexdigest() == old['sha256']
files = {}
with zipfile.ZipFile(archive_before) as source:
    for name in source.namelist():
        if name.startswith(('backend/src/main/java/', 'backend/maven-repository/')):
            continue
        path = ROOT / name
        assert path.is_file(), name
        files[name] = path
for path in (ROOT / 'backend/src/main/java').rglob('*.java'):
    files[path.relative_to(ROOT).as_posix()] = path
repo = ROOT / 'verification/runtime/A15/prepare-source/backend/maven-repository'
for path in repo.rglob('*'):
    if path.is_file():
        assert path.suffix in ('.jar', '.pom', '.sha1', '.repositories'), path
        files['backend/maven-repository/' + path.relative_to(repo).as_posix()] = path
blocked = {'node_modules', 'node_module', 'dist', 'target', 'test', 'tests', '__tests__', '__mocks__',
           'verification', 'test-data', 'coverage', '.git', '.idea', '.vscode', 'logs', 'storage', '__pycache__'}
for name in files:
    path = PurePosixPath(name)
    assert not set(path.parts) & blocked, name
    assert path.name not in ('manifest.json', 'data.sql'), name
    assert not re.search(r'\.(spec|test)\.[^/]+$', name, re.I), name
    assert 'com/tx/' not in name, name
stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
folder = ROOT / 'output/deliverables' / stamp
folder.mkdir(parents=True, exist_ok=False)
archive = folder / 'space-ground-visible-source.zip'
with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as target:
    for name, path in sorted(files.items()):
        target.write(path, name)
extract = RUN / ('extracted-' + stamp)
with zipfile.ZipFile(archive) as target:
    assert target.testzip() is None
    assert sorted(target.namelist()) == sorted(files)
    for name, path in files.items():
        assert target.read(name) == path.read_bytes(), name
    target.extractall(extract)
record = dict(passed=True, archive=str(archive), bytes=archive.stat().st_size,
    sha256=hashlib.sha256(archive.read_bytes()).hexdigest(), entries=len(files),
    groups=dict(Counter(PurePosixPath(n).parts[0] for n in files)),
    mavenFiles=sum(n.startswith('backend/maven-repository/') for n in files), extraction=str(extract),
    crcPassed=True, allSourceBytesMatched=True, forbiddenEntries=[], noOldPackage=True)
save('package-record' + SUFFIX, record)
save('package-source-files' + SUFFIX, [dict(path=n, sha256=hashlib.sha256(p.read_bytes()).hexdigest())
    for n, p in sorted(files.items()) if not n.startswith('backend/maven-repository/')])
work = extract / 'backend'
execute('package' + SUFFIX + '-offline-build', [shutil.which('mvn.cmd'), '-B', '-o', '-s', work / 'maven-settings.xml',
    '-gs', work / 'maven-settings.xml', '-Dmaven.repo.local=./maven-repository', '-Dmaven.test.skip=true',
    '-Dstyle.color=never', 'clean', 'package'], work)
print(json.dumps(record, ensure_ascii=False, indent=2))
