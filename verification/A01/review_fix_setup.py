"""A01审查修复一次性迁移：先保留字节及SHA，再收敛驱动；不读取或修改Git。"""
from pathlib import Path
import ast, hashlib, json, re, shutil

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'verification/A01'
HISTORY = HERE / 'review-fix/history'

def main():
    HISTORY.mkdir(parents=True, exist_ok=False)
    names = ['acceptance.py', 'acceptance_final.py', 'performance.py', 'report.py',
             '测试报告.md', '验收用例矩阵.json', 'final/final-selfcheck.json',
             'final/report-generation.json', 'final/navigation-browser-flow.js']
    records = []
    # 保留待修改文档和实现原字节；原R2原始证据只建立SHA清单，永不覆盖。
    for name in names:
        src = HERE / name; dest = HISTORY / name
        dest.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(src, dest)
        records.append(dict(path=src.relative_to(ROOT).as_posix(), preservedPath=dest.relative_to(ROOT).as_posix(), sha256=hashlib.sha256(src.read_bytes()).hexdigest()))
    for name in ['backend-performance.json', 'performance-r2.txt', 'performance-r2-exit.json', 'performance-resources.csv', 'performance-jstat-gc.txt', 'performance-resource-summary.json', 'server-performance-r2-binding.json', 'server-performance-r2-shutdown.json', 'server-performance-r2-start-stop.txt', 'performance-conditions.json', 'frontend-performance.json', 'browser-summary.json']:
        src = HERE / 'final' / name
        records.append(dict(path=src.relative_to(ROOT).as_posix(), sha256=hashlib.sha256(src.read_bytes()).hexdigest()))
    (HISTORY / 'preserved-manifest.json').write_text(json.dumps(records, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    initial = (HERE / 'acceptance.py').read_text(encoding='utf-8')
    lines = initial.splitlines(); tree = ast.parse(initial)
    methods=[]
    for node in tree.body:
        if not isinstance(node, ast.FunctionDef) or node.name == 'sha': continue
        code='\n'.join(lines[node.lineno-1:node.end_lineno])
        code=code.replace('def '+node.name+'(', 'def '+node.name+'(self, ')
        code=code.replace('(self, ):', '(self):')
        for name in ['OUT','RUN','CLEAN','PACKAGE']:
            code=re.sub(r'\b'+name+r'\b', 'self.'+name, code)
        for name in ['save','execute']:
            code=re.sub(r'(?<!def )\b'+name+r'\(', 'self.'+name+'(', code)
        if node.name=='prepare':
            code=code.replace("assert sha(source) == item['sha256'],", "assert sha(source) == item['sha256'] or name in self.productDelta,")
            code=code.replace("fileCount=len(files), files=files))", "fileCount=len(files), files=files, delta=[dict(path=i['path'], beforeSha256=next(x['sha256'] for x in manifest['finalFiles'] if x['path']==i['path']), finalSha256=i['sha256']) for i in files if i['sha256']!=next(x['sha256'] for x in manifest['finalFiles'] if x['path']==i['path'])]))")
        if node.name=='build':
            code=code.replace('tests=347', 'tests=self.backendTests').replace('backendPassed=346', 'backendPassed=self.backendTests-1')
        if node.name=='package':
            code=code.replace('    self.execute(', "    assert not self.PACKAGE.exists(), '拒绝覆盖已有正式包'\n    self.execute(", 1)
        if node.name=='execute':
            code=code.replace('    env = ', "    assert not (self.OUT / (name + '.txt')).exists(), '拒绝覆盖历史stdout'\n    env = ", 1)
        methods.append('\n'.join('    '+line if line else '' for line in code.splitlines()))
    header='\n'.join(lines[:21])
    header+='\nROOT = Path(__file__).resolve().parents[2]\nJAVA = Path(\'C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot\')\n\ndef sha(path):\n    return hashlib.sha256(path.read_bytes()).hexdigest()\n\n'
    header+='''class Acceptance:
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

'''
    (HERE/'acceptance_shared.py').write_text(header+'\n\n'.join(methods)+'\n',encoding='utf-8',newline='\n')
    for name,final in [('acceptance.py',False),('acceptance_final.py',True)]:
        text='''"""A01候选兼容入口：共享执行实现，仅明确选择候选配置；不受A01_FINAL隐式切换。"""
import sys
from acceptance_shared import Acceptance, ROOT, JAVA, sha

driver = Acceptance(final=FINAL)
OUT, RUN, CLEAN, PACKAGE = driver.OUT, driver.RUN, driver.CLEAN, driver.PACKAGE
save, execute = driver.save, driver.execute
prepare, build, numeric, package, original = driver.prepare, driver.build, driver.numeric, driver.package, driver.original

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) != 2 or sys.argv[1] not in ('prepare', 'build', 'numeric', 'package', 'original'):
        raise SystemExit('阶段必须为prepare/build/numeric/package/original')
    getattr(driver, sys.argv[1])()
'''.replace('FINAL',repr(final))
        (HERE/name).write_text(text,encoding='utf-8',newline='\n')

if __name__=='__main__': main()
