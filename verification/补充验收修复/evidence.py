"""本轮原字节保存、精确命令记录与只读保护核验；不调用历史证据写入入口。"""
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
ALLOWED = ['verification/A02/build_contract.py', 'docs/接口文档.md',
           'docs/openapi/openapi.json', 'docs/软件功能使用说明.md']


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def protected_paths():
    # 保护既有证据和产品字节；根拥有台账、计划和最终提交，不在本波禁止变化范围。
    paths = set()
    for relative in ['verification/A01', 'verification/A02', 'verification/A03',
                     'backend', 'frontend', 'deploy', 'scripts', 'test-data']:
        paths.update(p for p in (ROOT / relative).rglob('*') if p.is_file()
                     and not {'node_modules', 'target', '__pycache__', '.vite'}.intersection(p.relative_to(ROOT).parts))
    # 保留本轮所读的运行来源原字节；不遍历runtime冻结源码副本及缓存。
    runtime = ROOT / 'verification/runtime/A01/final-candidate'
    paths.update(p for p in (runtime / 'http-extended').rglob('*') if p.is_file())
    paths.add(runtime / 'release/backend/server.jar')
    paths = {p for p in paths if not p.is_relative_to(OUT)
             and p.relative_to(ROOT).as_posix() not in ALLOWED
             and p.relative_to(ROOT).as_posix() != 'verification/runtime/acceptance-final-fix-report.md'}
    git = ROOT / '.git'
    paths.update(p for p in [git / 'HEAD', git / 'index', git / 'packed-refs'] if p.is_file())
    paths.update(p for p in (git / 'refs').rglob('*') if p.is_file())
    return paths


def preserve():
    manifest = []
    for relative in ALLOWED:
        source = ROOT / relative
        saved = OUT / 'history' / (relative.replace('/', '__') + '.before.bin')
        saved.parent.mkdir(parents=True, exist_ok=True)
        saved.write_bytes(source.read_bytes())
        manifest.append({'path': relative, 'bytes': source.stat().st_size, 'sha256': sha(source),
                         'saved': saved.relative_to(ROOT).as_posix(), 'savedSha256': sha(saved)})
    dump(OUT / 'history/preserved-manifest.json', manifest)
    before = [{'path': p.relative_to(ROOT).as_posix(), 'bytes': p.stat().st_size, 'sha256': sha(p)}
              for p in sorted(protected_paths())]
    # 原目录只按原697条具名来源建立当前指纹，不改或重新冻结原目录。
    originals = json.loads((ROOT / 'verification/A01/original-directory-check.json').read_bytes())
    original_records = [{'path': str(Path(c['root']) / r['path']),
                         'sha256': sha(Path(c['root']) / r['path'])}
                        for c in originals['comparisons'] for r in c['records']]
    dump(OUT / 'protection-before.json', {'time': datetime.now(timezone.utc).isoformat(),
         'allowed': ALLOWED, 'protected': before, 'originals': original_records})
    print(json.dumps({'saved': len(manifest), 'protected': len(before), 'originals': len(original_records)}))


def protect():
    before = json.loads((OUT / 'protection-before.json').read_bytes())
    differences = []
    for record in before['protected'] + before['originals']:
        path = Path(record['path'])
        if not path.is_absolute():
            path = ROOT / path
        actual = sha(path) if path.is_file() else None
        if actual != record['sha256']:
            differences.append({'path': record['path'], 'expected': record['sha256'], 'actual': actual})
    new_protected = sorted(p.relative_to(ROOT).as_posix() for p in protected_paths()
                           if p.relative_to(ROOT).as_posix() not in {r['path'] for r in before['protected']})
    result = {'time': datetime.now(timezone.utc).isoformat(), 'protected': len(before['protected']),
              'originals': len(before['originals']), 'differences': differences,
              'newProtectedPaths': new_protected, 'passed': not differences and not new_protected,
              'current': [{'path': r, 'bytes': (ROOT / r).stat().st_size, 'sha256': sha(ROOT / r)} for r in ALLOWED]}
    dump(OUT / 'protection-after.json', result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['passed'] else 1


def run(label, argv):
    # subprocess原始stdout/stderr不改行尾、不重编码；摘要绑定实际保存字节。
    started = datetime.now(timezone.utc).isoformat()
    process = subprocess.run(argv, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ended = datetime.now(timezone.utc).isoformat()
    stdout = OUT / (label + '-stdout.bin')
    stderr = OUT / (label + '-stderr.bin')
    stdout.write_bytes(process.stdout)
    stderr.write_bytes(process.stderr)
    result = {'argv': argv, 'cwd': str(ROOT), 'started': started, 'ended': ended, 'exit': process.returncode,
              'stdout': stdout.relative_to(ROOT).as_posix(), 'stdoutBytes': len(process.stdout), 'stdoutSha256': sha(stdout),
              'stderr': stderr.relative_to(ROOT).as_posix(), 'stderrBytes': len(process.stderr), 'stderrSha256': sha(stderr)}
    dump(OUT / (label + '-command.json'), result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(process.stdout.decode('utf-8', errors='replace'))
    print(process.stderr.decode('utf-8', errors='replace'))
    return process.returncode


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if sys.argv[1] == 'preserve':
        preserve()
    elif sys.argv[1] == 'protect':
        sys.exit(protect())
    elif sys.argv[1] == 'run':
        sys.exit(run(sys.argv[2], sys.argv[3:]))
