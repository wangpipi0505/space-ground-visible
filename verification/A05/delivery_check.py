"""A05默认只读交付核验；不访问runtime、原目录、JAR/classes或外部STK安装。

核对候选相对路径SHA、635项源码/输入、命令原流、原CSV质量及已保存统计摘要。
它验证交付完整性和摘要内部一致性，不重算缺少运行资产的物理模拟/传播/窗口。
"""
from pathlib import Path
import json
import run_stk_validation as qa

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A05'


def read(name):
    return json.loads((OUT/(name+'.json')).read_text(encoding='utf-8'))


def checked_path(relative):
    """拒绝绝对路径、目录逃逸和runtime/编译产物进入可提交清单。"""
    path=ROOT/relative
    resolved=path.resolve()
    if Path(relative).is_absolute() or not resolved.is_relative_to(ROOT.resolve()) or any(
            part in ('runtime','target','node_modules','baseline-copies','__pycache__') for part in Path(relative).parts):
        raise ValueError('候选不允许路径：'+relative)
    if not resolved.is_file(): raise ValueError('交付文件缺失：'+relative)
    return resolved


def main():
    """所有核验只读；缺原模拟资产不阻塞交付SHA核对，不冒称重新计算。"""
    candidate=read('candidate-manifest')
    names=[item['path'] for item in candidate['files']]
    if len(names)!=len(set(names)) or candidate['fileCount']!=len(names): raise ValueError('候选数量/重复项不符')
    for item in candidate['files']:
        if qa.sha(checked_path(item['path']))!=item['sha256']:
            raise ValueError('候选SHA不符：'+item['path'])
    source=read('current-source-manifest')
    if not {item['path'] for item in source['files']}.issubset(set(names)):
        raise ValueError('候选遗漏必要源码/输入')
    if source['fileCount']!=635 or len(source['files'])!=635: raise ValueError('源清单点数不符')
    for item in source['files']:
        if qa.sha(checked_path(item['path']))!=item['sha256']: raise ValueError('源码SHA不符：'+item['path'])
    for binding in read('raw-input-bindings')['files']:
        path=checked_path(binding['fixturePath'])
        if qa.sha(path)!=binding['sha256'] or binding['sha256']!=binding['originalSha256']:
            raise ValueError('原CSV与纳入字节不同')
        rows=qa.parse_stk(path,binding['kind'])
        if len(rows)!=binding['count'] or qa.stamp(rows[0]['utc'])!=binding['firstUtc'] or qa.stamp(rows[-1]['utc'])!=binding['lastUtc']:
            raise ValueError('STK输入点数/时域不符')
    if qa.sha(OUT/'red-parser-source.py.txt')!=read('tests-red-command')['redSourceSha256']:
        raise ValueError('RED源码快照与执行绑定不符')
    if qa.sha(OUT/'failed-probe-source.java.txt')!=read('probe-bindings')['javaSourceSha256']:
        raise ValueError('失败探针源码快照与绑定不符')
    if qa.sha(OUT/'StkStateProbe.java')!=read('probe-bindings-repaired')['javaSourceSha256']:
        raise ValueError('真实执行探针源码SHA不符')
    allowed_failures=read('failure-repair-record')['unexpectedFailures']
    commands=list(OUT.glob('*-command.json'))
    for path in commands:
        command=json.loads(path.read_text(encoding='utf-8'))
        for prefix in ('stdout','stderr'):
            stream=checked_path('verification/A05/'+command[prefix+'File'])
            if qa.sha(stream)!=command[prefix+'Sha256']: raise ValueError('原始流SHA不符：'+path.name)
        if command['exitCode']!=command.get('expectedExitCode',0) and path.name not in allowed_failures:
            raise ValueError('未解释失败命令：'+path.name)
    protection=read('protected-after-ecef')
    if not protection['passed'] or protection['changes'] or protection['currentSourceChanges']:
        raise ValueError('历史保护核验失败')
    m=read('metrics-final')
    ecef=read('metrics-ecef')
    roi=read('metrics-legacy-roi')
    for reference in ('hpop','nodrag'):
        for label in ('original','current'):
            if roi['satellite'][reference][label]['count']!=74: raise ValueError('旧诊断ROI不完整')
    for label in ('original','current'):
        if ecef['trajectory'][label]['count']!=7371: raise ValueError('ECEF端点重建未完整')
    for key in ('eciP','eciV','ecefP','ecefV'):
        if ecef['originalCurrentTrajectory'][key]['norm']['max']!=0: raise ValueError('ECEF端点双跑差异')
    for label in ('original','current'):
        if m['trajectory'][label]['count']!=7371: raise ValueError('目标未完整')
        for source in ('hpop','nodrag'):
            if m['satellite'][source][label]['fullDay']['count']!=8641 or m['satellite'][source][label]['missileWindow']['count']!=74:
                raise ValueError('卫星范围不完整')
    for key in ('eciP','eciV','ecefP','ecefV'):
        if m['originalCurrentTrajectory'][key]['norm']['max']!=0: raise ValueError('目标双跑差异')
    for key in ('eciP','eciV'):
        if m['originalCurrentSatellite'][key]['norm']['max']!=0: raise ValueError('卫星双跑差异')
    for value in m['windows'].values():
        if not value['exactPhysicalJsonEqual'] or not value['comparison']['equal'] or value['absoluteStkAccess']!='NOT_TESTED_MISSING_STK_ACCESS':
            raise ValueError('窗口一致性/真值状态不符')
        if value['arcs']['original']!=value['arcs']['current']: raise ValueError('弧段摘要不同')
    print(json.dumps(dict(status='PASS_READ_ONLY_DELIVERY',candidateFiles=len(candidate['files']),sourceFiles=635,
         commandsChecked=len(commands),rawRows=[7371,8641,8641],runtimeRequired=False,jarClassesRequired=False,
         scope='交付SHA/原流/参考质量/已存摘要内部一致性；未重算物理',stkAccess='NOT_TESTED_MISSING_STK_ACCESS'),ensure_ascii=False))


if __name__=='__main__':
    main()
