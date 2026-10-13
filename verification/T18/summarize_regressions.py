"""只读取最终实际输出，严格重比三套文件集并记录工具结果及最终包版本。"""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from run_baseline import compare


def main():
    evidence = ROOT / 'verification/T18'
    runtime = ROOT / 'verification/runtime/T18'
    checks = []
    for name, expected, actual in [
        ('space-9', ROOT / 'test-data/baseline/space', runtime / 'space-actual'),
        ('ballistic-10', ROOT / 'test-data/baseline/ballistic', runtime / 'ballistic-actual'),
        ('space-complete-21', runtime / 'original-actual', runtime / 'migrated-actual')]:
        differences = compare(expected, actual)
        assert not differences, (name, differences)
        files = sorted(p.relative_to(actual).as_posix() for p in actual.rglob('*') if p.is_file())
        checks.append(dict(name=name, expected=expected.relative_to(ROOT).as_posix(),
            actual=actual.relative_to(ROOT).as_posix(), files=files, fileCount=len(files), differences=differences))
    standalone = []
    for name in ('ballistic-java-run.log', 'plain-java-run.log'):
        raw = (evidence / name).read_bytes()
        # PowerShell 5重定向带UTF-16 BOM，Python子进程日志为UTF-8；按真实文件标记解码。
        text = raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')
        success = [line for line in text.splitlines() if line.startswith('PASS ')]
        assert success, name
        standalone.extend(success)
    jar = ROOT / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
    summary = dict(passed=True, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), comparisons=checks,
        ordinaryJava=standalone, numericComparison='JSON Decimal exact values/structure/order; CSV and report text exact bytes',
        geometryPairs=130, geometryRotatedCases=520, geometryEncoding='raw double bits',
        limitations=['未进行内存profiling或性能基准，分配结论只来自源码和javap静态检查',
            '既有符号链接权限检查跳过1项', '两轮完整package均记录Tomcat Nio2Acceptor退出线程NPE，留T26',
            '没有前端或生产规模、全部OS/JVM测试，T19/T20未开始'])
    (evidence / 'regression-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(passed=True, comparisons=[dict(name=c['name'], files=c['fileCount']) for c in checks]), ensure_ascii=False))


if __name__ == '__main__':
    main()
