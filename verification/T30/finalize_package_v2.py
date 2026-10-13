"""T30终审文案新包绑定：只复制冻结输入及已实测新dist，正式package生成新目录。

后端JAR始终沿用本项完整347/15+9+21/68HTTP实测SHA；不重复同源测试，
不覆写旧源码/包/证据。静态index核验仅针对磁盘实物，不冒称浏览器实测。
"""
from pathlib import Path
import json
import re
import shutil
from zipfile import ZipFile
from final_verification import ROOT, OUT, RUN, CLEAN, PACKAGE, sha, save, execute

V2 = RUN / 'clean-source-v2'
RELEASE = RUN / 'release-v2'
DELTA = {'frontend/src/components/BallisticWindowControls.vue', 'docs/前端载荷配置说明.md'}


def main():
    assert not V2.exists() and not RELEASE.exists(), '新源/包目录已存在，禁止覆盖'
    old = json.loads((OUT / 'clean-source-manifest.json').read_text(encoding='utf-8'))
    expected = {i['path']: i['sha256'] for i in old['files']}
    changes, files = [], []
    for name, digest in expected.items():
        source = CLEAN / name
        assert sha(source) == digest, name
        # 完整实测后产品实际输入应仅有批准的两个文案差异，根台账/证据不作为构建输入。
        if name.startswith(('backend/', 'frontend/', 'test-data/', 'deploy/', 'scripts/')):
            assert name in DELTA or sha(ROOT / name) == digest, name
        target = V2 / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name if name in DELTA else source, target)
        final_digest = sha(target)
        files.append(dict(path=name, sha256=final_digest))
        if final_digest != digest:
            changes.append(dict(path=name, beforeSha256=digest, finalSha256=final_digest))
        else:
            assert name not in DELTA, '指定delta实际没有变化'
    assert {i['path'] for i in changes} == DELTA
    save('source-final-delta', dict(baseCommit=old['sourceCommit'], baseManifestSha256=sha(OUT / 'clean-source-manifest.json'),
        files=len(files), changedSourceFiles=len(changes), changes=changes, unchangedFiles=len(files)-len(changes),
        finalFiles=files, allOtherBuildInputsMatchCurrentRoot=True,
        backendAndReferenceAndLockUnchanged=True, source='冻结1432输入+根已实测批准两处文案delta；不复制ignored目录'))

    tested_dist = json.loads((OUT / 'minio-fix-frontend-dist-fingerprint.json').read_text(encoding='utf-8'))
    actual_dist = {p.relative_to(ROOT / 'frontend/dist').as_posix(): sha(p) for p in (ROOT / 'frontend/dist').rglob('*') if p.is_file()}
    assert {i['path']: i['sha256'] for i in tested_dist} == actual_dist
    shutil.copytree(ROOT / 'frontend/dist', V2 / 'frontend/dist')
    for i in tested_dist:
        assert sha(V2 / 'frontend/dist' / i['path']) == i['sha256']
    test = json.loads((OUT / 'minio-fix-frontend-test-command.json').read_text(encoding='utf-8'))
    build = json.loads((OUT / 'minio-fix-frontend-build-command.json').read_text(encoding='utf-8'))
    assert test['exitCode'] == 0 and build['exitCode'] == 0
    stdout = (OUT / 'minio-fix-frontend-test.txt').read_text(encoding='utf-8', errors='replace')
    assert re.search(r'Test Files\s+27 passed', stdout) and re.search(r'Tests\s+112 passed', stdout)
    assert 'vue-tsc -b && vite build' in (OUT / 'minio-fix-frontend-build.txt').read_text(encoding='utf-8', errors='replace')
    save('frontend-final-bindings', dict(frontendTestFiles=27, frontendTests=112, typeAndBuildExit=0,
        sourceChanges=changes, distFiles=tested_dist, copiedDistEqualsTestedRoot=True,
        lockSha256=sha(V2 / 'frontend/pnpm-lock.yaml'), evidence=[dict(path=n, sha256=sha(OUT / n)) for n in
            ['minio-fix-frontend-test.txt', 'minio-fix-frontend-test-command.json', 'minio-fix-frontend-build.txt',
             'minio-fix-frontend-build-command.json', 'minio-fix-frontend-dist-fingerprint.json', '终审文案修复报告.md']],
        testBoundary='采用根本项同源新112/类型构建实际证据，不重复运行'))

    old_binding = json.loads((OUT / 'package-bindings.json').read_text(encoding='utf-8'))
    frozen_jar = PACKAGE / 'backend/server.jar'
    assert sha(frozen_jar) == old_binding['jarSha256']
    target = V2 / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
    target.parent.mkdir()
    shutil.copy2(frozen_jar, target)
    execute('package-v2', ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                          str(V2 / 'deploy/package.ps1'), '-Destination', str(RELEASE)], V2)
    manifest = json.loads((RELEASE / 'manifest.json').read_text(encoding='utf-8-sig'))
    actual = {p.relative_to(RELEASE).as_posix(): sha(p) for p in RELEASE.rglob('*') if p.is_file() and p.name != 'manifest.json'}
    assert {i['path']: i['sha256'] for i in manifest} == actual and len(manifest) == 359
    assert sha(RELEASE / 'backend/server.jar') == old_binding['jarSha256']
    assert {p.relative_to(RELEASE / 'frontend').as_posix(): sha(p) for p in (RELEASE / 'frontend').rglob('*') if p.is_file()} == actual_dist
    for i in old['files']:
        assert sha(CLEAN / i['path']) == i['sha256'], '原源被覆写'
    for i in files:
        assert sha(V2 / i['path']) == i['sha256'], '最终源被修改'
    assert sha(PACKAGE / 'manifest.json') == old_binding['manifestSha256'], '原包被覆写'
    index = (RELEASE / 'frontend/index.html').read_text(encoding='utf-8')
    referenced = re.findall(r'(?:src|href)="(/[^\"]+)"', index)
    for name in referenced:
        assert (RELEASE / 'frontend' / name.lstrip('/')).is_file(), name
    js = [n for n in referenced if n.endswith('.js')]
    assert js == ['/assets/index-aYRW8iIe.js']
    assert 'MinIO 按地址中的既有凭据或匿名访问' in (RELEASE / 'frontend' / js[0].lstrip('/')).read_text(encoding='utf-8')
    assert '凭据由服务环境提供' not in (RELEASE / 'frontend' / js[0].lstrip('/')).read_text(encoding='utf-8')
    with ZipFile(RELEASE / 'backend/server.jar') as z:
        assert not any(n.startswith('BOOT-INF/classes/static/') for n in z.namelist())
        classes = [n for n in z.namelist() if n.startswith('BOOT-INF/classes/') and n.endswith('.class')]
        assert len(classes) == 288
    orekit = [p for p in (RELEASE / 'orekit-data').rglob('*') if p.is_file()]
    assert len(orekit) == 349
    save('package-v2-bindings', dict(path=str(RELEASE), sourcePath=str(V2), sourceChangeCount=len(changes),
        jarSha256=sha(RELEASE / 'backend/server.jar'), jarEqualImmutableFullVerifiedCandidate=True,
        manifestSha256=sha(RELEASE / 'manifest.json'), manifestFiles=len(manifest), manifestMatchesEveryFile=True,
        manifest=manifest, frontendFiles=len(actual_dist), frontendEqualsTestedFix=True, indexReferences=referenced,
        newJsReferenced=js[0], minioTextInActualJs=True, bundledStatic=[], classes=288, orekitFiles=349,
        originalCandidateUnchanged=True, finalInputsUnchanged=True,
        reuse='同一后端JAR沿用本项新347/15+9+21/68HTTP及自然关闭证据；前端采用本项文案修复同源27文件112及类型/build证据；静态实物核验不等于新全浏览器实测'))
    print('PASS release-v2', RELEASE, 'source changes', len(changes), flush=True)
    print('JAR', sha(RELEASE / 'backend/server.jar'), 'manifest', sha(RELEASE / 'manifest.json'), flush=True)


if __name__ == '__main__':
    main()
