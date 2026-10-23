"""冻结前端输入并按官方校验值准备独立 Node 14，不修改系统安装。"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[2]
runtime = root / 'verification/runtime/A19'
runtime.mkdir(parents=True, exist_ok=True)
files = subprocess.check_output(['git', 'ls-files', '-z', 'frontend'], cwd=root).decode().strip('\0').split('\0')
baseline = runtime / 'baseline/frontend'
records = []
for name in files:
    source = root / name
    target = baseline / Path(name).relative_to('frontend')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    records.append({'path': name, 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()})
(root / 'verification/A19/baseline-files.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')

def get(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()

def node():
    filename = 'node-v14.21.3-win-x64.zip'
    base = 'https://nodejs.org/dist/v14.21.3/'
    checksums = get(base + 'SHASUMS256.txt')
    (root / 'verification/A19/node-official-checksums.txt').write_bytes(checksums)
    archive = runtime / filename
    if not archive.exists():
        archive.write_bytes(get(base + filename))
    expected = next(line.split()[0] for line in checksums.decode().splitlines() if line.endswith('  ' + filename))
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    assert actual == expected, (actual, expected)
    with zipfile.ZipFile(archive) as z:
        z.extractall(runtime)
    result = {'version': '14.21.3', 'url': base + filename, 'sha256': actual, 'officialSha256': expected}
    (root / 'verification/A19/node-toolchain.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result

def metadata():
    packages = ['@vitejs/plugin-vue2/2.3.3', 'vite/4.5.14', 'happy-dom/9.20.3', 'vitest/0.34.6', '@vue/test-utils/1.3.6', 'element-ui/2.15.14', 'pnpm/7.33.7', 'undici/5.28.5']
    result = []
    for package in packages:
        m = json.loads(get('https://registry.npmjs.org/' + package))
        result.append({k: m.get(k) for k in ['name', 'version', 'engines', 'peerDependencies']})
    (root / 'verification/A19/target-metadata.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    return result

with ThreadPoolExecutor(2) as pool:
    for result in pool.map(lambda f: f(), [node, metadata]):
        print(json.dumps(result, ensure_ascii=False))
