"""仅验收驱动行为、原证据与下传校验的定向检查；不触发构建、HTTP或数值重新计算。"""
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import copy, json, os, shutil, subprocess, sys
import acceptance, acceptance_final, acceptance_shared as shared
import performance

ROOT=shared.ROOT
checks=[]
def checked(name):checks.append(name);print('PASS',name,flush=True)

def main():
    assert acceptance.OUT!=acceptance_final.OUT and acceptance.RUN!=acceptance_final.RUN
    assert acceptance.driver.backendTests==347 and acceptance_final.driver.backendTests==351
    assert not acceptance.driver.productDelta and len(acceptance_final.driver.productDelta)==3
    assert acceptance.build.__func__ is acceptance_final.build.__func__ is shared.Acceptance.build
    assert acceptance.execute.__func__ is acceptance_final.execute.__func__
    checked('两个兼容入口路径/347与351/三处delta及同一执行实现')
    for module in (acceptance,acceptance_final):
        command=[sys.executable,str(Path(module.__file__)),'not-a-stage']
        result=subprocess.run(command,capture_output=True)
        assert result.returncode!=0 and b'not-a-stage' not in result.stdout
    checked('两入口非法阶段拒绝，不触发构建')
    with TemporaryDirectory(prefix='a01-driver-') as temporary:
        temp=Path(temporary)
        for label,module in [('initial',acceptance),('final',acceptance_final)]:
            driver=shared.Acceptance(final=label=='final');driver.OUT=temp/label;driver.OUT.mkdir()
            driver.RUN=temp/(label+'-run');driver.RUN.mkdir()
            driver.CLEAN=driver.RUN/'source';driver.CLEAN.mkdir();driver.PACKAGE=driver.RUN/'release';driver.PACKAGE.mkdir()
            with patch.object(driver,'execute') as execute:
                for method in (driver.prepare,driver.package):
                    try:method()
                    except AssertionError:pass
                    else:raise AssertionError('已有输出必须受保护')
                execute.assert_not_called()
            driver.execute('raw-check',[sys.executable,'-c',"import sys;sys.stdout.buffer.write(b'raw\\x00\\xff\\n')"],cwd=temp)
            assert (driver.OUT/'raw-check.txt').read_bytes()==b'raw\x00\xff\n'
            metadata=json.loads((driver.OUT/'raw-check-exit.json').read_text())
            assert metadata['exitCode']==0 and metadata['stdoutSha256']==shared.sha(driver.OUT/'raw-check.txt')
            try:driver.execute('raw-check',[sys.executable,'-c','raise Exception()'],cwd=temp)
            except AssertionError:pass
            else:raise AssertionError('原stdout不能覆写')
        checked('初最终输出隔离/原字节退出码SHA/prepare和package拒绝覆盖')
        for label,module in [('initial',acceptance),('final',acceptance_final)]:
            driver=shared.Acceptance(final=label=='final');driver.OUT=temp/(label+'-build');driver.OUT.mkdir()
            driver.RUN=temp/(label+'-build-run');driver.RUN.mkdir();driver.CLEAN=module.CLEAN
            shutil.copy2(module.OUT/'build-all.txt',driver.OUT/'build-all.txt')
            with patch.object(driver,'execute') as execute:driver.build()
            args=execute.call_args.args
            assert args[0]=='build-all' and str(module.CLEAN/'deploy/build.ps1') in args[1] and args[2]==module.CLEAN
            summary=json.loads((driver.OUT/'test-summary.json').read_text())
            assert summary['backend']['tests']==driver.backendTests and summary['backendPassed']==driver.backendTests-1
            assert summary['frontendTests']==112 and summary['frontendFiles']==27
        checked('读取既有真实XML和stdout验证347/351与346/350统计及构建命令，不执行构建')
        sys.path.insert(0,str(ROOT/'scripts'));import run_baseline as baseline
        driver=shared.Acceptance(final=True);driver.OUT=temp/'numeric';driver.OUT.mkdir();driver.RUN=temp/'numeric-run'
        native_copy=shutil.copy2;gold=ROOT/'test-data/baseline/space/satellites.json';before=shared.sha(gold)
        identical=temp/'identical.json';identical.write_bytes(gold.read_bytes());different=temp/'different.json';different.write_bytes(b'{}')
        class ProbeComplete(Exception):pass
        def capture_probe(*args):
            assert baseline.shutil.copy2(identical,gold)==str(gold)
            try:baseline.shutil.copy2(different,gold)
            except AssertionError:pass
            else:raise AssertionError('禁止覆写不同黄金')
            raise ProbeComplete()
        with patch.object(baseline,'capture',capture_probe):
            try:driver.numeric()
            except ProbeComplete:pass
            else:raise AssertionError('probe未执行')
        assert shutil.copy2 is native_copy and shared.sha(gold)==before
        checked('numeric相同黄金不写/不同黄金拒绝/异常后标准库恢复，无计算')
    sys.path.insert(0,str(ROOT/'scripts'));import run_baseline as baseline
    for expected,actual in [(ROOT/'test-data/baseline/ballistic',acceptance_final.RUN/'ballistic-actual'),(ROOT/'test-data/baseline/space',acceptance_final.RUN/'space-actual'),(acceptance_final.RUN/'complete21-original-actual',acceptance_final.RUN/'complete21-current-actual')]:
        assert not baseline.compare(expected,actual)
    checked('既有15/9/21完整实际产物沿原Decimal/字节比较器无差异，未重新计算')
    package=acceptance_final.PACKAGE;manifest=json.loads((package/'manifest.json').read_text(encoding='utf-8-sig'))
    assert {x['path']:x['sha256'] for x in manifest}=={p.relative_to(package).as_posix():shared.sha(p) for p in package.rglob('*') if p.is_file() and p.name!='manifest.json'}
    for item in json.loads((acceptance_final.OUT/'clean-source-manifest.json').read_text(encoding='utf-8'))['files']:
        assert shared.sha(acceptance_final.CLEAN/item['path'])==item['sha256']
        if item['path'].startswith(('backend/','frontend/','deploy/','test-data/','scripts/')):
            assert shared.sha(ROOT/item['path'])==item['sha256']
    checked('359文件正式包/1432最终源副本及全部实际产品源码与锁保持原SHA')
    browser=json.loads((acceptance_final.OUT/'browser-summary.json').read_text(encoding='utf-8'))
    event=next(x for x in browser['space']['result']['evidence'] if x['name']=='downlink');raw=json.dumps(event['response']).encode()
    expected,dim=performance.digest(raw,'downlink');actual=copy.deepcopy(event['response'])
    actual['satelliteStationWindows'][0]['windows'][0]['durationSec']=441.0
    assert performance.digest(json.dumps(actual).encode(),'downlink')[0]==expected
    actual['satelliteStationWindows'][0]['windows'][0]['durationSec']=441.0000001
    assert performance.digest(json.dumps(actual).encode(),'downlink')[0]!=expected
    for broken in [dict(satelliteStationWindows=[],coverageWindows=[]),event['response']|{'coverageWindows':[]}]:
        try:performance.digest(json.dumps(broken).encode(),'downlink')
        except AssertionError:pass
        else:raise AssertionError('空下传应拒绝')
    actual=copy.deepcopy(event['response']);actual['satelliteStationWindows'][0]['windows'][0]['durationSec']=0
    try:performance.digest(json.dumps(actual).encode(),'downlink')
    except AssertionError:pass
    else:raise AssertionError('0时长应拒绝')
    checked('下传非空/正时长/规模/Decimal数值等价与微小差异不掩盖')
    print('PASS targeted checks',len(checks),flush=True)

if __name__=='__main__':main()
