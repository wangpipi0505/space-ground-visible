"""T29 源码行尾/注释整理的真实字节证据；只扫描跟踪产品源码与正式脚本。

整理前后以相同Java8 javac -g:none编译主源码，逐class精确比较，排除仅注释
造成的调试行号差异；test-data、Orekit及历史验证日志只读取校验值，不改字节。
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'verification/T29'
RUNTIME = ROOT / 'verification/runtime/T29'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')
EXTENSIONS = {'.java', '.ts', '.vue', '.css', '.xml', '.json', '.yml', '.yaml', '.py', '.ps1', '.sql'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tracked():
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
    return [ROOT / name for name in names if name and name.startswith(('backend/', 'frontend/', 'deploy/', 'scripts/'))
            and Path(name).suffix in EXTENSIONS and 'orekit-data' not in Path(name).parts]


def manifest(paths):
    return [{'path': path.relative_to(ROOT).as_posix(), 'sha256': sha(path),
             'crlfCount': path.read_bytes().count(b'\r\n'),
             'utf8Bom': path.read_bytes().startswith(b'\xef\xbb\xbf')} for path in paths]


def execute(name, command):
    env = dict(os.environ, JAVA_HOME=str(JAVA))
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + env['PATH']
    result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True)
    (EVIDENCE / (name + '.txt')).write_bytes(result.stdout + result.stderr)
    (EVIDENCE / (name + '-exit.json')).write_text(json.dumps(dict(command=command, exitCode=result.returncode)), encoding='utf-8')
    result.check_returncode()


def compile_snapshot(label):
    dependency_file = RUNTIME / 'compile-classpath.txt'
    if not dependency_file.exists():
        execute('compile-dependencies', ['mvn.cmd', '-B', '-f', str(ROOT / 'backend/pom.xml'),
                'dependency:build-classpath', '-DincludeScope=compile', '-Dmdep.outputFile=' + str(dependency_file)])
    target = RUNTIME / (label + '-classes')
    target.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / 'backend/src/main/java').rglob('*.java'))
    argfile = RUNTIME / (label + '-javac.args')
    argfile.write_text('\n'.join('"' + str(path).replace('\\', '/') + '"' for path in sources), encoding='utf-8')
    execute(label + '-compile-no-debug', [str(JAVA / 'bin/javac.exe'), '-encoding', 'UTF-8', '-g:none',
            '-cp', dependency_file.read_text(encoding='utf-8').strip(), '-d', str(target), '@' + str(argfile)])
    return {path.relative_to(target).as_posix(): sha(path) for path in sorted(target.rglob('*.class'))}


if __name__ == '__main__':
    RUNTIME.mkdir(parents=True, exist_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    mode = sys.argv[1]
    if mode == 'before':
        assert not (EVIDENCE / 'source-before.json').exists(), '不得覆盖已冻结整理前证据'
        files = tracked()
        protected = [path for path in (ROOT / 'test-data').rglob('*') if path.is_file()]
        protected += [path for path in (ROOT / 'backend/orekit-data').rglob('*') if path.is_file()]
        (EVIDENCE / 'source-before.json').write_text(json.dumps(dict(files=manifest(files), protected=manifest(protected)), ensure_ascii=False, indent=2), encoding='utf-8')
        # 保留仅本事项临时源码，供审查剥离注释后复核语法保持；该目录受Git忽略。
        for path in files:
            target = RUNTIME / 'before-source' / path.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())
        classes = compile_snapshot('before')
        (EVIDENCE / 'class-before.json').write_text(json.dumps(classes, indent=2), encoding='utf-8')
        print('before', len(files), 'sources', len(classes), 'classes')
    elif mode == 'normalize':
        changes = []
        for path in tracked():
            original = path.read_bytes()
            text = original.decode('utf-8-sig')
            normalized = ('\n'.join(line.rstrip() for line in text.splitlines()) + '\n').encode('utf-8')
            if path.suffix == '.ps1':
                normalized = b'\xef\xbb\xbf' + normalized
            if original != normalized:
                path.write_bytes(normalized)
                changes.append(path.relative_to(ROOT).as_posix())
        (EVIDENCE / 'line-ending-changes.json').write_text(json.dumps(changes, ensure_ascii=False, indent=2), encoding='utf-8')
        print('normalized', len(changes))
    elif mode == 'after':
        before = json.loads((EVIDENCE / 'source-before.json').read_text(encoding='utf-8'))
        for item in before['protected']:
            assert sha(ROOT / item['path']) == item['sha256'], item['path']
        classes = compile_snapshot('after')
        old_classes = json.loads((EVIDENCE / 'class-before.json').read_text(encoding='utf-8'))
        assert classes == old_classes, '去除调试信息的Java8完整主类字节必须精确相同'
        files = manifest(tracked())
        assert all(not item['crlfCount'] and (item['utf8Bom'] == item['path'].endswith('.ps1')) for item in files)
        (EVIDENCE / 'source-after.json').write_text(json.dumps(dict(files=files, protectedUnchanged=len(before['protected']),
                identicalNoDebugClasses=len(classes), classHashes=classes), ensure_ascii=False, indent=2), encoding='utf-8')
        print('after', len(files), 'sources', len(classes), 'identical classes; protected', len(before['protected']))
