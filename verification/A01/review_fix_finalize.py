"""A01修复报告与独立可复算自查；只读原证据/产品，更新本项当前报告元数据。"""
from pathlib import Path
from collections import Counter
from decimal import Decimal
import csv, hashlib, json, math, re, socket, statistics, sys
import acceptance_final as candidate
from performance import digest,percentile_ms

ROOT=candidate.ROOT;HERE=ROOT/'verification/A01';OUT=candidate.OUT;FIX=HERE/'review-fix/downlink-v4'
def read(path):return json.loads(path.read_text(encoding='utf-8'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def link(path,label=None):return '['+(label or path.name)+']('+str(path).replace('\\','/')+')'

def fix_report():
    p=read(FIX/'backend-performance.json');s=p['scenarios'][0];shutdown=read(FIX/'server-downlink-review-v4-shutdown.json')
    resource=read(FIX/'performance-resource-summary.json');prep=read(FIX/'performance-preparation.json')
    history=read(HERE/'review-fix/history/preserved-manifest.json')
    changed=['acceptance.py','acceptance_final.py','acceptance_shared.py','performance.py','report.py','collect_browser.py','final/navigation-browser-flow.js','review_downlink.py','test_review_fixes.py','review_fix_setup.py','review_fix_finalize.py','测试报告.md','验收用例矩阵.json','final/report-generation.json','final/final-selfcheck.json','final/backend-performance-current.json','final/performance-conditions-current.json','final/frontend-performance-cache-context.json']
    report=['# A01 独立审查修复报告\n',
        '本轮按唯一修复合同处理 I01、I02、M01；本机定向证据结论 **DONE_WITH_CONCERNS**，供根代理原独审重审。A02/A03未开始；本代理未执行Git、台账或远端操作。产品源码、数值模型、Java8/Boot2.7.18/Orekit12.0/锁、正式JAR和黄金未修改。未重跑其他18性能场景、完整347/351/112或整套HTTP/Chrome。\n',
        '## I01 非空下传\n',
        '原R2从/stations取DEMO-STATION，60响应148B、窗口及覆盖均空，作为历史规格缺口保留。原R2主JSON/stdout/CSV/jstat/资源摘要/退出记录原字节未动；历史报告、矩阵、自查、report-generation、两验收源入口、performance/report和导航脚本已先保存原字节及SHA。原报告SHA `'+next(x['sha256'] for x in history if x['path'].endswith('/测试报告.md'))+'`；原矩阵SHA `'+next(x['sha256'] for x in history if x['path'].endswith('/验收用例矩阵.json'))+'`。\n'+link(HERE/'review-fix/history/preserved-manifest.json','历史字节保存清单')+'。\n',
        '实际输入直接取最终真实Chrome space Result中的downlink请求及期望响应，绑定browser-summary、CLI原stdout和runtime Result SHA；本轮GET stations/satellites真实定位T04-STATION（基准星下站）和卫星1/3（基准观测星/基准共同观测星），验证KEPLER_6及全部影响传播的根数和历元（星3平近点角2°）。同日UTC2026-07-01T10:00~10:10、coarse60/fine1、保护0/间隙0、两卫星/一站/两链路，未降采样、降精度或截断。服务复用A01已关闭的非生产server-fixture同H2/files，T27来源SHA和当前库启动前SHA见data-origin，数据库运行数据未当源码提交。\n',
        f"最终新服务名server-downlink-review-v4，PID{p['pid']}，独立runtime根review-fix-downlink-v4；JAR SHA `{p['jarSha256']}`。两预热、串行10、并发1/2/4/6各12，共60下传响应均HTTP200/593B；2 satelliteStationWindows、2实际windows、1 coverageWindow，链路441/407秒、覆盖441秒，skippedLinks0。非空和每窗口正时长都有硬断言；完整结构/数值/数组顺序按Decimal严格一致，不加容差。441.0和Chrome441是JSON数值书写差异，指纹按精确Decimal规范化；定向测试证明441.0000001仍不同。所有重复响应完整原字节保留，统一responseSHA `{s['warmup'][0]['responseSha256']}`、numericSHA `{s['warmup'][0]['numericSha256']}`。\n",
        '| 下传组 | N | 有效/错误 | p50 ms | p95 ms | p99 ms | 组墙时s | 实际CPU增量s |\n|---|---:|---|---:|---:|---:|---:|---:|']
    for g in s['groups']:report.append(f"| {g['name']} c{g['concurrency']} | {g['samples']} | {g['validSamples']}/{g['errors']} | {g['p50Ms']:.6f} | {g['p95Ms']:.6f} | {g['p99Ms']:.6f} | {g['wallSec']:.6f} | {g['cpuAfterSec']-g['cpuBeforeSec']:.6f} |")
    report += [f"\n2个真实准备GET请求墙时{prep['wallSec']:.6f}s/CPU增量{prep['cpuDeltaSec']:.6f}s；完整请求阶段墙时{p['requestsWallSec']:.6f}s/CPU增量{p['cpuDeltaSec']:.6f}s。各组CPU来自精确PID Windows GetProcessTimes内核+用户累计秒，不能称纯数学CPU；HTTP延迟至最后字节完整读取，组墙时/RPS含Decimal检查。250ms真实采样{resource['processSampling']['samples']}行，间隔{resource['processSampling']['actualMinMs']}~{resource['processSampling']['actualMaxMs']}ms、中位{resource['processSampling']['actualMedianMs']}ms；jstat1s实际{resource['jstatSamples']}行。采样峰工作集{p['resources']['maxWorkingSetBytes']}B、PrivateBytes {p['resources']['maxPrivateBytes']}B、线程{p['resources']['maxThreads']}，近似堆峰{resource['peakHeapUsedBytes']}B，单位/初末/GC原行可复算；短轮次不能提供稳健GC或容量结论。\n",
        f"新JVM自然exit{shutdown['naturalExitCode']}，context.close/main返回，无剩余非daemon，18140复绑且活JAR SHA保持。进程采样器exit0；jstat测量结束明确terminate/exit1，保留真实关闭方式，不混同JVM自然退出。关闭仍{len(shutdown['warnLines'])}条space-visibility worker潜在线程泄漏WARN，原行保留，不声明长期无泄漏/热卸载通过。\n",
        link(FIX/'backend-performance.json','60样本/时段/CPU/完整数值指纹')+'；'+link(FIX/'performance-preparation.json','2准备请求/原响应SHA/身份')+'；'+link(FIX/'data-origin.json','同库来源/JAR')+'；'+link(FIX/'performance-resources.csv','实际250ms进程资源')+'；'+link(FIX/'performance-jstat-gc.txt','实际jstat1s')+'；'+link(FIX/'performance-resource-summary.json','独立资源复算')+'；'+link(FIX/'monitor-exits.json','采样器退出')+'；'+link(FIX/'server-downlink-review-v4-shutdown.json','精确PID自然关闭')+'。\n',
        '### 实际阶段与失败处分\n\n| 阶段 | 实际准备 | 实际下传 | 退出/处理 | 当前指标 |\n|---|---:|---:|---|---|\n| review-downlink | 2 GET原响应 | 0 | exit1：误以双星所有根数同值，星3真实平近点角2°；准备拒绝，自然关闭0 | 排除 |\n| review-downlink-v2 | 2 | 60，200/593B | exit1：441.0与441的指纹表示差异，非产品数值失败；全部原响应/误判/自然关闭0保留 | 排除 |\n| review-downlink-v3 | 2 | 60，全部有效 | exit0/自然关闭0；已有真实采样，但未直接绑定准备/各组CPU，v4新增该合同证据 | 排除重复 |\n| review-downlink-v4 | 2 | 60，全部有效 | exit0/自然关闭0，阶段CPU/资源齐全 | 当前新下传来源 |\n',
        '四阶段本轮共188实际HTTP（2+62+62+62），绝不把这些重复/失败累加为当前有效覆盖。原R2实际2201仍完整；当前只取其18有效场景，排除旧58测量/2热身后纳入v4新58/2。当前19场景/95组/2158测量/38热身，5原准备+2新准备，覆盖计数2203。原R2和v4资源各自绑定独立时段/PID/JAR/SHA，无跨阶段墙时/CPU/资源拼接；原R2资源包含原空下传实际活动，未凭估计扣减。每场景出处和新旧SHA见'+link(OUT/'backend-performance-current.json','18+1当前来源汇总')+'。\n',
        '## I02 两候选驱动归并\n',
        'acceptance_shared.py仅一份execute/prepare/build/numeric/package/original；两个轻入口各持有独立Acceptance实例，显式配置初OUT/RUN/CLEAN、347运行346通过，以及最终路径351运行350通过、允许的3个productDelta；兼容其他入口按A01_FINAL导入常量/绑定函数，导入两个入口不共享可变全局路径。无框架或额外依赖。已有源码副本/正式包拒绝覆盖；execute拒绝覆写历史stdout；numeric黄金保护局限于capture且异常后恢复copy2，避免不同模式污染。\n',
        '实际8组定向检查通过：两个入口/路径/计数/三delta及同一实现；非法阶段拒绝；隔离临时输出原字节exit/SHA及prepare/package保护；读既有真实XML/stdout验证347/351与346/350并捕获build命令不执行；黄金相同不写/不同拒绝/异常恢复；既有15/9/21原Decimal/字节对照无差异；359件包/1432源SHA及所有产品源码/锁不变；下传非空/时长/规模/精确数值语义。仅验证既有产物，不将其表述为重跑业务。\n'+link(OUT/'review-driver-checks.txt','8组原stdout')+'；'+link(OUT/'review-driver-checks-exit.json','实际命令/exit0/原SHA')+'。\n',
        '## M01 缓存文案\n',
        'final/navigation-browser-flow.js明确cacheState=not-controlled，首次及随后导航未控制/清理缓存且前有功能流程；collect_browser.py未来收集同口径。当前原frontend-performance.json/browser-summary/Chrome stdout不改，以新frontend-performance-cache-context.json绑定原SHA说明，不改变任何TTFB/FCP/LCP值，也未重跑Chrome。\n'+link(OUT/'frontend-performance-cache-context.json','未控制缓存补充元数据')+'。\n',
        '## 当前报告、命令及验证\n',
        '详细测试报告与当前矩阵已更新，894唯一项：884PASS、5历史FAIL（4原工具条目+1已修复空下传规格缺口）、4BLOCKED、1SKIP。8个新驱动检查+1新自然关闭追加，原性能下传5组替换为v4来源，未把历史已失败的工具日志改成成功。旧报告/矩阵/自查/report-generation源SHA由history清单维持。\n',
        '| 实际命令 | exit | 原stdout/退出证据 |\n|---|---:|---|']
    for name in ['review-downlink','review-downlink-v2','review-downlink-v3','review-downlink-v4','review-driver-checks','review-compile-final','review-driver-checks-final','review-node-syntax','review-report-generation','review-report-generation-v2','review-selfcheck']:
        exit_path=OUT/(name+'-exit.json')
        if not exit_path.exists():continue
        record=read(exit_path)
        report.append('| `'+json.dumps(record['command'],ensure_ascii=False).replace('|','\\|')+'` | '+str(record['exitCode'])+' | '+link(OUT/(name+'.txt'),name+'原stdout')+' / '+link(exit_path,'命令/cwd/duration/SHA')+' |')
    report += ['\n'+link(HERE/'测试报告.md','更新详细报告')+'；'+link(HERE/'验收用例矩阵.json','当前894项矩阵')+'；'+link(OUT/'final-selfcheck.json','修复最终自查/数值指纹/资源/来源/端口/SHA')+'。\n',
        '## 修改文件与剩余边界\n',
        '\n'.join('- '+link(HERE/name,name) for name in changed)+'\n',
        '新增downlink-v4等文件只属本项隔离验收证据，原响应与数据库/JAR留runtime；历史失败证据不删除。四外部MySQL/DM/MinIO/Nginx仍未测BLOCKED；Windows符号链接权限仍SKIP。真实日志WARN、最高6并发、N10/N12及极短资源样本、无SLA、未测长期稳定性/容量/热卸载等边界保留。DONE_WITH_CONCERNS仅本机有限范围，最终独审、Git/台账/远端由根处理。\n']
    (HERE/'独立审查修复报告.md').write_text('\n'.join(report)+'\n',encoding='utf-8',newline='\n')

def selfcheck():
    history=read(HERE/'review-fix/history/preserved-manifest.json');preserved=[]
    for record in history:
        target=ROOT/record.get('preservedPath',record['path']);assert sha(target)==record['sha256'],target
        preserved.append(record)
    old=read(HERE/'review-fix/history/final/final-selfcheck.json')
    assert old['reportSha256']==sha(HERE/'review-fix/history/测试报告.md')
    assert old['matrixSha256']==sha(HERE/'review-fix/history/验收用例矩阵.json')
    current=read(OUT/'backend-performance-current.json');assert len(current['sources'])==19
    groups=0;measured=0;warmup=0
    for source in current['sources']:
        assert sha(ROOT/source['path'])==source['sha256']
        s=next(x for x in read(ROOT/source['path'])['scenarios'] if x['name']==source['scene'])
        warmup+=len(s['warmup']);assert all(x['ok'] for x in s['warmup'])
        for g in s['groups']:
            groups+=1;measured+=len(g['requests']);assert all(x['ok'] and x['status'] in (200,201) for x in g['requests'])
            values=[x['ms'] for x in g['requests']]
            for key,p in [('p50Ms',.5),('p95Ms',.95),('p99Ms',.99)]:assert g[key]==percentile_ms(values,p)
            assert g['validSamples']==len(values) and g['samples']==len(values) and g['errors']==0
            assert g['rps']==len(values)/g['wallSec']
    assert (groups,measured,warmup)==(95,2158,38)
    perf=read(FIX/'backend-performance.json');scenario=perf['scenarios'][0]
    browser=read(OUT/'browser-summary.json');event=next(x for x in browser['space']['result']['evidence'] if x['name']=='downlink')
    expected=json.loads(json.dumps(event['response']),parse_float=Decimal);responses=scenario['warmup']+[x for g in scenario['groups'] for x in g['requests']]
    assert len(responses)==60 and len(perf['preparation'])==2
    for row in responses:
        path=ROOT/row['responsePath'];raw=path.read_bytes()
        assert len(raw)==593==row['bytes'] and sha(path)==row['responseSha256']
        assert json.loads(raw,parse_float=Decimal)==expected,'沿原Decimal结构/数组顺序精确比较Chrome实际响应'
        fingerprint,dimensions=digest(raw,'downlink');assert fingerprint==row['numericSha256'] and dimensions==row['dimensions']
    condition=ROOT/'verification/runtime/A01/review-fix-downlink-v4/performance-downlink-review-v4/space-downlink-request.json'
    assert read(condition)['body']==event['request']
    for request in perf['preparation']:assert sha(Path(request['responsePath']))==request['responseSha256']
    csv_rows=list(csv.DictReader((FIX/'performance-resources.csv').open(encoding='utf-8-sig')))
    resource=read(FIX/'performance-resource-summary.json');intervals=[int(b['timeEpochMs'])-int(a['timeEpochMs']) for a,b in zip(csv_rows,csv_rows[1:])]
    assert len(csv_rows)==resource['processSampling']['samples']
    assert (min(intervals),max(intervals),statistics.median(intervals))==(resource['processSampling']['actualMinMs'],resource['processSampling']['actualMaxMs'],resource['processSampling']['actualMedianMs'])
    for key,column in [('maxWorkingSetBytes','workingSetBytes'),('maxPrivateBytes','privateBytes'),('maxThreads','threads')]:assert perf['resources'][key]==max(int(row[column]) for row in csv_rows)
    gc_lines=[x.split() for x in (FIX/'performance-jstat-gc.txt').read_text().splitlines() if x.strip()];gc=[dict(zip(gc_lines[0],map(float,x))) for x in gc_lines[1:]]
    assert len(gc)==resource['jstatSamples']
    assert resource['peakHeapUsedBytes']==max(sum(s[k] for k in ('S0U','S1U','EU','OU'))*1024 for s in gc)
    assert resource['peakHeapCommittedBytes']==max(sum(s[k] for k in ('S0C','S1C','EC','OC'))*1024 for s in gc)
    assert resource['youngGcDelta']==gc[-1]['YGC']-gc[0]['YGC'] and resource['fullGcDelta']==gc[-1]['FGC']-gc[0]['FGC']
    assert perf['cpuDeltaSec']==perf['cpuAfterSec']-perf['cpuBeforeSec']
    for g in scenario['groups']:assert g['cpuAfterSec']>=g['cpuBeforeSec']
    stops=read(FIX/'server-downlink-review-v4-shutdown.json');assert stops['naturalExitCode']==0 and stops['contextCloseReturned'] and stops['mainReturned'] and not stops['nonDaemonRemaining'] and stops['portRebind'] and stops['jarUnchanged']
    assert sha(candidate.PACKAGE/'backend/server.jar')==perf['jarSha256']=='d9c9956c389e79d1d52619c3e8310fb4b887d2009ad8f4c226d597cc030e3c71'
    manifest=read(OUT/'clean-source-manifest.json')
    for item in manifest['files']:
        assert sha(candidate.CLEAN/item['path'])==item['sha256']
        if item['path'].startswith(('backend/','frontend/','deploy/','test-data/','scripts/')):assert sha(ROOT/item['path'])==item['sha256']
    matrix=read(HERE/'验收用例矩阵.json');cases=matrix['cases'];assert len(cases)==894 and len({x['id'] for x in cases})==894
    counts=dict(Counter(x['status'] for x in cases));assert counts==matrix['counts']==dict(PASS=884,FAIL=5,BLOCKED=4,SKIP=1)
    metadata=read(OUT/'report-generation.json');assert metadata['reportSha256']==sha(HERE/'测试报告.md') and metadata['matrixSha256']==sha(HERE/'验收用例矩阵.json')
    links=[]
    for file in [HERE/'测试报告.md',HERE/'独立审查修复报告.md']:
        for target in re.findall(r'\]\(([^)]+)\)',file.read_text(encoding='utf-8')):
            assert Path(target).exists(),target;links.append(target)
    source_files=['acceptance.py','acceptance_final.py','acceptance_shared.py','performance.py','report.py','collect_browser.py','final/navigation-browser-flow.js','review_downlink.py','test_review_fixes.py','review_fix_setup.py','review_fix_finalize.py']
    source_records=[]
    for name in source_files:
        path=HERE/name;raw=path.read_bytes();raw.decode('utf-8');assert b'\r' not in raw,path
        source_records.append(dict(path=path.relative_to(ROOT).as_posix(),sha256=sha(path),utf8Lf=True))
    for port in [18140,15140]:
        with socket.socket() as stream:stream.bind(('127.0.0.1',port))
    evidence=[]
    for path in sorted(FIX.glob('*')):
        if path.is_file():evidence.append(dict(path=path.relative_to(ROOT).as_posix(),sha256=sha(path)))
    write(OUT/'final-selfcheck.json',dict(status='PASS',scope='A01 I01/I02/M01修复后定向自查，非重新业务测试',reportSha256=sha(HERE/'测试报告.md'),matrixSha256=sha(HERE/'验收用例矩阵.json'),repairReportSha256=sha(HERE/'独立审查修复报告.md'),matrixCases=len(cases),matrixCounts=counts,linksChecked=len(links),missingLinks=[],performanceGroupsRecomputed=groups,performanceMeasured=measured,warmup=warmup,downlinkActualResponsesCompared=60,downlinkPrepareResponsesCompared=2,decimalStrictStructureAndOrder=True,resourceSamplesRecomputed=len(csv_rows),jstatSamplesRecomputed=len(gc),sourceFilesChecked=len(manifest['files']),jarSha256=perf['jarSha256'],sourceUtf8LfPass=True,sourceRecords=source_records,preservedHistory=preserved,downlinkEvidence=evidence,servicePortsRebound=[18140,15140],cacheState='not-controlled',limitations='四外部BLOCKED/1OS SKIP/WARN/无SLA/有限并发与短资源样本；原R2保持原字节，当前18+1资源按独立阶段绑定'))
    print('PASS repair selfcheck: 894 unique cases; 95 groups/2158 measured/38 warmup; 60 exact Decimal responses; resource/GC/SHA/history/links/ports',flush=True)

if __name__=='__main__':
    if sys.argv[1]=='report':fix_report()
    elif sys.argv[1]=='selfcheck':selfcheck()
    else:raise SystemExit('report/selfcheck')
