"""按上次包的结构交付源码、Maven库、新dist和可搬迁前端依赖，实际解压后验证。"""
from collections import Counter
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A20'
RUN = ROOT / 'verification/runtime/A20'
NODE = ROOT / 'verification/runtime/A19/node-v14.21.3-win-x64/node.exe'
PNPM = ROOT / 'verification/runtime/A19/pnpm7/package/bin/pnpm.cjs'
PREPARE = RUN / 'prepared'

def save(name, data):
    (OUT / (name + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

def sha(data):
    return hashlib.sha256(data).hexdigest()

def execute(name, args, cwd):
    start = time.time()
    env = os.environ.copy()
    env['PATH'] = str(NODE.parent) + os.pathsep + env['PATH']
    env.pop('NODE_PATH', None)
    env['NO_COLOR'] = '1'
    result = subprocess.run([str(value) for value in args], cwd=cwd, env=env, capture_output=True,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    for stream, content in [('stdout', result.stdout), ('stderr', result.stderr)]:
        (OUT / (name + '-' + stream + '.txt')).write_bytes(content)
    save(name + '-command', {'args': [str(value) for value in args], 'cwd': str(cwd), 'exitCode': result.returncode,
         'durationSec': time.time() - start, 'stdoutSha256': sha(result.stdout), 'stderrSha256': sha(result.stderr)})
    assert result.returncode == 0, name + '执行失败，请检查实际日志'

def tracked_frontend():
    names = subprocess.check_output(['git', 'ls-files', '-z', 'frontend'], cwd=ROOT).decode().strip('\0').split('\0')
    result = []
    for name in names:
        parts = PurePosixPath(name).parts
        if any(part in {'test', 'tests', '__tests__', 'verification', 'node_modules', 'dist', '.idea'} for part in parts):
            continue
        if re.search(r'\.(spec|test)\.[^/]+$', name) or PurePosixPath(name).name.startswith('vitest.config.'):
            continue
        if (ROOT / name).is_file():
            result.append(name)
    return result

def prepare():
    RUN.mkdir(parents=True, exist_ok=True)
    PREPARE.mkdir(exist_ok=False)
    old = json.loads((ROOT / 'verification/A18/package-record-r2.json').read_text(encoding='utf-8'))
    archive = Path(old['archive'])
    assert sha(archive.read_bytes()) == old['sha256']
    origins = {}
    with zipfile.ZipFile(archive) as source:
        for name in source.namelist():
            if not name.startswith(('backend/', 'docs/')):
                continue
            parts = PurePosixPath(name).parts
            assert '..' not in parts and not PurePosixPath(name).is_absolute()
            target = PREPARE / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if name.startswith('backend/maven-repository/'):
                content = source.read(name)
            else:
                content = (ROOT / name).read_bytes()
                if name.startswith('backend/'):
                    assert content == source.read(name), name + '后端字节变化'
            target.write_bytes(content)
            origins[name] = sha(content)
    for name in tracked_frontend() + ['源码包说明.md']:
        target = PREPARE / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, target)
        origins[name] = sha(target.read_bytes())
    save('source-files', origins)
    save('previous-package', old)
    execute('workspace-build', [NODE, PNPM, 'build'], ROOT / 'frontend')
    shutil.copytree(ROOT / 'frontend/dist', PREPARE / 'frontend/dist')
    execute('portable-dependencies', [NODE, PNPM, 'install', '--offline', '--frozen-lockfile',
            '--config.node-linker=hoisted', '--config.extend-node-path=false', '--package-import-method=copy'], PREPARE / 'frontend')
    assert (PREPARE / 'frontend/pnpm-lock.yaml').read_bytes() == (ROOT / 'frontend/pnpm-lock.yaml').read_bytes()
    execute('portable-build', [NODE, PNPM, 'build'], PREPARE / 'frontend')
    print('准备完成：当前dist、源码、文档和冻结锁依赖。')

def package():
    cache = {'.vite', '.vitest', '.cache'}
    files = {}
    links = []
    for folder, directories, names in os.walk(PREPARE, followlinks=False):
        directories[:] = [name for name in directories if name not in cache]
        for name in directories + names:
            path = Path(folder) / name
            if path.is_symlink() or (getattr(path.stat(follow_symlinks=False), 'st_file_attributes', 0) & 0x400):
                links.append(str(path))
        for name in names:
            path = Path(folder) / name
            relative = path.relative_to(PREPARE).as_posix()
            if relative in {'frontend/node_modules/.modules.yaml', 'frontend/node_modules/.package-lock.json', 'frontend/node_modules/.pnpm/lock.yaml'}:
                continue
            files[relative] = path
    assert not links, links
    for name in files:
        parts = PurePosixPath(name).parts
        assert 'manifest.json' != parts[-1] or name.startswith('frontend/node_modules/'), name
        if not name.startswith('frontend/node_modules/'):
            assert not set(parts) & {'test', 'tests', 'verification', 'test-data', 'target', 'logs', 'storage', '.git', '.idea', '.vscode'}, name
            assert not re.search(r'\.(spec|test)\.[^/]+$', name), name
    # 相对命令入口是解压后可用的前提；拒绝将打包机器路径写进交付脚本。
    for name, path in files.items():
        if name.startswith('frontend/node_modules/.bin/'):
            assert b'G:\\5-YG' not in path.read_bytes() and b'G:/5-YG' not in path.read_bytes(), name
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    folder = ROOT / 'output/deliverables' / stamp
    folder.mkdir(parents=True, exist_ok=False)
    archive = folder / 'space-ground-visible-vue2-source-dist-dependencies.zip'
    dist = folder / 'dist.zip'
    with zipfile.ZipFile(dist, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for name, path in sorted(files.items()):
            if name.startswith('frontend/dist/'):
                target.write(path, name.removeprefix('frontend/'))
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for name, path in sorted(files.items()):
            target.write(path, name)
    records = []
    for kind, path in [('dist', dist), ('complete', archive)]:
        with zipfile.ZipFile(path) as z:
            assert z.testzip() is None
            assert len(z.namelist()) == len(set(z.namelist()))
            if kind == 'complete':
                assert sorted(z.namelist()) == sorted(files)
                for name, source in files.items():
                    assert z.read(name) == source.read_bytes(), name
        records.append({'kind': kind, 'path': str(path), 'bytes': path.stat().st_size, 'sha256': sha(path.read_bytes())})
    save('package', {'archives': records, 'entries': len(files), 'groups': dict(Counter(PurePosixPath(name).parts[0] for name in files)),
                    'dependencyFiles': sum(name.startswith('frontend/node_modules/') for name in files),
                    'mavenFiles': sum(name.startswith('backend/maven-repository/') for name in files),
                    'crcPassed': True, 'allBytesMatched': True, 'links': links})
    print(json.dumps(records, ensure_ascii=True))

def verify():
    record = json.loads((OUT / 'package.json').read_text(encoding='utf-8'))
    archive = Path(next(item['path'] for item in record['archives'] if item['kind'] == 'complete'))
    target = RUN / 'relocated-extraction'
    target.mkdir(exist_ok=False)
    with zipfile.ZipFile(archive) as z:
        z.extractall(target)
    work = target / 'frontend'
    before = {p.relative_to(work / 'dist').as_posix(): sha(p.read_bytes()) for p in (work / 'dist').rglob('*') if p.is_file()}
    execute('extracted-build', [NODE, PNPM, 'build'], work)
    after = {p.relative_to(work / 'dist').as_posix(): sha(p.read_bytes()) for p in (work / 'dist').rglob('*') if p.is_file()}
    scripts = list((work / 'dist/assets').glob('*.js'))
    count = sum(len(re.findall(r'Vue.js v2.7.16', p.read_text(encoding='utf-8'))) for p in scripts)
    assert count == 1, count
    save('verification', {'extraction': str(target), 'buildExitCode': 0, 'installedAfterExtraction': False,
                          'distBytesIdenticalAfterRebuild': before == after, 'distBefore': before, 'distAfter': after,
                          'vueRuntimeCount': count, 'lockUnchanged': (work / 'pnpm-lock.yaml').read_bytes() == (ROOT / 'frontend/pnpm-lock.yaml').read_bytes()})
    print(json.dumps({'build': 'passed', 'vueRuntimeCount': count, 'identicalDist': before == after}))

if __name__ == '__main__':
    {'prepare': prepare, 'package': package, 'verify': verify}[sys.argv[1]]()
