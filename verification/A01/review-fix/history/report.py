"""A01中文报告/可检查矩阵生成：只读取真实本轮证据，独立统计nearest-rank并拒绝未关闭服务/缺失证据。"""
from pathlib import Path
from collections import Counter
from datetime import datetime,timezone,timedelta
import csv,hashlib,json,re,sys,xml.etree.ElementTree as ET
from acceptance_final import ROOT,OUT,RUN,PACKAGE,CLEAN,sha
from performance import validate_matrix,percentile_ms

def load(name):return json.loads((OUT/(name+'.json')).read_text(encoding='utf-8'))
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def link(path,label=None):return '['+(label or str(path))+']('+str(path).replace('\\','/')+')'
def ms(value):return f'{value:.2f}'

def main():
    tests=load('test-summary');numeric=load('numeric-summary');package=load('package-bindings');abi=load('legacy-abi-compare');http=load('http-extended-summary');browser=load('browser-summary');perf=load('backend-performance');front=load('frontend-performance');resource=load('performance-resource-summary');shutdown=load('server-performance-r2-shutdown');failure=load('resource-failure-summary');source=load('clean-source-manifest');classes=load('class-delta')
    assert perf['complete'] and http['passed'] and not http['unresolvedFailures'] and shutdown['naturalExitCode']==0 and shutdown['portRebind'] and shutdown['jarUnchanged']
    assert package['jarSha256']==sha(PACKAGE/'backend/server.jar')==perf['jarSha256']
    rows=[r for s in perf['scenarios'] for g in s['groups'] for r in g['requests']];warm=[r for s in perf['scenarios'] for r in s['warmup']]
    assert len(rows)==2158 and all(r['ok'] for r in rows) and len(warm)==38 and all(r['ok'] for r in warm)
    matrix=[]
    def add(id,module,input,expected,actual,status='PASS',evidence=None,**extra):matrix.append(dict(id=id,module=module,input=input,expected=expected,actual=actual,status=status,evidence=evidence or [],**extra))
    add('BUILD-01','构建','实际工作文件1432份，产品相对初候选3源delta','Java8/Spring2.7.18/Orekit12.0/锁冻结；API与dist独立','351后端、112前端、类型和生产构建exit0',evidence=['verification/A01/final/build-all.txt','verification/A01/final/build-all-exit.json'])
    # XML逐真实case保存；1项OS符号链接权限跳过不得转为通过。
    for ordinal,(p,case) in enumerate([(p,c) for p in sorted((RUN/'surefire-reports').glob('TEST-*.xml')) for c in ET.parse(p).getroot().findall('testcase')],1):
        skipped=case.find('skipped');status='SKIP' if skipped is not None else 'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'PASS'
        add(f'UNIT-BE-{ordinal:03}','后端单元/集成',case.attrib,'JUnit真实断言通过',skipped.attrib if skipped is not None else {'durationSec':case.attrib.get('time')},status,evidence=['verification/runtime/A01/final-candidate/surefire-reports/'+p.name])
    text=re.sub(r'\x1b\[[0-9;]*m','',(OUT/'build-all.txt').read_text(encoding='utf-8',errors='replace'))
    files=re.findall(r'[✓✔]\s+(src/[^\r\n]*?)\s+\((\d+) tests?\)',text)
    assert re.search(r'Test Files\s+27 passed',text) and re.search(r'Tests\s+112 passed',text)
    # 本轮Vitest只输出27/112总计；按真实默认glob列27源spec，逐文件数量未知就保持null，不能编造112个case名。
    if not files:files=[(p.relative_to(CLEAN/'frontend').as_posix(),None) for p in sorted((CLEAN/'frontend/src').rglob('*.spec.ts'))]
    assert len(files)==27
    for i,(name,count) in enumerate(files,1):add(f'UNIT-FE-{i:02}','前端Vitest文件组',name,'本轮默认glob完整suite通过；总数以真实27文件112测试汇总为准','本轮suite全通过；stdout无逐文件数量',evidence=['verification/A01/final/build-all.txt'],actualTestCount=int(count) if count is not None else None,countGranularity='只实测汇总27/112，逐文件case数未输出')
    for label,expected,actual in [('ballistic',ROOT/'test-data/baseline/ballistic',RUN/'ballistic-actual'),('space9',ROOT/'test-data/baseline/space',RUN/'space-actual'),('space21',RUN/'complete21-original-actual',RUN/'complete21-current-actual')]:
        for i,p in enumerate(sorted(actual.rglob('*')),1):
            if not p.is_file():continue
            relative=p.relative_to(actual);before=expected/relative
            add(f'NUM-{label}-{i:02}','原数值严格对照',str(relative),'JSON Decimal结构/数值/数组顺序相等；CSV/报告逐字节',{'expectedSha256':sha(before),'actualSha256':sha(p),'comparison':'JSON-Decimal' if p.suffix=='.json' else 'byte-exact'},evidence=['verification/A01/final/numeric-summary.json',str(p.relative_to(ROOT))])
    add('ABI-01','Java兼容','只读原class与本轮最终classes','原50类型1174公开声明不缺失',abi,evidence=['verification/A01/final/legacy-abi-compare.json'])
    add('PLAIN-01','普通Java工具','纯JDK弹道领域源码+原黄金','Java8独立编译运行、不带Spring/Orekit','exit0，原数值对照通过',evidence=['verification/A01/plain-ballistic.txt','verification/A01/plain-ballistic-exit.json'])
    add('PLAIN-02','普通Java工具','空间领域源码+Orekit/Hipparchus/日志','Java8独立编译运行，无Spring/MyBatis/JUnit','exit0',evidence=['verification/A01/plain-space.txt','verification/A01/plain-space-exit.json'])
    add('ORIGINAL-01','原目录只读','526空间+171弹道原文件','697 SHA不变','697/697一致',evidence=['verification/A01/original-directory-check.json'])
    event_no=check_no=0
    for phase,p in http['phases'].items():
        for event in p['events']:
            event_no+=1;add(f'HTTP-{event_no:03}','实际HTTP',dict(phase=phase,name=event['name'],method=event['method'],path=event['path']),{'status':event.get('expected','原完整示例已断言')},{'status':event.get('status'),'bytes':event.get('bytes'),'responseSha256':event.get('sha256'),'elapsedMs':event.get('elapsedMs')},'PASS' if event.get('passed') else 'FAIL',evidence=['verification/A01/final/http-extended-summary.json'],scope='历史工具预期错误' if not event.get('passed') else '最终验收',route=event.get('route'))
        for check in p['checks']:
            check_no+=1;add(f'HTTP-CHECK-{check_no:03}','HTTP业务检查',dict(phase=phase,name=check['name']),'检查真实业务断言',check,check['status'],evidence=['verification/A01/final/http-extended-summary.json'],scope='历史工具失败已修正' if check['status']=='FAIL' else '最终验收')
    assert event_no==247 and check_no==95
    descriptions={
      'space':'双星fixture；SPACE/GROUND保存切换；站星下传；真实窗口选择/校验；目标筛选；单星/双星共同观测；选择持久化；明细/中文备注/清空刷新/删除；coarse0.005/fine0.001真实取消到CANCELLED/历史/409',
      'window':'原生CSV chooser×2；双目标/双星/四标准载荷；三偏移；全部弧段真实轨迹；原CSV/原JSON下载',
      'window-edge':'点0/1、单偏移、不同双星根数、0步长本地阻断无HTTP、180度太阳约束真实全空、草稿保持',
      'fit':'原生102 JSON；双目标1633/31全CSV四图/各4原点；真实分页/CSV及JSON下载；告警/过期快照',
      'fit-edge':'非法JSON/17位非引号时间码无HTTP；null任务名/两观测成功；单观测/非法位置原400',
      'fit-aux-failure':'精确本次拥有CSV临时移走；真实preview/download404；另一目标和成功曲线保持；无假CSV下载',
      'fit-aux-recovery':'恢复同CSV，只重新登记/读取，未重拟合；真实201/200及原CSV下载',
      'simulation':'原生JSON；2576全点四图；21列分页/原CSV和JSON下载；冻结提交快照；同日六月轨道引用回窗',
      'retime':'原生CSV；默认2576全点四图；22列分页/原下载；模拟及自身生成引用再次重定时；七月同日星历回窗',
      'retime-edge':'3行重排列/非整秒/唯一原点；未知文本保留；非法UTC/不支持表头400→原生上传恢复',
      'navigation':'真实Chrome五次独立page.goto；TTFB/DCL/load/FCP/LCP、资源字节、可用JS堆及6页tab首次可见',
      'large':'31偏移/13062完整真实点/4026832字节；0/15/30偏移切换；原样轨迹绘制；原响应真实download'}
    for i,(name,description) in enumerate(descriptions.items(),1):add(f'UI-{i:02}','真实Chrome',description,'真实UI/HTTP/文件事件通过，无mock或注入结果',browser[name]['result'],evidence=['verification/A01/final/browser-summary.json',f'verification/A01/final/{name}-browser-flow.js'])
    for i,s in enumerate(perf['scenarios'],1):
        for j,g in enumerate(s['groups'],1):
            values=[r['ms'] for r in g['requests'] if r['ok']];assert all(g[key]==percentile_ms(values,p) for key,p in [('p50Ms',.5),('p95Ms',.95),('p99Ms',.99)])
            add(f'PERF-{i:02}-{j}','后端性能',dict(scene=s['name'],kind=s['kind'],scale=s['scale'],concurrency=g['concurrency'],samples=g['samples']),'完整HTTP/数值一致；无用户SLA，仅测量',dict(valid=g['validSamples'],errors=g['errors'],p50Ms=g['p50Ms'],p95Ms=g['p95Ms'],p99Ms=g['p99Ms'],wallSec=g['wallSec'],rps=g['rps']),evidence=['verification/A01/final/backend-performance.json'])
    add('RESOURCE-01','故障与隔离','6路成功/400/preview/download混合；6同名拟合；2真实TCP RST','失败不串扰；12CSV路径唯一且原黄金；完整恢复',failure,evidence=['verification/A01/final/resource-failure-summary.json'])
    add('RESOURCE-02','文件生命周期','TTL3000ms/调度500ms；16777220B完整下载保持租约','跨TTL实体在；完整消费原字节；自然回收404；恢复上传','实际5请求PASS',evidence=['verification/A01/final/http-lifecycle.txt','verification/A01/final/http-extended-summary.json'])
    add('RESOURCE-03','最终自然关闭','正式JAR/精确PID21984','context.close/main返回、exit0、无剩余非daemon、18140可复绑、JAR不变',shutdown,evidence=['verification/A01/final/server-performance-r2-shutdown.json','verification/A01/final/server-performance-r2-start-stop.txt'])
    for name,reason in [('MySQL','未提供可授权隔离实例；Docker引擎连接失败，PATH无mysql'),('DM','未提供可授权隔离实例'),('MinIO','未提供可授权隔离实例；现有路径/文件契约可测试，不代称真实外部存储'),('Nginx','PATH无nginx；Node标准库网关只属于本机B/S验收')]:add('ENV-'+name,'外部实际环境',name,'该产品实际外部部署/连接验收',reason,'BLOCKED',evidence=['verification/A01/docker-info.txt','verification/A01/environment.txt'])
    validate_matrix(matrix);counts=dict(Counter(r['status'] for r in matrix));write(ROOT/'verification/A01/验收用例矩阵.json',dict(schemaVersion=1,scope='A01最终本机验收+明确标识历史工具失败；原首候选产品失败见独立摘要',sourceBase='030e73d1887cb6cde11e784120a0ee6b5caae103',jarSha256=package['jarSha256'],counts=counts,cases=matrix,routeMatrix=http['routeMatrix'],historicalFailures=http['failures'],unresolvedFinalFailures=http['unresolvedFailures']))
    # 可提交请求条件只保留来源SHA，1.5MB multipart原文/实际响应/CSV在ignored运行根完整保存。
    conditions=[]
    for p in sorted((RUN/'performance-r2').glob('*-request.json')):
        value=json.loads(p.read_text(encoding='utf-8'));original=value
        if isinstance(value.get('body'),str) and value.get('kind')=='upload':value['body']={'source':'test-data/reference/ballistic/trajectory-0.1s-standard.csv','sha256':sha(ROOT/'test-data/reference/ballistic/trajectory-0.1s-standard.csv'),'bytes':1507045,'multipartBoundary':'A01-performance-isolated'}
        conditions.append(value|{'rawRequestConditionSha256':sha(p),'rawConditionPath':str(p.relative_to(ROOT))})
    write(OUT/'performance-conditions.json',conditions)
    report=[];emit=report.append
    emit('# A01 全面前后端功能与性能测试报告\n')
    emit('本机可执行范围的最终候选验收通过；结论为 **DONE_WITH_CONCERNS**。真实备注 null 清空缺陷已修复并重新验证；外部 MySQL/DM/MinIO/Nginx 未提供可用隔离环境，明确 BLOCKED；后端一项符号链接用例因 Windows 权限 SKIP。用户未提供 SLA，本报告只给实测，不自设性能合格线。\n')
    emit('## 1. 版本、来源与执行边界\n')
    emit(f"基准为 `030e73d1887cb6cde11e784120a0ee6b5caae103`，最终从实际工作文件按冻结清单新复制 **1432** 项，不使用只含已提交对象的 Git archive。相对本轮初候选，产品只有三个修复源文件；287/288 主体 class 逐字节不变，仅 `VisibilityTaskResultService.class` 因备注修复改变。两原工程只读，重新核验 **526+171=697** 个原文件 SHA，无差异；本轮所有新构建、数据、端口与证据位于 A01，未覆写任何 Txx。\n\n最终 JAR SHA256：`{package['jarSha256']}`。正式包359文件、288类、349个Orekit数据文件、5个dist文件；359件manifest均逐文件匹配，API JAR不夹带static。前端锁 SHA256：`{package['lockSha256']}`。Java8/Spring Boot2.7.18/Orekit12.0/锁和原传播模型、步长、CSV/HTTP契约保持。")
    emit('\n\n'+link(OUT/'clean-source-manifest.json','最终源码及三处delta')+'；'+link(OUT/'class-delta.json','288类同字节对照')+'；'+link(OUT/'package-bindings.json','正式包绑定')+'；'+link(ROOT/'verification/A01/original-directory-check.json','原697文件只读核验')+'。\n')
    emit('验收后的 Git 证据行尾属性不参与 JAR 构建；新增报告、测试脚本、指标和截图独立属于 A01 证据。测试时冻结源副本自身的1432 SHA匹配不因后续证据属性变化而重写。\n')
    emit('### 本机环境\n\n| 项目 | 实测/配置 |\n|---|---|\n| 系统 | Windows 11 Pro 10.0.26200 |\n| CPU | i5-14600KF，14物理核/20逻辑处理器 |\n| RAM | 初始TotalVisibleMemorySize 33337620KiB，FreePhysicalMemory 10742344KiB；只代表采集时 |\n| JDK | Eclipse Adoptium 1.8.0_472 |\n| Maven/Node/pnpm | 3.9.16 / 24.16.0 / 11.21.0 |\n| 浏览器 | 真实Chrome '+front['navigation']['browser']+'，Asia/Shanghai |\n| API/前端 | 18140 / 15140；前端为正式dist+Node标准库静态验收网关 |\n| 性能 JVM | 明确-Xms256m -Xmx4g；无强制GC |\n| 存储 | 本轮隔离H2 MODE=MySQL；它不是MySQL实测 |\n')
    emit('fresh demo 阶段真正新建 H2；空间双星与Chrome阶段复制既有T27非生产fixture并保存来源SHA，demo仅补缺项。后续同库/文件真实重启与R2性能明确复用该A01隔离数据，未将其称为fresh demo。模拟六月与重定时七月引用回窗使用对应实际同日星历，没有从默认七月数值积分倒推数周。外部Docker info实际exit1：dockerDesktopLinuxEngine命名管道不存在；PATH无mysql/nginx，未启动或连接未知生产实例。\n')
    emit('## 2. 构建、单元与严格原值回归\n\n| 范围 | 本轮实际结果 | 状态/证据 |\n|---|---:|---|\n| 初候选完整后端 | 347运行，346通过，1跳过 | 初次新build exit0 |\n| 修复后最终完整后端 | 351运行，350通过，0FAIL/0ERROR，1跳过 | final/build-all.txt及XML |\n| 最终前端 | 27文件112测试通过 | 新pnpm frozen-lock安装/test |\n| 类型/正式dist | vue-tsc -b && vite build exit0 | 新生产构建 |\n| 弹道 | 15件实际产物严格相同 | 10计算场景+条件元数据等15文件 |\n| 空间基本 | 9件实际输入/输出产物严格相同 | 包含请求，不能冒称9独立业务测试 |\n| 完整空间 | 21件本輪旧class/当前class产物严格相同 | 两边分别新编译驱动、新运行 |\n| Java ABI | 50公开类型1174声明/描述符无缺失 | 本轮新javap-public-s |\n| 普通Java工具 | 弹道纯JDK与空间独立类路径均exit0 | 不带Spring/MyBatis/JUnit |\n')
    emit('15/9/21是冻结对照文件数，不与JUnit351或HTTP请求数相加冒充独立测试数。JSON沿原比较器按Decimal、字段结构和数组顺序严格相等；CSV及文本报告逐字节，未加容差、截断或降采样。弹道15件包含conditions.json、7窗口JSON、fit/simulate/retime三个结果JSON及四份CSV。1个SKIP为 `BallisticFileStorageTest.结果边界拒绝外部文件及登记后替换的符号链接`：实际OS错误“客户端没有所需的特权”，不是通过。\n\n'+link(OUT/'numeric-summary.json','新15/9/21原值对照')+'；'+link(OUT/'test-summary.json','测试统计')+'；'+link(OUT/'legacy-abi-compare.json','新ABI来源/类型/描述符')+'。根另外直接读取实际XML/构建stdout/退出码复核上述数量，并独立复算95性能组与1432源码SHA；这些复核不叠加为新业务测试数。\n')
    emit('## 3. 35活动路由与完整HTTP业务\n')
    emit(f"最终候选保存 **{http['totalRequests']} 个实际HTTP请求、{http['assertedChecks']} 个分组检查**，35/35路由成功路径覆盖，25个路由有错误/边界路径。包括新重跑的原68请求示例及扩展：标准载荷十字段/null/0/-1/type、分页/筛选、两UTC格式、多目标多星多载荷/三模型/points0和1、同步与异步一致、真实失败/取消/记录状态、选择/备注/删除、旧显式路径、201登记/原字节下载/逐格预览、413限额、部分写入回滚与恢复、TTL/租约和自然重启。不是仅健康探针或原68例重复。\n\n247请求中含三次明确保留的工具失败阶段，不声称每个初次断言都通过；`unresolvedFailures=[]`，三项经定向更正/复测处置。当前responseByteAudit可定位247事件的原字节SHA（245份response.bin、169个唯一SHA、0缺失）；相同响应内容可重用字节匹配，未把文件数量伪造为请求数量。")
    emit('\n\n| 方法/活动路径 | 请求数 | 实际状态 | 成功覆盖 | 错误边界 |\n|---|---:|---|---|---|')
    for route in http['routeMatrix']:emit('| '+route['route']+' | '+str(route['requestCount'])+' | '+','.join(map(str,route['statuses']))+' | '+str(route['successCovered'])+' | '+str(route['boundaryCovered'])+' |')
    emit('\n### 关键断言与边界\n\n- 标准SPACE/GROUND分别保存、切换、回读，十字段值保留。SPACE角速度0/-1不限；GROUND0是真实零上限、-1被400拒绝；姿态双端null不限，单端错误被拒绝，startup值仅元数据且改变不影响窗口原数值。\n- 两UTC横杠/斜杠别名经真实计算，除原warnings回显调用者日期分隔符这一明确映射，JSON结果相同、CSV原字节完全相同；空间Instant非法输入保留原合同500，不借验收改变旧错误协议。\n- 标准与旧窗口合同、多历元双对象、三模型、点开关/空约束均有实际请求。同步/异步完整结果一致，长任务真正CANCELLED及result409、FAILED/SUCCEEDED/CANCELLED持久状态可查。\n- 201引用来自实际上传/结果登记，重复登记复用ID；真实下载/CSV逐字节与四个原黄金相同，preview表头/单元格/总行数相同；页码/limit/404及64MiB+1实际文件超限413，观察temp/元数据回滚。\n- 模拟非法半径在结果工作域已分配后400，实际results目录/所有权标记集合无增量；损坏重定时第二行在写入已开始后失败，独占目录与标记真正回滚。\n- TTL3000ms、cleanup500ms的独立实例，16777220字节下载跨TTL持有租约时实体仍在；完整消费后原字节一致，再自然过期404且元数据清理；未写时间戳作弊。默认业务/性能实例仍使用正常TTL24h。\n- 实际自然关闭再启动同H2/文件：持久记录及result/selection结构不变、原上传8B SHA `492d5ea496056f1a6a6592241032fab764c321596317930b4fa0e1e8bc3b7470`不变；内存task缓存重启后404。这是记录/文件恢复，不把易失task缓存称为永久。\n')
    emit(link(OUT/'http-extended-summary.json','逐请求状态/字节/SHA及边界矩阵')+'；'+link(ROOT/'verification/A01/验收用例矩阵.json','完整逐项矩阵')+'。\n')
    emit('## 4. 真实Chrome B/S、原生文件事件与页面状态\n')
    emit('最终12个脚本流均实际返回结果，生产dist、真实Chrome、真实服务、原生filechooser/upload及download/saveAs，无route/mock、无注入计算结果代替展示。成功流console/pageerror为0；主动400/404负例的网络console错误单独保存，没有隐藏。26原截图加严格取消历史新增截图，最终'+str(len(browser['screenshots']))+'截图；11份CSV/实际提交JSON/原响应JSON完成逐字节或Decimal结构核验，31偏移大原响应下载另做全字节核验。\n\n| 流程 | 实际覆盖 |\n|---|---|')
    for name,desc in descriptions.items():emit('| '+name+' | '+desc+' |')
    emit('\n空间UI使用双观测星1/3、同一目标2及真实T04-STATION，规划区间UTC10:00~10:10，页面北京时间18:00~18:10；真实选择下传连续覆盖至10:07:21（441秒），双星共同观测441秒，独立分段从响应回显。完整10分钟自选校验显示不满足下传约束，保留有效的441秒真实窗口；没有伪造满足。先前coarse1秒任务提前SUCCEEDED导致取消按钮消失的竞态作为独立历史证据保留；最终coarse0.005/fine0.001允许值真点击取消、继续轮询CANCELLED、历史CANCELLED和同源result409全通过。\n\n'+link(OUT/'browser-summary.json','真实请求/响应摘要、console、截图与下载SHA')+'。\n')
    for path,label in [('space/common-workbench.png','双星共同观测工作台'),('space/cancelled-history.png','真实CANCELLED历史'),('fit/target-0-complete-trajectory.png','1633全点四图及原始约束点'),('window/large-31-offsets.png','31偏移原样大结果轨迹')]:emit('!['+label+']('+str(ROOT/'output/playwright/A01/final'/path).replace('\\','/')+')\n')
    emit('## 5. 后端性能方法与本轮完整指标\n')
    emit('功能、构建和所有助手HTTP/Chrome负载完成后，独占本轮负载测试。每场景预热2次、串行10次；并发1/2/4/6，三个轻接口各级100有效样本，16个较重/计算/文件场景各级12完整有效样本。R2 **19场景、95组、2158测量、38热身、5准备HTTP，共2201请求**，测量和热身均无错误且完整数值/CSV一致。主指标只绑定R2新服务PID21984及最终JAR；R1测量同为2158/38但资源采样失败、退出1，保留为不完整轮，不混合到R2统计。\n\n延迟起点在请求字节已准备后urlopen之前，终点在最后响应字节读取完成；包括本机服务计算、序列化、网络和完整传输，不包括随后客户端JSON/Decimal/CSV校验。分组墙时从线程池提交前到全部完整校验结束，因此RPS包含客户端校验开销，尤其大JSON受Python解析/GIL影响；不能将该RPS解释为无验证的纯服务吞吐上限。性能API直连18140，Chrome导航/流程经15140 Node网关，两者区别明确。未暴露纯数学服务计算专用计时，不能将HTTP时间冒称纯数学CPU耗时。\n\n分位数nearest-rank `sorted(values)[ceil(N*p)-1]`，空样本拒绝计算；必要标准库自检及根独立复算通过。N10/N12的p95/p99往往是本次最大观测值，尤其p99只是本次观察上界，不能用于高置信SLA推断。没有用户SLA，也没有虚构的“达标线”。\n')
    emit('### 输入与结果规模\n\n| 场景 | 完整结果/请求规模 |\n|---|---|')
    for s in perf['scenarios']:emit('| '+s['name']+' | '+json.dumps({'input':s['scale'],'output':s['warmup'][0].get('dimensions',{}),'responseBytes':s['warmup'][0]['bytes']},ensure_ascii=False).replace('|','\\|')+' |')
    emit('\n三模型×points0/1：1目标/1星/2载荷SPACE+GROUND/3偏移/1秒原样采样；多对象：2目标/2星/4载荷/3偏移；大结果：1目标/1星/1SPACE/31偏移/完整13062点，约4MB，非空结果。点0仍保留原累计摘要，真实轨迹数组长度0；点1实际返回全部点。拟合2目标1664点；模拟和重定时各1目标2576点，所有生成CSV全字节指纹一致、仅随机拥有路径被规范化；SPACE/GROUND/各对象身份、字段和数组顺序均保留，不移除数值差异。空间单星及双星共同观测均有1真实弧，不拿空结果充当重计算。\n')
    emit('### 全部95组原指标\n\n| 场景/组 | 并发 | N/有效/错误 | p50 ms | p95 ms | p99 ms | 墙时 s | 请求/s | 字节（本组范围） |\n|---|---:|---|---:|---:|---:|---:|---:|---|')
    for s in perf['scenarios']:
        for g in s['groups']:
            sizes=[r['bytes'] for r in g['requests']];emit(f"| {s['name']}/{g['name']} | {g['concurrency']} | {g['samples']}/{g['validSamples']}/{g['errors']} | {ms(g['p50Ms'])} | {ms(g['p95Ms'])} | {ms(g['p99Ms'])} | {g['wallSec']:.4f} | {g['rps']:.2f} | {min(sizes)}~{max(sizes)} |")
    emit('\n大结果c6 p95/p99 '+ms(next(s for s in perf['scenarios'] if s['name']=='window-large-31-offsets')['groups'][-1]['p95Ms'])+'ms，表示12次完整观测，不代表普遍容量承诺。负载限于本机、最高6并发及有限轮次；没有证明无界并发、长期无泄漏、热卸载或外部数据库性能。\n\n'+link(OUT/'backend-performance.json','每请求毫秒/状态/字节/完整数值SHA原指标')+'；'+link(OUT/'performance-conditions.json','每场景请求条件/原文件来源SHA')+'；'+link(OUT/'performance-r2.txt','R2原stdout')+'；'+link(OUT/'performance-r2-exit.json','R2命令及退出0')+'。\n')
    emit('## 6. CPU、内存、线程与GC\n')
    emit(f"Windows进程采样计划250ms，实际{resource['processSampling']['samples']}样本，中位间隔{resource['processSampling']['actualMedianMs']}ms（查询/调度耗时导致间隔非恒250ms；范围{resource['processSampling']['actualMinMs']}~{resource['processSampling']['actualMaxMs']}ms）；JVM jstat-gc计划1000ms，实际{resource['jstatSamples']}样本。下表峰值都是采样观测峰，可能漏掉短于采样周期的峰。Windows工作集/PrivateBytes不是Java堆；CPU为跨线程累计处理器秒，不能按单核百分比误读。jstat容量/使用列由KiB换算，显示精度约0.1KiB，非逐字节精确堆对象统计。\n\n| 指标 | 本轮实测 |\n|---|---:|\n| 峰进程工作集 | {perf['resources']['maxWorkingSetBytes']} B（{perf['resources']['maxWorkingSetBytes']/1048576:.2f} MiB） |\n| 峰进程PrivateBytes | {perf['resources']['maxPrivateBytes']} B（{perf['resources']['maxPrivateBytes']/1048576:.2f} MiB） |\n| 峰进程线程 | {perf['resources']['maxThreads']} |\n| Java累计CPU增量 | {perf['resources']['cpuTotalSec']:.6f} s |\n| 性能阶段墙时 | {perf['finishedAtEpoch']-perf['startedAtEpoch']:.4f} s |\n| 观测峰Java堆使用 | {resource['peakHeapUsedBytes']/1048576:.2f} MiB |\n| 观测峰Java堆提交 | {resource['peakHeapCommittedBytes']/1048576:.2f} MiB |\n| YoungGC/FullGC增量 | {resource['youngGcDelta']} / {resource['fullGcDelta']} |\n| GC累计秒增量 | {resource['gcTimeDeltaSec']:.3f} s |\n")
    emit('进程CPU/资源包含本轮准备与负载的Java真实活动，采样发生在HTTP测量墙时边界附近；没有强制GC。初始/末尾堆及GC计数、原行和资源时间戳完整保存，可独立重算。不能据末尾堆值上升或有限自然退出证明/否定长期泄漏。\n\n'+link(OUT/'performance-resources.csv','Windows原采样')+'；'+link(OUT/'performance-jstat-gc.txt','JVM原GC行')+'；'+link(OUT/'performance-resource-summary.json','单位/周期/初末与峰值')+'。\n')
    emit('## 7. 前端导航、页面和大数据交互性能\n\n五次独立page.goto，真实生产dist；LCP观察器在稳定500ms后取最后候选，未假称无限等待最终LCP；资源条目保留transfer/encoded/decoded字节，浏览器缓存/初始化影响在原行可见。它们是同一Chrome的独立导航，不是五个全新OS/浏览器进程冷启动。首次本导航系列之前浏览器已执行功能流程，不能冒称绝对冷缓存。Performance.memory是Chrome提供的近似JS堆，不代表浏览器总工作集、也不是无泄漏证明。\n\n| 导航 | TTFB ms | DCL ms | load ms | FCP ms | LCP ms | 可用JS堆 B |\n|---|---:|---:|---:|---:|---:|---:|')
    for i,n in enumerate(front['navigation']['navigations'],1):emit(f"| {i} | {ms(n['ttfbMs'])} | {ms(n['domContentLoadedMs'])} | {ms(n['loadMs'])} | {ms(n['fcpMs'])} | {ms(n['lcpMs'])} | {n['jsHeap']['used']} |")
    emit('\n| 页面首次tab可见+两帧 | ms | DOM节点 |\n|---|---:|---:|')
    for p in front['navigation']['pages']:emit(f"| {p['name']} | {p['tabClickToVisibleTwoFramesMs']} | {p['domNodes']} |")
    large=front['large'];emit(f"\n31偏移、13062实际点、{large['responseBytes']}B原响应：计算点击→完整HTTP传输{large['clickToTransferCompleteMs']}ms，传输完成→剩余解析/结果DOM观测{large['transferCompleteToResultDomMs']}ms，端到端{large['clickToResultDomMs']}ms；完整当前556点轨迹绘制→两帧{large['fullTrajectoryDrawMs']}ms，切0/15/30偏移{','.join(str(x['ms']) for x in large['offsetTimings'])}ms；点击原JSON下载→实际download/saveAs完成{large['downloadInteractionMs']}ms。该剩余处理间隔含Playwright协议/Node读取解析/DOM等待，不称纯浏览器CPU。服务专用纯计算耗时未暴露；HTTP、交互端到端、剩余处理分别保存，不用静态GET或bundle字节冒充业务性能。\n")
    emit('四弹道页面的真实HTTP request.timing（完整传输）和计算点击→完整原样四图/结果DOM均另保存；多目标拟合第二目标时间包含前一个目标查看/截图/下载，属于该实际操作流程，不能解释为单个目标独立渲染时延。偏移切换/轨迹采用真实点击到可见并等待两帧。\n\n'+link(OUT/'frontend-performance.json','5导航、资源大小、JS堆与四页面/大数据原指标')+'。\n')
    emit('## 8. 失败定位、修复与重测\n\n### 唯一产品缺陷：备注null清空500\n\n原首候选真实PUT `remark:null`返回500，requestId `c784f5a7-dd35-4c67-9295-2f86e647abd1`。MyBatis updateById忽略null，SQL缺SET。先加入3服务+1MockMvc测试，RED4：1FAIL/2ERROR/1PASS；再仅改一个方法用显式主键wrapper设置nullable remark；GREEN两完整类44/44。新最终完整351/112及15/9/21重新通过。最终真socket6请求：中文备注→null清空→GET→空字符串→GET，除remark外完整明细逐结构不变；合法未知Long `9223372036854775806` null PUT404。Chrome原界面留空保存并真实刷新也清空。\n\n'+link(ROOT/'verification/A01/备注清空缺陷修复报告.md','完整RED/GREEN与根因报告')+'。\n')
    emit('### 测试工具问题与保留边界\n\n- HTTP工具：相对output路径relative_to误用（0请求）、把非数字主键的原SQL类型错误500误作合法不存在编号404、响应省略null selectionJson被工具KeyError。全部原失败/traceback与请求保留；按合法Long及真实可选字段修正并定向复测，不改产品错误协议，不覆写失败为通过。\n- 浏览器适配：隐藏checkbox原input直接check、误写fixture站名/按钮名、输入data定位等产生工具超时。根据真实快照改为用户可点控件/实际标签，原stdout留存；取消任务过短的SUCCEEDED竞态保留，最终使用控件允许0.005/0.001步长真正验证CANCELLED。\n- R1监测：原先怀疑New-Object构造，改::new后仍失败，假设被反证。单变量probe证明Windows PS5按ANSI读取UTF8无BOM中文末“。”吞掉下一行param，BoundCount0/Destination=null；仅改ASCII句点对照BoundCount3。最终param首ASCII/mandatory、fail-fast启动至少2真实行、PS1 UTF8 BOM+LF；5行及BOM4行实际小样均exit0。R1 HTTP指标有效但资源不完整/性能exit1，全部r1-*原证据保留；新服务/新performance-r2整轮2158+38和资源完整、exit0，没有混合统计。\n')
    emit('## 9. 并发故障、自然关闭与剩余限制\n')
    emit(f"本轮额外真实{failure['httpRequests']}个故障/隔离HTTP及2个TCP RST：6路成功/400/预览/下载混合，6个同名拟合12份CSV独立路径且原黄金；大响应/下载各在首批字节收到后TCP RST、完整后续请求数值/CSV不变。该16请求不混入247路由矩阵或2158性能测量数，原摘要单列。\n\n性能服务PID{shutdown['pid']}已真实context.close/main返回、自然exit{shutdown['naturalExitCode']}、无剩余非daemon、18140端口复绑成功、活JAR未改。前述每个功能/TTL/R1服务也各自自然关闭保存绑定/退出；Node网关仅精确PID Stop-Process结束并15140复绑，未将网关强停称为Java自然关闭。Chrome CLI也实际close。\n")
    emit('实际日志仍有MVC WebAsyncManager执行器告警、关闭阶段space-visibility worker及SystemClock的Tomcat潜在泄漏WARN；最终上下文返回及进程自然退出不等于长期热卸载或无限并发已证明。全日志保留，没有为让报告好看而删除WARN。\n\n')
    for line in shutdown['warnLines']:emit('- `'+line.replace('`','')+'`')
    emit('\n\n'+link(OUT/'server-performance-r2-shutdown.json','最终自然退出/复绑/JAR绑定')+'；'+link(OUT/'server-performance-r2-start-stop.txt','实际完整启动/关闭/WARN')+'。\n\n外部MySQL/DM/MinIO/Nginx仍BLOCKED、符号链接OS权限用例SKIP、最高并发只测6、样本/机器有限、未提供SLA、未做长期稳定性/无限并发/热卸载能力证明。以上明确限制不影响本机已有B/S功能与本轮有限负载的实际通过，不把H2/Node网关冒称外部部署验收。\n')
    emit('## 10. 可复现步骤与证据保存\n\n在本机既有Java8、Maven、Node/pnpm、真实原class/classpath与冻结test-data条件下，脚本拒绝覆写已有候选/服务/性能根。再次运行需选择新的本项后缀目录/端口并同步常量，不能直接覆盖现有证据。阶段命令原字节stdout、command/cwd/exit/duration/SHA均保存；较大Chrome原Result正文和计算产物留ignored runtime，退出记录指向实际原stdoutPath，原字节未改变。早期沿用工具write_text生成的元JSON可能为CRLF，按A01专属Git -text规则保留其实际字节/SHA链；后续save明确LF，新报告/矩阵及源代码按LF，未将raw JSON改写冒称原文。\n\n```powershell\npython verification/A01/acceptance_final.py prepare\npython verification/A01/acceptance_final.py build\npython verification/A01/acceptance_final.py numeric\npython verification/A01/acceptance_final.py package\n$env:A01_FINAL = \'1\'\npython verification/A01/tools_validation.py abi\npython verification/A01/services.py start server-fresh\npython verification/A01/http_acceptance.py --mode full --output-dir verification/runtime/A01/final-candidate/http-extended --summary-path verification/A01/final/http-extended-summary.json --storage-root verification/runtime/A01/final-candidate/server-fresh/files\n# 先自然close；其后按各binding.command真实重启同H2/files并执行recovery/TTL独立阶段。\npython verification/A01/browser.py open\npython verification/A01/browser.py run window\npython verification/A01/browser.py upload window-1 G:/5-YG/space-ground-visible/test-data/reference/ballistic/trajectory-0.1s-standard.csv\npython verification/A01/browser.py upload window-2 G:/5-YG/space-ground-visible/test-data/reference/ballistic/trajectory-0.1s-standard.csv\npython verification/A01/browser.py result window __a01_window\n# 原生chooser实际暂停则必须upload后再读取Result；每流实际执行顺序见chrome-*-exit.json。\npython verification/A01/browser.py close\n$env:A01_SERVER_NAME = \'server-performance-r2\'\n$env:A01_PERF_RUN = \'performance-r2\'\npython verification/A01/performance.py\npython verification/A01/services.py close server-performance-r2\npython verification/A01/report.py\n```\n')
    emit('上述是本轮真实阶段入口，现目录已存在故再次执行会被保护拒绝。服务参数/精确PID/来源SHA见每个server-*-binding.json，前端正式dist/后端JAR/Orekit来源见package-bindings，HTTP阶段编号目录及失败处分见http-extended-summary。完整247请求原body/response.bin、生成CSV、数据库、JAR和class不提交；可提交脚本、原TXT/元数据、性能数据、截图及有来源参考。没有密码或本地生产配置。\n\n报告配套 '+link(ROOT/'verification/A01/验收用例矩阵.json','验收用例矩阵')+' 含真实JUnit项、27前端测试文件组、45冻结产物、247 HTTP事件/95检查、12浏览器脚本流、95性能组及资源/环境状态。矩阵历史工具FAIL有明确scope和处分，不能把历史失败条目数量解释为当前产品未修缺陷。\n')
    (ROOT/'verification/A01/测试报告.md').write_text('\n'.join(report)+'\n',encoding='utf-8',newline='\n')
    write(OUT/'report-generation.json',dict(matrixCases=len(matrix),matrixStatusCounts=counts,backend=tests['backend'],frontendTests=tests['frontendTests'],httpRequests=event_no,httpChecks=check_no,uiFlows=12,screenshots=len(browser['screenshots']),downloadComparisons=len(browser['downloads']),performanceMeasured=len(rows),warmup=len(warm),prepareRequests=5,measuredErrors=0,reportSha256=sha(ROOT/'verification/A01/测试报告.md'),matrixSha256=sha(ROOT/'verification/A01/验收用例矩阵.json'),jarSha256=package['jarSha256']))
    print('PASS report/matrix',len(matrix),counts)

if __name__=='__main__':main()
