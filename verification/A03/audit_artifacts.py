"""从实际保存原字节复核 A03 命令日志和当次清单，不重新运行任何业务。"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A03'
errors = []
commands = json.loads((OUT / 'commands.json').read_text(encoding='utf-8'))
for command in commands['commands']:
    for stream in ['stdout', 'stderr']:
        item = command[stream]
        data = (ROOT / item['path']).read_bytes()
        if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
            errors.append('原字节日志SHA不匹配:' + item['path'])
        if b'\r\r\n' in data:
            errors.append('原字节日志有CRCRLF:' + item['path'])
manifest = json.loads((OUT / 'artifact-manifest.json').read_text(encoding='utf-8'))
for item in manifest:
    data = (ROOT / item['path']).read_bytes()
    if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
        errors.append('当前清单原字节不匹配:' + item['path'])
result = {'passed': not errors, 'commandsAudited': len(commands['commands']),
          'rawStreamsAudited': len(commands['commands']) * 2, 'manifestFilesAudited': len(manifest),
          'historicalExitCodes': [c['exitCode'] for c in commands['commands']], 'errors': errors,
          'note': '本次只审计当次已有清单；外层驱动在保存本命令后刷新清单，避免自指哈希。'}
(OUT / 'byte-audit-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n',
                                         encoding='utf-8', newline='\n')
print(json.dumps(result, ensure_ascii=False))
sys.exit(0 if result['passed'] else 1)
