"""A06本轮实际证据复核：双流/原字节/2268保护/真实网络与下载；不修改黄金或模型。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'verification/A06/review-fix'
RUNTIME = ROOT / 'verification/runtime/A06/review-fix'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


protected = json.loads((ROOT / 'verification/A06/protected-before.json').read_text(encoding='utf-8'))
protection_mismatches = [row['path'] for row in protected if sha(row['path']) != row['sha256']]
history = json.loads((OUT / 'history-index.json').read_text(encoding='utf-8'))
history_mismatches = [row['backup'] for row in history if sha(ROOT / row['backup']) != row['sha256']]
commands = []
stream_mismatches = []
for path in sorted(OUT.glob('*.json')):
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or 'argv' not in value:
        continue
    commands.append({'metadata': path.relative_to(ROOT).as_posix(), **value})
    for stream in ('stdout', 'stderr'):
        if sha(path.with_suffix(f'.{stream}.txt')) != value[f'{stream}Sha256']:
            stream_mismatches.append(f'{path.name}:{stream}')

network_path = RUNTIME / 'browser-network.json'
network = json.loads(network_path.read_text(encoding='utf-8'))
live = json.loads((ROOT / 'verification/A06/live-stack.json').read_text(encoding='utf-8'))
live_jar_sha = sha(live['jarPath'])
fit_response = next(row for row in network['network'] if row['url'].endswith('/api/trajectory/fit/package102'))
response_download_exact = (RUNTIME / 'new102-response.json').read_bytes() == fit_response['response'].encode('utf-8')
summary = {
    'protectedCount': len(protected), 'protectedMismatches': protection_mismatches,
    'backupCount': len(history), 'backupMismatches': history_mismatches,
    'commandsChecked': len(commands), 'commandStreamMismatches': stream_mismatches,
    'browser': network['browser'], 'browserErrors': network['errors'], 'browserChecks': network['checks'],
    'networkCount': len(network['network']), 'networkStatuses': [row['status'] for row in network['network']],
    'networkSha256': sha(network_path), 'fitResponseDownloadExact': response_download_exact,
    'liveJarSha256': live_jar_sha, 'liveJarUnchanged': live_jar_sha == live['jarSha256'],
    'runtimeArtifacts': [{ 'path': path.relative_to(ROOT).as_posix(), 'sizeBytes': path.stat().st_size, 'sha256': sha(path)} for path in sorted(RUNTIME.iterdir()) if path.is_file()],
}
(OUT / 'evidence-check.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(OUT / 'command-index.json').write_text(json.dumps(commands, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
sources = [ROOT / row['source'] for row in history] + [ROOT / 'verification/A06/独立输入与导航审查报告.md', ROOT / 'verification/A06/protected-before.json', ROOT / 'verification/A06/implementer-candidate.json', ROOT / 'verification/A06/source-bindings.json', Path('G:/Code/Code-V3/alg/deliverables/alg-0723/source/src/main/java/com/tx/alg/Main.java'), Path('G:/Code/Code-V3/alg/deliverables/alg-0723/source/src/main/java/com/tx/alg/OrbitCalculator.java'), Path('G:/Code/Code-V3/alg/deliverables/alg-0723/source/src/main/java/com/tx/alg/trajectory/TargetTrajectoryFitPanel.java')]
(OUT / 'source-bindings.json').write_text(json.dumps([{'path': str(path), 'sizeBytes': path.stat().st_size, 'sha256': sha(path)} for path in sources], ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: value for key, value in summary.items() if key != 'runtimeArtifacts'}, ensure_ascii=False, indent=2))
assert len(protected) == 2268 and not protection_mismatches
assert not history_mismatches and not stream_mismatches
assert not network['errors'] and all(row['status'] in (200, 201) for row in network['network'])
assert response_download_exact
assert live_jar_sha == live['jarSha256']
