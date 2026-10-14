"""A01 干净最终验收：仅写本项证据/runtime，不改产品源码、黄金或原目录。

阶段 prepare/build/numeric/package/http/original 独立执行并保存原字节输出。
来源为冻结文件清单对应的当前实际工作文件；正式 Windows PowerShell5 构建/打包，HTTP绑定本轮包。
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from unittest.mock import patch
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

class Acceptance:
    """初/最终候选仅配置不同；实例拥有路径，不随环境或另一入口导入串用全局。"""
    def __init__(self, final=False):
        self.OUT = ROOT / ('verification/A01/final' if final else 'verification/A01')
        self.RUN = ROOT / ('verification/runtime/A01/final-candidate' if final else 'verification/runtime/A01')
        self.CLEAN = self.RUN / ('final-source-v3' if final else 'clean-source')
        self.PACKAGE = self.RUN / 'release'
        self.backendTests = 351 if final else 347
        self.productDelta = frozenset({
            'backend/src/main/java/com/runisys/orbit/spacevisibility/application/VisibilityTaskResultService.java',
            'backend/src/test/java/com/runisys/orbit/spacevisibility/application/VisibilityTaskResultServiceTest.java',
            'backend/src/test/java/com/runisys/orbit/spacevisibility/api/SpaceVisibilityControllerIntegrationTest.java',
        }) if final else frozenset()

    def save(self, name, value):
        (self.OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')

    def execute(self, name, arguments, cwd=ROOT):
        """stdout/stderr合并保留原字节，不经PowerShell文本重定向或截尾。"""
        assert not (self.OUT / (name + '.txt')).exists(), '拒绝覆盖历史stdout'
        env = dict(os.environ, JAVA_HOME=str(JAVA), PYTHONIOENCODING='utf-8')
        env['PATH'] = str(JAVA / 'bin') + os.pathsep + env['PATH']
        started = time.time()
        with (self.OUT / (name + '.txt')).open('wb') as stream:
            result = subprocess.run(arguments, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT)
        self.save(name + '-exit', dict(command=arguments, cwd=str(cwd), exitCode=result.returncode,
                                 durationSec=time.time() - started, stdoutSha256=sha(self.OUT / (name + '.txt'))))
        print(name, 'exit=', result.returncode, flush=True)
        result.check_returncode()

    def prepare(self):
        """按最终冻结文件清单复制当前工作文件，不调用Git、不复制历史生成目录；每一输入绑定本轮SHA。"""
        assert not self.CLEAN.exists(), '拒绝覆盖新干净源码'
        manifest = json.loads((ROOT / 'verification/T30/source-final-delta.json').read_text(encoding='utf-8'))
        self.RUN.mkdir(parents=True, exist_ok=True)
        files = []
        for item in manifest['finalFiles']:
            name = item['path']
            # 产品、参考、正式构建与运行示例均使用当前真实工作文件；历史报告只复制冻结清单中的文件。
            source = ROOT / name
            if name.startswith(('backend/', 'frontend/', 'deploy/', 'test-data/', 'scripts/')):
                assert sha(source) == item['sha256'] or name in self.productDelta, '冻结输入变化：' + name
            destination = self.CLEAN / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            files.append(dict(path=name, sha256=sha(destination)))
        assert not (self.CLEAN / 'backend/src/main/resources/static').exists()
        self.save('clean-source-manifest', dict(sourceCommit='030e73d1887cb6cde11e784120a0ee6b5caae103',
            method='从当前工作文件按最终冻结清单复制；未操作Git', fileCount=len(files), files=files, delta=[dict(path=i['path'], beforeSha256=next(x['sha256'] for x in manifest['finalFiles'] if x['path']==i['path']), finalSha256=i['sha256']) for i in files if i['sha256']!=next(x['sha256'] for x in manifest['finalFiles'] if x['path']==i['path'])]))
        for name, command in [('java-version', [str(JAVA / 'bin/java.exe'), '-version']),
                              ('maven-version', ['mvn.cmd', '-version']), ('node-version', ['node.exe', '--version']),
                              ('pnpm-version', ['pnpm.cmd', '--version']),
                              ('powershell-version', ['powershell.exe', '-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'])]:
            self.execute(name, command)
        print('exported files', len(files), flush=True)

    def build(self):
        self.execute('build-all', ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                             str(self.CLEAN / 'deploy/build.ps1'), '-Component', 'all', '-JavaHome', str(JAVA)], self.CLEAN)
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        reports = []
        for p in sorted((self.CLEAN / 'backend/target/surefire-reports').glob('TEST-*.xml')):
            node = ET.parse(p).getroot()
            item = dict(file=p.name, sha256=sha(p), **{key: int(node.attrib[key]) for key in totals})
            reports.append(item)
            for key in totals:
                totals[key] += item[key]
        shutil.copytree(self.CLEAN / 'backend/target/surefire-reports', self.RUN / 'surefire-reports')
        text = (self.OUT / 'build-all.txt').read_text(encoding='utf-8', errors='replace')
        assert totals == dict(tests=self.backendTests, failures=0, errors=0, skipped=1), totals
        assert re.search(r'Test Files\s+27 passed', text) and re.search(r'Tests\s+112 passed', text)
        assert 'vue-tsc -b && vite build' in text
        self.save('test-summary', dict(backend=totals, backendPassed=self.backendTests-1, backendReports=reports,
                                  frontendFiles=27, frontendTests=112, typeAndBuildExit=0,
                                  lockSha256=sha(self.CLEAN / 'frontend/pnpm-lock.yaml')))

    def numeric(self):
        """复用原驱动/Decimal比较器；阻止capture重写已冻结参考，全部输出为新目录。"""
        sys.path.insert(0, str(ROOT / 'scripts'))
        import run_baseline as baseline
        original_copy = baseline.shutil.copy2

        def safe_copy(source, destination, *args, **kwargs):
            dest = Path(destination)
            if dest.is_relative_to(ROOT / 'test-data'):
                assert Path(source).read_bytes() == dest.read_bytes(), '参考输入不符，禁止覆盖'
                return str(dest)
            return original_copy(source, destination, *args, **kwargs)

        ballistic_logs = self.OUT / 'ballistic'
        ballistic_logs.mkdir()
        # 保护仅覆盖capture调用；成功或失败都恢复标准库，避免初/最终入口互相污染。
        with patch.object(baseline.shutil, 'copy2', safe_copy):
            baseline.capture(self.CLEAN / 'backend', self.RUN / 'ballistic-actual', self.RUN / 'ballistic-runner', ballistic_logs)
        dependencies = (ROOT / 'verification/runtime/T04/space-runner/classpath.txt').read_text().strip()
        self.execute('space9-capture', [sys.executable, str(ROOT / 'scripts/run_space_baseline.py'), 'capture',
                                 '--classes', str(self.CLEAN / 'backend/target/classes'), '--classpath', dependencies,
                                 '--orekit-data', str(self.CLEAN / 'backend/orekit-data'), '--output', str(self.RUN / 'space-actual'),
                                 '--runtime', str(self.RUN / 'space-runner'), '--evidence', str(self.OUT / 'space')])
        outputs = {}
        for label, classes in [('original', ROOT / 'verification/runtime/T04/space-java8-validation/target/classes'),
                               ('current', self.CLEAN / 'backend/target/classes')]:
            runner = self.RUN / ('complete21-' + label + '-runner')
            runner.mkdir()
            actual = self.RUN / ('complete21-' + label + '-actual')
            actual.mkdir()
            cp = str(classes) + os.pathsep + dependencies
            self.execute('complete21-' + label + '-compile', [str(JAVA / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', cp,
                     '-d', str(runner), str(ROOT / 'verification/T07/SpaceRegression.java')])
            self.execute('complete21-' + label + '-run', [str(JAVA / 'bin/java.exe'), '-Dfile.encoding=UTF-8',
                     '-Djava.awt.headless=true', '-Dorekit.data.path=' + str(self.CLEAN / 'backend/orekit-data'),
                     '-cp', str(runner) + os.pathsep + cp, 'SpaceRegression', str(actual)])
            outputs[label] = actual
        comparisons = []
        for name, expected, actual in [('ballistic15', ROOT / 'test-data/baseline/ballistic', self.RUN / 'ballistic-actual'),
                                      ('space9', ROOT / 'test-data/baseline/space', self.RUN / 'space-actual'),
                                      ('spaceComplete21', outputs['original'], outputs['current'])]:
            differences = baseline.compare(expected, actual)
            comparisons.append(dict(name=name, expectedFiles=sum(p.is_file() for p in expected.rglob('*')),
                                     actualFiles=sum(p.is_file() for p in actual.rglob('*')), differences=differences))
        self.save('numeric-summary', dict(passed=all(not i['differences'] for i in comparisons), comparisons=comparisons,
                                     comparator='原JSON Decimal结构/数值/顺序严格相等；CSV/报告逐字节，无新增容差'))
        assert all(not i['differences'] for i in comparisons), comparisons
        print('PASS strict numeric 15/9/21', flush=True)

    def package(self):
        assert not self.PACKAGE.exists(), '拒绝覆盖已有正式包'
        self.execute('package', ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                           str(self.CLEAN / 'deploy/package.ps1'), '-Destination', str(self.PACKAGE)], self.CLEAN)
        jar = self.PACKAGE / 'backend/server.jar'
        assert sha(jar) == sha(self.CLEAN / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar')
        manifest = json.loads((self.PACKAGE / 'manifest.json').read_text(encoding='utf-8-sig'))
        actual = {p.relative_to(self.PACKAGE).as_posix(): sha(p) for p in self.PACKAGE.rglob('*') if p.is_file() and p.name != 'manifest.json'}
        assert {i['path']: i['sha256'] for i in manifest} == actual
        with ZipFile(jar) as z:
            prefix = 'BOOT-INF/classes/'
            names = [n for n in z.namelist() if n.startswith(prefix) and n.endswith('.class')]
            mismatch = [n for n in names if z.read(n) != (self.CLEAN / 'backend/target/classes' / n[len(prefix):]).read_bytes()]
            static = [n for n in z.namelist() if n.startswith(prefix + 'static/')]
            assert not mismatch and not static
            assert any('spring-boot-2.7.18.jar' in n for n in z.namelist())
            assert any('orekit-12.0.jar' in n for n in z.namelist())
        orekit = [p for p in (self.PACKAGE / 'orekit-data').rglob('*') if p.is_file()]
        assert len(orekit) == 349
        for i in json.loads((self.OUT / 'clean-source-manifest.json').read_text(encoding='utf-8'))['files']:
            assert sha(self.CLEAN / i['path']) == i['sha256'], i['path']
        self.save('package-bindings', dict(path=str(self.PACKAGE), jarSha256=sha(jar), manifestSha256=sha(self.PACKAGE / 'manifest.json'),
                                    files=len(manifest), manifestMatchesEveryFile=True, classesCompared=len(names),
                                    classesEqualOwnBuild=True, bundledStatic=static, orekitFiles=len(orekit),
                                    frontendFiles=sum(p.is_file() for p in (self.PACKAGE / 'frontend').rglob('*')),
                                    sourceFilesUnchanged=True, lockSha256=sha(self.CLEAN / 'frontend/pnpm-lock.yaml')))
        print('package', jar, sha(jar), flush=True)

    def original(self):
        comparisons = []
        for name, path in [('space', Path('G:/5-YG/target-visible-alg/space-target-visible-main-集成交付')),
                           ('ballistic', Path('G:/Code/Code-V3/alg/deliverables/alg-0723'))]:
            baseline = json.loads((ROOT / ('verification/T02/' + name + '-manifest.json')).read_text(encoding='utf-8-sig'))
            records = [dict(path=i['path'], expected=i['sha256'], actual=sha(path / i['path'])) for i in baseline]
            differences = [i for i in records if i['expected'] != i['actual']]
            comparisons.append(dict(name=name, root=str(path), files=len(records), differences=differences, records=records))
        self.save('original-directory-check', dict(total=sum(i['files'] for i in comparisons), comparisons=comparisons,
                                             passed=all(not i['differences'] for i in comparisons), action='只读重新hash，不覆写初始清单'))
        assert sum(i['files'] for i in comparisons) == 697 and all(not i['differences'] for i in comparisons)
        print('PASS original directory SHA 526+171=697', flush=True)
