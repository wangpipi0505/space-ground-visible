"""T02 原版冻结：只读取原目录，按白名单复制并逐文件校验 SHA256。"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path


def digest(path):
    """流式计算摘要，避免星历和交付包一次性占用内存。"""
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def freeze(root, destination, selections, manifest):
    """冻结白名单中的文件；清单仅含相对路径和摘要，不包含配置内容。"""
    records = []
    excluded = {'.git', 'target', 'node_modules', 'dist', '.idea', '.obsidian', '__pycache__'}
    for selection in selections:
        selected = root / selection
        if not selected.exists():
            raise FileNotFoundError(selected)
        files = selected.rglob('*') if selected.is_dir() else [selected]
        for source in sorted(files):
            relative = source.relative_to(root)
            if not source.is_file() or excluded.intersection(relative.parts):
                continue
            if 'static' in relative.parts:
                continue
            # 文档截图不是运行输入；源码/public 的图片必须保留，避免破坏前端构建。
            if relative.parts[0] == 'docs' and source.suffix.lower() in {'.png', '.jpg', '.jpeg', '.gif'}:
                continue
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            before = digest(source)
            shutil.copy2(source, target)
            if before != digest(target) or before != digest(source):
                raise RuntimeError('冻结字节不一致：' + str(relative))
            records.append({'path': relative.as_posix(), 'sha256': before})
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(records, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{root.name}: {len(records)} 个文件逐字节摘要核验通过')


def main():
    """路径显式传入；所有写入限定于本工程 verification 目录。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--space', required=True, type=Path)
    parser.add_argument('--ballistic', required=True, type=Path)
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parent.parent
    copies = workspace / 'verification/baseline-copies'
    evidence = workspace / 'verification/T02'
    freeze(args.space, copies / 'space', ['backend/src', 'backend/pom.xml', 'backend/db', 'backend/orekit-data', 'frontend/src', 'frontend/public', 'frontend/package.json', 'frontend/pnpm-lock.yaml', 'frontend/index.html', 'frontend/tsconfig.json', 'frontend/tsconfig.app.json', 'frontend/tsconfig.node.json', 'frontend/vite.config.ts', 'frontend/vitest.config.ts'], evidence / 'space-manifest.json')
    freeze(args.ballistic, copies / 'ballistic', ['source/src', 'source/pom.xml', 'source/res', 'data', 'docs', 'build', 'dependencies/runtime-jars'], evidence / 'ballistic-manifest.json')


if __name__ == '__main__':
    main()
