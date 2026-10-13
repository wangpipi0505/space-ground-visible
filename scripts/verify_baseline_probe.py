"""T02 探针执行：四次独立 JVM 输出必须逐字节相同，否则立即失败。"""
import hashlib
import json
import subprocess
from pathlib import Path

workspace = Path(__file__).resolve().parent.parent
java = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin')
evidence = workspace / 'verification/T02'
classes = workspace / 'verification/runtime/T02/probe'
copies = workspace / 'verification/baseline-copies/ballistic'
classes.mkdir(parents=True, exist_ok=True)
subprocess.run([str(java / 'javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), str(evidence / 'BaselineProbe.java')], check=True)
outputs = []
for name, implementation in [('source', copies / 'source/target/classes'), ('jar', copies / 'build/alg-toolkit-1.0.0-plain.jar')]:
    for suffix in ['', '-repeat']:
        # 主类只通过反射访问原入口，classpath 中运行依赖来自冻结交付目录。
        command = [str(java / 'java.exe'), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp', f'{classes};{implementation};{copies / "dependencies/runtime-jars"}/*', 'BaselineProbe']
        result = subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (evidence / f'{name}-probe{suffix}.txt').write_bytes(result.stdout)
        outputs.append(result.stdout)
if not all(output == outputs[0] for output in outputs):
    raise RuntimeError('源码与交付 JAR 输出或重复运行输出不一致，请逐行定位差异')
summary = {'runs': 4, 'records_per_run': len(outputs[0].splitlines()), 'byte_equal': True, 'sha256': hashlib.sha256(outputs[0]).hexdigest()}
(evidence / 'probe-comparison.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary))
