"""仅生成本次可提交候选字节清单；与只读delivery_check入口分开，禁止刷新旧清单。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'verification/A04'


def main():
    """纳入本次证据、修复源与所选当前源/必要输入，排除自引用总结和自身审计流。"""
    source = json.loads((OUT / 'current-source-manifest.json').read_text(encoding='utf-8'))
    paths = {ROOT / item['path'] for item in source['files']}
    paths.update(ROOT / relative for relative in ('verification/A01/acceptance_shared.py',
                 'verification/T04/BallisticBaseline.java', 'verification/T04/SpaceBaseline.java',
                 'verification/T07/SpaceRegression.java', 'scripts/run_baseline.py', 'scripts/run_space_baseline.py'))
    for path in OUT.rglob('*'):
        if not path.is_file() or path.name in ('candidate-delivery-manifest.json', '交付入口修复报告.md'):
            continue
        if path.parent == OUT / 'delivery-fix' and (path.name.endswith(('.stdout.bin', '.stderr.bin', '-command.json')) or path.name == 'final-fingerprints.json'):
            continue
        assert '__pycache__' not in path.parts
        paths.add(path)
    files = [dict(path=path.relative_to(ROOT).as_posix(), bytes=path.stat().st_size,
                  sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in sorted(paths)]
    manifest = dict(recordedUtc=datetime.now(timezone.utc).isoformat(), fileCount=len(files), files=files,
                    scope='已提交或本次可提交候选；不声称新文件已经提交；根提交时另核对实际Git blob',
                    exclusions=['本清单自身', '引用本清单SHA的交付入口修复报告.md', 'delivery-fix自身检查命令/双流及final-fingerprints.json',
                                '全部ignored runtime/冻结副本/本机依赖/生产运行产物'])
    (OUT / 'candidate-delivery-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('候选交付文件数：', len(files))


if __name__ == '__main__':
    main()
