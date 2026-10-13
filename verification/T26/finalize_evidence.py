"""T26 复用 T10 javap 公有合同检查，只向本项写摘要，不覆盖早期证据。"""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('original_contract_check', root / 'verification/T10/verify_java_contract.py')
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
contract.BASE_TYPES.append('com.tx.alg.api.TargetTrajectoryFitController')
names = []
for base in contract.BASE_TYPES:
    stem = contract.ORIGINAL / base.replace('.', '/')
    names.extend(path.relative_to(contract.ORIGINAL).as_posix().removesuffix('.class').replace('/', '.')
                 for path in sorted(stem.parent.glob(stem.name + '*.class')))
before = contract.inspect(contract.ORIGINAL, names)
public_names = [re.search(r'(?:class|interface|enum)\s+([^\s<{]+)', header).group(1) for header in before]
after = contract.inspect(contract.MIGRATED, public_names)
differences = []
for header, expected in before.items():
    if header not in after:
        differences.append({'class': header, 'reason': '原公有类声明不存在或已改变'})
    elif expected - after[header]:
        differences.append({'class': header, 'missingDeclarations': list((expected - after[header]).elements())})
report = dict(passed=not differences, publicTypes=len(before),
              declarationLinesIncludingDescriptors=sum(sum(value.values()) for value in before.values()),
              scope=contract.BASE_TYPES, differences=differences)
destination = root / 'verification/T26'
(destination / 'java-contract-extended-compare.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
payload_names = ['com.runisys.orbit.spacevisibility.domain.payload.' + name for name in (
    'IlluminationConstraint', 'IlluminationState', 'PayloadConstraint', 'PayloadType',
    'PayloadVisibilityEvaluator', 'VisibilitySample')]
payload_before = contract.inspect(root / 'verification/runtime/T04/space-java8-validation/target/classes', payload_names)
payload_after = contract.inspect(contract.MIGRATED, payload_names)
payload_report = dict(passed=payload_before == payload_after, publicTypes=len(payload_names), names=payload_names)
(destination / 'space-payload-contract.json').write_text(json.dumps(payload_report, indent=2) + '\n', encoding='utf-8')
assert payload_report['passed']
summary = dict(tests=0, failures=0, errors=0, skipped=0, suites=[])
for path in sorted((root / 'backend/target/surefire-reports').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    record = dict(name=suite.attrib['name'], **{name: int(suite.attrib[name]) for name in ('tests', 'failures', 'errors', 'skipped')})
    summary['suites'].append(record)
    for name in ('tests', 'failures', 'errors', 'skipped'):
        summary[name] += record[name]
jar = root / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
summary['jarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
summary['javaVersion'] = '1.8.0_472'
(destination / 'test-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(contract=report, package=summary), ensure_ascii=False, indent=2))

# 将最后发布候选与数值回归所使用的前候选逐class核对，生命周期复审后无需无依据地重跑未变化算法。
earlier = root / 'verification/runtime/T26/space-target-visible-T26-before-review.jar'
if earlier.exists():
    with zipfile.ZipFile(earlier) as old, zipfile.ZipFile(jar) as new:
        class_names = sorted(name for name in new.namelist() if name.startswith('BOOT-INF/classes/') and name.endswith('.class'))
        changed = [name for name in class_names if name not in old.namelist() or old.read(name) != new.read(name)]
        assert all('/infrastructure/web/' in name for name in changed), changed
        comparison = dict(earlierSha256=hashlib.sha256(earlier.read_bytes()).hexdigest(), finalSha256=summary['jarSha256'],
                          checkedClasses=len(class_names), changedClasses=changed,
                          numericalClassesUnchanged=True)
        (destination / 'package-bytecode-compare.json').write_text(json.dumps(comparison, indent=2) + '\n', encoding='utf-8')
if differences or summary['failures'] or summary['errors']:
    raise SystemExit(1)
