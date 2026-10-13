"""核验 T10 原版独立重复采集，并保存源码/类文件和状态向量来源摘要。"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / 'test-data/baseline/ballistic-propagation'
REPEAT = ROOT / 'verification/runtime/T10/original-probe-repeat'
JAR_RUN = ROOT / 'verification/runtime/T10/original-jar-probe'
SOURCE = ROOT / 'verification/baseline-copies/ballistic/source'


def digest(path):
    """按原文件字节计算 SHA256，不先改换行、重排或舍入数值。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    """完整比较本次生成的三个 CSV；任何缺失、额外或字节不同都失败。"""
    expected = {'satellite-position.csv': 72, 'satellite-j2-state.csv': 24, 'fitter-state.csv': 26}
    actual_files = {p.name for p in REPEAT.iterdir() if p.is_file()}
    assert actual_files == set(expected), '重复采集文件集合不同'
    assert {p.name for p in JAR_RUN.iterdir() if p.is_file()} == set(expected)
    records = []
    for name, count in expected.items():
        baseline, repeat = GOLDEN / name, REPEAT / name
        assert baseline.read_bytes() == repeat.read_bytes(), name + ' 两次采集字节不同'
        assert baseline.read_bytes() == (JAR_RUN / name).read_bytes(), name + ' 源码/JAR不同'
        assert len(baseline.read_text(encoding='utf-8').splitlines()) - 1 == count
        records.append({'file': name, 'records': count, 'sha256': digest(baseline),
                        'repeatIdentical': True, 'sourceJarIdentical': True})
    sources = [
        'src/main/java/com/tx/alg/OrbitCalculator.java',
        'src/main/java/com/tx/alg/trajectory/TargetTrajectoryFitter.java',
        'target/classes/com/tx/alg/OrbitCalculator.class',
        'target/classes/com/tx/alg/trajectory/TargetTrajectoryFitter.class',
    ]
    provenance = {
        'javaVersion': '1.8.0_472',
        'utcOffsetSec': 0.0,
        'source': 'T02 冻结副本，T04 Java8 编译类；采集不引用迁入后端',
        'sourceFiles': [{'file': name, 'sha256': digest(SOURCE / name)} for name in sources],
        'probeSha256': digest(ROOT / 'verification/T10/OriginalPropagationProbe.java'),
        'deliveryJarSha256': digest(ROOT / 'verification/baseline-copies/ballistic/build/alg-toolkit-1.0.0-plain.jar'),
        'files': records,
        'passed': True,
    }
    (GOLDEN / 'provenance.json').write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': True, 'files': records}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    verify()
