"""核对A07实际命令的原始双流。保留非零退出及CLI语义错误，不将它们记成通过。"""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[2]
folder = root / 'verification/A07'
rows = []
for path in sorted(folder.rglob('*.json')):
    record = json.loads(path.read_bytes())
    if not isinstance(record, dict) or not isinstance(record.get('argv'), list):
        continue
    # 原始命令使用同名stdout/stderr文件；根Git门使用显式仓库相对路径。
    streams = {}
    for stream in ('stdout', 'stderr'):
        explicit = record.get(stream)
        if isinstance(explicit, dict) and 'path' in explicit:
            target = root / explicit['path']
            expected = explicit['sha256']
        elif stream + 'Sha256' in record:
            candidates = [path.with_suffix('.' + stream + suffix) for suffix in ('.log', '.txt')]
            target = next((p for p in candidates if p.is_file()), None)
            assert target is not None, str(path)
            expected = record[stream + 'Sha256']
        else:
            break
        assert target.resolve().is_relative_to(root.resolve())
        raw = target.read_bytes()
        assert hashlib.sha256(raw).hexdigest() == expected, str(target)
        streams[stream] = raw
    if len(streams) != 2:
        continue
    rows.append({'path': path.relative_to(root).as_posix(), 'exitCode': record['exitCode'],
                 'cliReportedError': b'### Error' in streams['stdout']})
print(json.dumps({'verifiedCommands': len(rows), 'originalStreams': len(rows) * 2,
                  'nonzeroExits': [r for r in rows if r['exitCode'] != 0],
                  'cliSemanticErrors': [r for r in rows if r['cliReportedError']],
                  'scope': '原双流SHA核对，不把已保留的失败或CLI退出0冒称产品验证通过'},
                 ensure_ascii=False, indent=2))
