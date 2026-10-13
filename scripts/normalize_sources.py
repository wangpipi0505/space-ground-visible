"""仅规范新工程源码行尾，不处理冻结副本、星历、依赖和数值基准数据。"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {".py", ".ps1", ".java", ".ts", ".vue", ".json", ".xml", ".yml", ".yaml", ".css"}
EXCLUDED = {".git", "target", "node_modules", "orekit-data", "baseline-copies", "test-data"}

for path in ROOT.rglob("*"):
    if path.is_file() and not EXCLUDED.intersection(path.relative_to(ROOT).parts):
        if path.suffix in EXTENSIONS:
            text = path.read_text(encoding="utf-8-sig")
            path.write_text("\n".join(line.rstrip() for line in text.splitlines()) + "\n",
                            encoding="utf-8", newline="\n")
