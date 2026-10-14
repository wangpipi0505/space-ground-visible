"""A03 本波字节复核：原历史、修改前副本、当前清单与保护快照均读取实际文件。"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A03'
errors = []


def verify(item):
    data = (ROOT / item['path']).read_bytes()
    if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
        errors.append('字节/SHA不匹配:' + item['path'])
    return data


preserved = json.loads((OUT / 'review-fix-history/preserved-originals.json').read_text(encoding='utf-8'))
for item in preserved['unchanged']:
    verify(item)
for pair in preserved['saved']:
    data = verify(pair['saved'])
    if len(data) != pair['original']['bytes'] or hashlib.sha256(data).hexdigest() != pair['original']['sha256']:
        errors.append('修改前副本未保持原字节:' + pair['original']['path'])
original = json.loads((OUT / 'commands.json').read_text(encoding='utf-8'))['commands']
current = json.loads((OUT / 'review-fix-evidence/commands.json').read_text(encoding='utf-8'))
for command in original + current:
    for stream in ['stdout', 'stderr']:
        data = verify(command[stream])
        data.decode('utf-8')
        if b'\r\r\n' in data:
            errors.append('原流存在CRCRLF:' + command[stream]['path'])
manifest = json.loads((OUT / 'artifact-manifest.json').read_text(encoding='utf-8'))
for item in manifest:
    data = verify(item)
    if item['path'] == 'verification/A03/artifact-manifest.json':
        errors.append('清单错误自指')
    if Path(item['path']).suffix in ['.md', '.py', '.json']:
        data.decode('utf-8')
        if b'\r' in data or data.startswith(b'\xef\xbb\xbf'):
            errors.append('新文档非UTF8无BOM/LF:' + item['path'])
protected = json.loads((OUT / 'protected-before.json').read_text(encoding='utf-8'))
for item in protected:
    verify(item)
validation = json.loads((OUT / 'validation-result.json').read_text(encoding='utf-8'))
if not validation['passed'] or validation['errors'] or not all(item['passed'] for item in validation['details']):
    errors.append('当前来源核验未全部通过')
result = {'passed': not errors, 'originalCommands': len(original), 'originalExitCodes': [c['exitCode'] for c in original],
          'originalStreams': len(original) * 2, 'preservedFingerprints': len(preserved['unchanged']),
          'beforeCopies': len(preserved['saved']), 'currentCommandsBeforeThisCapture': len(current),
          'allStreamsBeforeThisCapture': (len(original) + len(current)) * 2, 'manifestFiles': len(manifest),
          'protectedFiles': len(protected), 'sourceChecks': validation['checks'], 'valueChecks': validation['valueChecks'],
          'links': len(validation['links']), 'screenshots': len(validation['screenshots']), 'csvInputs': len(validation['csvInputs']),
          'errors': errors, 'note': '此命令自身原流/命令记录由外层驱动保存后刷新清单；随后仅只读复核最终全部记录。'}
print(json.dumps(result, ensure_ascii=False))
sys.exit(0 if result['passed'] else 1)
