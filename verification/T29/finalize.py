"""整理T29源/锁/构建/命令指纹和文档链接证据，不宣称未运行检查通过。"""
from pathlib import Path
from collections import Counter
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/T29'
RUNTIME = ROOT / 'verification/runtime/T29'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    documentation = [ROOT / 'README.md', ROOT / 'deploy/部署说明.md',
                     ROOT / 'docs/接口使用说明.md', ROOT / 'docs/载荷时间与文件口径.md',
                     ROOT / 'docs/独立外推工具与后续方案.md']
    missing = []
    for path in documentation:
        text = path.read_text(encoding='utf-8-sig')
        for link in re.findall(r'(?<!!)\[[^\]]+\]\(([^)]+)\)', text):
            if '://' not in link and not (path.parent / link.split('#', 1)[0]).exists():
                missing.append(dict(source=path.relative_to(ROOT).as_posix(), target=link))
    assert not missing, missing
    result = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True)
    (OUT / 'git-diff-check.txt').write_bytes(result.stdout + result.stderr)
    result.check_returncode()
    (OUT / 'git-diff-check-exit.json').write_text(json.dumps(dict(command=['git', 'diff', '--check'], exitCode=result.returncode)), encoding='utf-8')
    source = json.loads((OUT / 'source-after.json').read_text(encoding='utf-8'))
    for item in source['files']:
        assert sha(ROOT / item['path']) == item['sha256'], item['path']
    locks = []
    for name in ('frontend/pnpm-lock.yaml', 'frontend/package.json', 'backend/pom.xml'):
        actual = (ROOT / name).read_bytes()
        head = subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=ROOT)
        # Git规范字节可能为LF而原工作文件曾CRLF；只按行尾规范比较，不允许依赖内容变化。
        assert actual == head.replace(b'\r\n', b'\n'), name
        locks.append(dict(path=name, sha256=sha(ROOT / name), headCanonicalContentUnchanged=True))
    jar = RUNTIME / 'clean-source/backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
    binding = json.loads((OUT / 'service-binding-final.json').read_text(encoding='utf-8'))
    assert sha(jar) == binding['jarSha256']
    for stage in ('', '-fresh', '-final'):
        closed = json.loads((OUT / ('service-close' + stage + '.json')).read_text(encoding='utf-8'))
        assert closed['naturalExit'] and closed['uncaught'] == 0
    tests = (OUT / 'selected-java-package.txt').read_text(encoding='utf-8', errors='replace')
    totals = re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)', tests)
    assert tuple(map(int, totals[-1])) == (28, 0, 0, 0)
    commands = []
    for path in sorted(OUT.glob('*-exit.json')):
        info = json.loads(path.read_text(encoding='utf-8'))
        assert info['exitCode'] == 0, path
        commands.append(dict(file=path.name, **info))
    summary = dict(baseCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
                   sourceFingerprint='source-after.json中的308实际规范化工作文件及288去调试主class',
                   locks=locks, jarSha256=sha(jar), documentation=[dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(path)) for path in documentation],
                   frontendDist=[dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(path)) for path in sorted((ROOT / 'frontend/dist').rglob('*')) if path.is_file()],
                   commands=commands, selectedJavaTests=28, frontendTests=112, frontendTestFiles=27,
                   documentationLocalLinksValid=True, servicesNaturallyClosed=[58400, 61436, 21928],
                   excluded='原版只读、test-data/星历/历史raw日志不规范化；未执行新完整压力/浏览器矩阵')
    (OUT / 'final-bindings.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('PASS final bindings', len(commands), 'commands;', len(documentation), 'documents')


if __name__ == '__main__':
    main()
