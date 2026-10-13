"""T12 原时间/转换采集的重复性、交付JAR一致性与来源摘要；按原字节核验。"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / 'test-data/baseline/time-frame'
REPEAT = ROOT / 'verification/runtime/T12/original-probe-repeat'
JAR_RUN = ROOT / 'verification/runtime/T12/original-jar-probe'


def digest(path):
    """原字节摘要，不改换行或把十六进制值先舍入成展示值。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    """四份数据的文件集、数量、全部字节与三轮一致性均验证，缺失即失败。"""
    counts = {
        'legacy-time.json': 48,
        'legacy-j2000-ecef.json': 18,
        'teme-ecef.json': 18,
        'eme2000-teme.json': 18,
    }
    for directory in (REPEAT, JAR_RUN):
        assert {p.name for p in directory.iterdir() if p.is_file()} == set(counts)
    files = []
    for name, count in counts.items():
        file = GOLDEN / name
        assert file.read_bytes() == (REPEAT / name).read_bytes(), name + ' 原重复采集不一致'
        assert file.read_bytes() == (JAR_RUN / name).read_bytes(), name + ' 原JAR采集不一致'
        assert len(json.loads(file.read_text(encoding='utf-8'))) == count
        files.append({'file': name, 'records': count, 'sha256': digest(file),
                      'repeatIdentical': True, 'ballisticSourceJarIdentical': True})
    originals = [
        'verification/baseline-copies/ballistic/source/src/main/java/com/tx/alg/OrbitCalculator.java',
        'verification/baseline-copies/ballistic/source/target/classes/com/tx/alg/OrbitCalculator.class',
        'verification/baseline-copies/ballistic/build/alg-toolkit-1.0.0-plain.jar',
        'verification/baseline-copies/space/backend/src/main/java/com/runisys/orbit/spacevisibility/domain/geometry/GroundStationGeometry.java',
        'verification/runtime/T04/space-java8-validation/target/classes/com/runisys/orbit/spacevisibility/domain/geometry/GroundStationGeometry.class',
        'verification/baseline-copies/space/backend/src/main/java/com/runisys/orbit/spacevisibility/domain/orbit/KeplerOrbitStateProvider.java',
        'verification/T12/OriginalTimeFrameProbe.java',
    ]
    provenance = {
        'javaVersion': '1.8.0_472',
        'source': 'T02 冻结原源码的 T04 Java8 编译类；另以原弹道交付JAR替换其类路径复核',
        'orekitData': 'T05 按全部原字节迁入并校验的 backend/orekit-data，T12未修改',
        'doubleEncoding': 'Double.toHexString；使用 Double.parseDouble 恢复原始数值位',
        'sources': [{'file': name, 'sha256': digest(ROOT / name)} for name in originals],
        'files': files,
        'passed': True,
    }
    (GOLDEN / 'provenance.json').write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': True, 'files': files}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    verify()
