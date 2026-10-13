"""T10 用 Java8 javap 比较迁入前后公开类/成员及 JVM 描述符，不依赖源码行号。"""
from collections import Counter
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
ORIGINAL = ROOT / 'verification/baseline-copies/ballistic/source/target/classes'
MIGRATED = ROOT / 'backend/target/classes'
JAVAP = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/javap.exe')
BASE_TYPES = [
    'com.tx.alg.OrbitCalculator',
    'com.tx.alg.TrajectoryFileResolver',
    'com.tx.alg.trajectory.TargetTrajectoryFitter',
    'com.tx.alg.api.VisibilityRequest',
    'com.tx.alg.api.OrbitVisibilityController',
]


def inspect(class_root, names):
    """批量读取公开声明及descriptor；原私有嵌套类不属于对外兼容契约。"""
    result = subprocess.run(
        [str(JAVAP), '-J-Dfile.encoding=UTF-8', '-classpath', str(class_root), '-public', '-s', *names],
        capture_output=True, text=True, encoding='utf-8', check=True)
    types = {}
    current = None
    for line in result.stdout.splitlines():
        if line.startswith('Compiled from '):
            current = None
        elif line.startswith('public ') and line.endswith('{'):
            current = line.strip()
            types[current] = []
        elif current and line.strip() and line.strip() != '}':
            types[current].append(line.strip())
    return {header: Counter(members) for header, members in types.items()}


def main():
    """原公有声明必须逐项仍存在；允许本次增加的新辅助入口，不删旧成员或改类型。"""
    names = []
    for base_type in BASE_TYPES:
        stem = ORIGINAL / base_type.replace('.', '/')
        names.extend(path.relative_to(ORIGINAL).as_posix().removesuffix('.class').replace('/', '.')
                     for path in sorted(stem.parent.glob(stem.name + '*.class')))
    before = inspect(ORIGINAL, names)
    public_names = [re.search(r'(?:class|interface|enum)\s+([^\s<{]+)', header).group(1)
                    for header in before]
    after = inspect(MIGRATED, public_names)
    differences = []
    for header, expected in before.items():
        if header not in after:
            differences.append({'class': header, 'reason': '原公有类声明不存在或已改变'})
        else:
            missing = expected - after[header]
            if missing:
                differences.append({'class': header, 'missingDeclarations': list(missing.elements())})
    report = {
        'passed': not differences,
        'publicTypes': len(before),
        'declarationLinesIncludingDescriptors': sum(sum(value.values()) for value in before.values()),
        'scope': BASE_TYPES,
        'differences': differences,
    }
    (ROOT / 'verification/T10/java-contract-compare.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if differences:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
