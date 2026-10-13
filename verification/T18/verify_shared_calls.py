"""T18 用最终编译字节码核验真实生产调用链、无新分配以及包/编译类一致性。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
GEOMETRY = 'com.runisys.orbit.spacevisibility.domain.geometry.'
CLASSES = ROOT / 'backend/target/classes'
JAVAP = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/javap.exe'


def main():
    targets = {
        GEOMETRY + 'Vector3': [('dot(', 'GeometryMath.dot:'), ('norm()', 'GeometryMath.norm:'), ('angleDeg(', 'GeometryMath.angleRad:')],
        'com.tx.alg.OrbitCalculator$Vector3': [('dot(', 'GeometryMath.dot:'), ('norm()', 'GeometryMath.norm:')],
        GEOMETRY + 'GroundStationGeometry': [('hasLineOfSight(', 'GeometryMath.spaceLineOfSight:')],
        'com.tx.alg.OrbitCalculator': [('hasLineOfSight(', 'GeometryMath.ballisticSphereLineOfSight:'),
            ('hasLineOfSightWgs84Ellipsoid(', 'GeometryMath.ballisticEllipsoidLineOfSight:'), ('angleBetween(', 'GeometryMath.angleRad:'),
            ('isTargetObservableFromSatellite(', 'Vector3.norm:')],
        'com.runisys.orbit.spacevisibility.domain.payload.PayloadVisibilityEvaluator': [('evaluate(', 'Vector3.norm:')],
        GEOMETRY + 'GeometryMath': [],
    }
    evidence = ROOT / 'verification/T18'
    evidence.mkdir(exist_ok=True)
    report = []
    jar = ROOT / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        for owner, calls in targets.items():
            result = subprocess.run([JAVAP, '-J-Dfile.encoding=UTF-8', '-classpath', str(CLASSES), '-c', '-p', owner],
                capture_output=True, text=True, encoding='utf-8', check=True).stdout
            (evidence / (owner.rsplit('.', 1)[-1] + '-bytecode.log')).write_text(result, encoding='utf-8')
            blocks = re.split(r'\n(?=  (?:public|private|protected) )', result)
            for name, call in calls:
                matching = [block for block in blocks if name in block.split('\n', 1)[0]]
                assert matching and any(call in block for block in matching), (owner, name, call)
                report.append(dict(owner=owner, method=name, calls=call, verified=True))
            if owner == GEOMETRY + 'GeometryMath':
                assert not re.search(r'\d+:\s+(?:new|newarray|anewarray|multianewarray)\b', result)
            relative = owner.replace('.', '/') + '.class'
            assert archive.read('BOOT-INF/classes/' + relative) == (CLASSES / relative).read_bytes(), owner
    # 公共类的额外空间ABI检查，保留私有坐标字段及方法是源码和黄金检查的职责。
    space_abi = []
    for name in ('Vector3', 'GroundStationGeometry'):
        def abi(path):
            return subprocess.run([JAVAP, '-J-Dfile.encoding=UTF-8', '-classpath', str(path), '-public', '-s', GEOMETRY+name],
                capture_output=True, text=True, encoding='utf-8', check=True).stdout
        before = abi(ROOT / 'verification/runtime/T04/space-java8-validation/target/classes')
        after = abi(CLASSES)
        assert before == after, name
        space_abi.append(dict(type=GEOMETRY+name, identical=True))
    summary = dict(passed=True, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), calls=report,
        sharedCoreHasAllocationInstructions=False, classesEqualPackagedBytes=True, spacePublicAbi=space_abi)
    (evidence / 'shared-production-calls.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
