"""具名Git LF交付修复有限验证；不执行Java、Maven或数值计算，不修改原证据。"""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'verification/A04/git-delivery-fix'
SOURCE = 'verification/T07/SpaceRegression.java'
COMMIT = '3dc6285'
OLD_SHA = '22956f0df0970c5a67942906c1c3ff8e6aed7e204629794670000d2bf5a9ec15'
LF_SHA = '622687b8150a2795e788876c28b757558f39a351e618ba94370e625b0306ba81'


def digest(data):
    """原始字节摘要；不解码双流，不归一化证据行尾。"""
    return hashlib.sha256(data).hexdigest()


def write_json(path, data):
    """只写本轮新增证据，不覆盖历史记录。"""
    assert not path.exists(), '拒绝覆盖已有证据：' + str(path)
    path.write_bytes((json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def run(name, argv):
    """逐真实子进程记录UTC、argv、退出码及原始双流；失败同样保存。"""
    paths = [OUT / (name + suffix) for suffix in ('.stdout.bin', '.stderr.bin', '-command.json')]
    assert not any(path.exists() for path in paths), '拒绝覆盖已有命令：' + name
    started = datetime.now(timezone.utc).isoformat()
    result = subprocess.run(argv, cwd=ROOT, capture_output=True)
    ended = datetime.now(timezone.utc).isoformat()
    paths[0].write_bytes(result.stdout)
    paths[1].write_bytes(result.stderr)
    record = dict(argv=argv, cwd=str(ROOT), startedUtc=started, endedUtc=ended, exitCode=result.returncode)
    for label, data, path in zip(('stdout', 'stderr'), (result.stdout, result.stderr), paths):
        record.update({label + 'File': path.name, label + 'Bytes': len(data), label + 'Sha256': digest(data)})
    write_json(paths[2], record)
    return result


def export_source():
    """从固定已提交版本真实导出旧源，保留Git命令原stdout作为LF证明。"""
    path = OUT / 'git-t07-source.stdout.bin'
    if not path.exists():
        result = run('git-t07-source', ['git', 'show', COMMIT + ':' + SOURCE])
        assert result.returncode == 0, result.stderr
    data = path.read_bytes()
    assert len(data) == 15202 and digest(data) == LF_SHA
    original = (ROOT / SOURCE).read_bytes()
    assert len(original) == 15397 and digest(original) == OLD_SHA
    assert original.replace(b'\r\n', b'\n') == data
    proof = OUT / 'source-line-ending-proof.json'
    if not proof.exists():
        write_json(proof, dict(path=SOURCE, gitCommit=COMMIT, gitCommandRecord='git-t07-source-command.json',
                             originalBytes=len(original), originalSha256=digest(original), gitLfBytes=len(data),
                             gitLfSha256=digest(data), originalCrlfCount=original.count(b'\r\n'),
                             convertedBytes=len(original.replace(b'\r\n', b'\n')),
                             convertedSha256=digest(original.replace(b'\r\n', b'\n')), conversionByteEqual=True))
    return data


def fingerprint(root):
    """逐文件SHA绑定临时树，入口前后必须完全相同。"""
    return {path.relative_to(root).as_posix(): digest(path.read_bytes())
            for path in sorted(root.rglob('*')) if path.is_file()}


def main():
    """最小树按最终清单复制，具名源换为真实Git LF；反例只改变临时副本。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('label')
    parser.add_argument('--case', choices=('no-runtime', 'worktree-crlf', 'source-byte-change', 'csv-crlf', 'json-crlf', 'raw-crlf'), default='no-runtime')
    args = parser.parse_args()
    data = export_source()
    manifest = ROOT / 'verification/A04/交付清单.json'
    items = json.loads(manifest.read_bytes())
    candidate = json.loads((ROOT / 'verification/A04/candidate-delivery-manifest.json').read_bytes())
    with tempfile.TemporaryDirectory(prefix='a04-git-lf-') as directory:
        root = Path(directory)
        paths = {item['path'] for item in items}
        paths.update(['verification/A04/交付清单.json',
                      'verification/A04/git-delivery-fix/history/before-bindings.json',
                      'verification/A04/git-delivery-fix/history/delivery_check.py.before'])
        for relative in sorted(paths):
            dest = root / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes((ROOT / relative).read_bytes())
        if args.case != 'worktree-crlf':
            (root / SOURCE).write_bytes(data)
        assert not (root / 'verification/runtime').exists()
        changed = None
        if args.case == 'source-byte-change':
            changed = SOURCE
            (root / SOURCE).write_bytes(data.replace(b'class SpaceRegression', b'class SpaceRegressioX', 1))
        elif args.case in ('csv-crlf', 'json-crlf', 'raw-crlf'):
            suffix = {'csv-crlf': '.csv', 'json-crlf': '.json', 'raw-crlf': '.stdout.bin'}[args.case]
            changed = next(item['path'] for item in candidate['files']
                           if item['path'].endswith(suffix)
                           and b'\n' in (root / item['path']).read_bytes())
            path = root / changed
            raw = path.read_bytes()
            # 只做标准CRLF/LF转换，避免CRCRLF这种额外变化掩盖比较器过度归一化。
            path.write_bytes(raw.replace(b'\r\n', b'\n') if b'\r\n' in raw else raw.replace(b'\n', b'\r\n'))
        before = fingerprint(root)
        result = run(args.label, [sys.executable, '-X', 'utf8', '-B', str(root / 'verification/A04/delivery_check.py'), '--root', str(root)])
        after = fingerprint(root)
        write_json(OUT / (args.label + '-fingerprints.json'), dict(case=args.case, files=len(before),
                   runtimePresent=False, changedPath=changed, before=before, after=after, byteReadonly=before == after))
        assert before == after, '入口改写证据树'
        assert not (root / 'verification/runtime').exists()
        sys.stdout.buffer.write(result.stdout)
        sys.stderr.buffer.write(result.stderr)
        if args.case in ('no-runtime', 'worktree-crlf'):
            print(('真实Git LF' if args.case == 'no-runtime' else '具名工作树CRLF') + '最小证据树：',
                  len(before), '文件；无runtime；逐文件前后SHA完全一致')
            raise SystemExit(result.returncode)
        assert result.returncode != 0 and changed.encode('utf-8') in result.stderr, '反例未被具名拒绝'
        print('反例已拒绝：', args.case, changed, 'checker exit=', result.returncode)


if __name__ == '__main__':
    main()
