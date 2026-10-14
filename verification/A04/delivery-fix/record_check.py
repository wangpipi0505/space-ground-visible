"""记录本次有限交付检查的精确命令与原始双流，不覆盖任何历史证据。"""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import subprocess
import sys


def main():
    """名称必须唯一；子进程退出码原样返回，失败同样永久保存。"""
    directory = Path(__file__).resolve().parent
    name, *argv = sys.argv[1:]
    paths = [directory / (name + suffix) for suffix in ('.stdout.bin', '.stderr.bin', '-command.json')]
    assert not any(path.exists() for path in paths), '拒绝覆盖已有检查：' + name
    started = datetime.now(timezone.utc).isoformat()
    with paths[0].open('wb') as out, paths[1].open('wb') as err:
        result = subprocess.run(argv, stdout=out, stderr=err)
    record = dict(argv=argv, cwd=str(Path.cwd()), startedUtc=started,
                  endedUtc=datetime.now(timezone.utc).isoformat(), exitCode=result.returncode)
    for label, path in zip(('stdout', 'stderr'), paths):
        record[label + 'File'] = path.name
        record[label + 'Bytes'] = path.stat().st_size
        record[label + 'Sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    paths[2].write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(name, 'exit=', result.returncode)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
