"""冻结本轮既有字节清单并立即重读审计；自身输出按明确例外保留，避免循环指纹。"""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
EXCLUDED = {'artifact-manifest.json', 'artifact-audit.json', 'final-audit-public-command.json',
            'final-audit-public-stdout.bin', 'final-audit-public-stderr.bin'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dump(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


if __name__ == '__main__':
    __import__('sys').stdout.reconfigure(encoding='utf-8')
    protection = json.loads((OUT / 'protection-after.json').read_bytes())
    # 核心交付物必须仍是GREEN时点的字节，根新格式检查不可回写合同。
    for item in protection['current']:
        assert sha(ROOT / item['path']) == item['sha256']
    files = {p for p in OUT.rglob('*') if p.is_file() and p.name not in EXCLUDED}
    files.update(ROOT / r['path'] for r in protection['current'])
    # runtime交接副本被Git忽略；正式报告在本目录，随本轮新证据纳入可提交清单。
    assert (OUT / '正式修复报告.md').read_bytes() == (ROOT / 'verification/runtime/acceptance-final-fix-report.md').read_bytes()
    records = [{'path': p.relative_to(ROOT).as_posix(), 'bytes': p.stat().st_size, 'sha256': sha(p)}
               for p in sorted(files)]
    manifest = {'time': datetime.now(timezone.utc).isoformat(),
                'scope': '本轮新证据（含根新4.0.4格式结果）、README、交接报告及稳定四文件；不替代旧A02/A03历史清单。',
                'excludedSelfOutputs': sorted(EXCLUDED), 'total': len(records), 'files': records}
    dump(OUT / 'artifact-manifest.json', manifest)
    failures = []
    text_count = 0
    for item in json.loads((OUT / 'artifact-manifest.json').read_bytes())['files']:
        path = ROOT / item['path']
        data = path.read_bytes()
        if len(data) != item['bytes'] or sha(path) != item['sha256']:
            failures.append(item['path'] + ': 字节数或SHA不匹配')
        if path.suffix in ['.md', '.py', '.json']:
            text_count += 1
            try:
                data.decode('utf-8')
                assert b'\r' not in data and not data.startswith(b'\xef\xbb\xbf')
                if path.suffix == '.json':
                    json.loads(data)
            except (UnicodeError, AssertionError, ValueError):
                failures.append(item['path'] + ': UTF8/LF/JSON检查失败')
    result = {'time': datetime.now(timezone.utc).isoformat(), 'files': len(records), 'utf8LfTextFiles': text_count,
              'manifestSha256': sha(OUT / 'artifact-manifest.json'), 'passed': not failures, 'failures': failures}
    dump(OUT / 'artifact-audit.json', result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
