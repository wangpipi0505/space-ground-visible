"""A01独占性能测量：标准库完整读响应，原始毫秒/状态/字节/数值指纹及nearest-rank；不设置SLA。"""
from pathlib import Path
import concurrent.futures as futures
import copy, csv, hashlib, io, json, math, os, subprocess, sys, threading, time
import ctypes
from ctypes import wintypes
from decimal import Decimal
from urllib.error import HTTPError
from urllib.request import Request,urlopen
if os.environ.get('A01_FINAL'):
    from acceptance_final import ROOT, OUT, RUN, JAVA, save, sha
else:
    from acceptance import ROOT, OUT, RUN, JAVA, save, sha
BASE='http://127.0.0.1:18140'; FILES='/api/v1/ballistic/files'; SPACE='/api/v1/space'
PREPARATION=[]

def process_cpu_sec(pid):
    """读取精确PID的Windows累计内核/用户CPU秒；不调用GC、不修改被测进程。"""
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,*([ctypes.POINTER(wintypes.FILETIME)]*4)]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x1000,False,pid);assert handle,ctypes.get_last_error()
    times=[wintypes.FILETIME() for _ in range(4)]
    try:assert kernel.GetProcessTimes(handle,*[ctypes.byref(item) for item in times]),ctypes.get_last_error()
    finally:kernel.CloseHandle(handle)
    return sum((item.dwHighDateTime<<32)+item.dwLowDateTime for item in times[2:])/10000000

def percentile_ms(values,p):
    """最近秩分位数，N<100的p99明确只是本次观测上界；空输入拒绝计算。"""
    if not values or not 0<p<=1:raise ValueError('分位数输入为空或越界')
    ordered=sorted(values);return ordered[max(0,math.ceil(len(ordered)*p)-1)]

def summarize(rows,wall):
    """错误率分母为全部请求；延迟统计只用于完整且数值一致的有效样本。"""
    values=[r['ms'] for r in rows if r['ok']]
    return dict(samples=len(rows),validSamples=len(values),errors=len(rows)-len(values),errorRate=(len(rows)-len(values))/len(rows),wallSec=wall,rps=len(rows)/wall,validRps=len(values)/wall,throughputBoundary='线程池全组墙时含完整响应读取和数值/CSV校验；不是纯服务容量上界',
        p50Ms=percentile_ms(values,.5) if values else None,p95Ms=percentile_ms(values,.95) if values else None,p99Ms=percentile_ms(values,.99) if values else None,
        minMs=min(values) if values else None,maxMs=max(values) if values else None,p99Caution='N<100：仅本次观测上界，不能推断稳健SLA' if len(values)<100 else 'nearest-rank；仅本机本轮')

def validate_matrix(rows):
    """防止重复ID和未定义状态虚增验收计数。"""
    assert len({r['id'] for r in rows})==len(rows),'重复用例ID'
    assert all(r['status'] in ('PASS','FAIL','SKIP','BLOCKED') for r in rows)

def request(method,path,body=None,content_type='application/json'):
    """计时起点在构造请求字节之后、urlopen之前；终点为最后响应字节读取结束。"""
    payload=body if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False).encode('utf-8') if body is not None else None
    start=time.perf_counter()
    try:response=urlopen(Request(BASE+path,payload,{'Content-Type':content_type} if payload is not None else {},method=method),timeout=240)
    except HTTPError as error:response=error
    with response:raw=response.read();status=response.status
    return raw,dict(ms=(time.perf_counter()-start)*1000,status=status,bytes=len(raw),responseSha256=hashlib.sha256(raw).hexdigest())

def multipart(binary,name='trajectory.csv'):
    """固定可复现boundary；输入为有来源完整CSV，不截断和降采样。"""
    boundary='A01-performance-isolated'
    return ('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: text/csv\r\n\r\n').encode()+binary+('\r\n--'+boundary+'--\r\n').encode(),'multipart/form-data; boundary='+boundary

def digest(raw,kind):
    """完整Decimal结构指纹；仅消除本轮随机拥有目录/id，CSV另比较全字节SHA。"""
    if kind=='binary':return hashlib.sha256(raw).hexdigest(),{}
    value=json.loads(raw,parse_float=Decimal)
    dimensions={};csv_hashes=[]
    if kind=='generated':
        for item in value['files']:
            csv_hashes.append(sha(Path(item['csvPath'])));item['csvPath']='<OWNED_CSV>'
        dimensions={'targets':value['targetCount'],'points':sum(item['pointCount'] for item in value['files']),'csvSha256':csv_hashes}
    if kind=='upload':
        assert sha(Path(value['serverPath']))==sha(ROOT/'test-data/reference/ballistic/trajectory-0.1s-standard.csv')
        value={k:value[k] for k in ('originalName','sizeBytes','contentType')};dimensions={'uploadBytes':value['sizeBytes']}
    if kind=='window':
        dimensions={'offsets':len(value['allResults']),'arcs':sum(o['visibleArcCount'] for o in value['allResults']),'points':sum(o['visiblePointCount'] for o in value['allResults']),
            'returnedTrajectoryPoints':sum(len(a['trajectoryPoints']) for o in value['allResults'] for s in o['visibleSatellites'] for p in s['payloadResults'] for t in p['targets'] for a in t['visibleArcs'])}
        assert dimensions['arcs']>0,'空结果不属于本场景重负载'
    if kind=='space':
        dimensions={'resultGroups':len(value['results']),'arcs':sum(len(r['arcs']) for r in value['results'])}
        assert dimensions['arcs']>0,'空间重场景没有真实弧段'
    if kind=='downlink':
        # 每条实际链路与总覆盖均必须非空且正时长；空数组不能充当有效计算负载。
        links=value['satelliteStationWindows'];coverage=value['coverageWindows']
        assert links and coverage,'下传链路或覆盖为空'
        assert all(link['windows'] and all(Decimal(w['durationSec'])>0 for w in link['windows']) for link in links),'下传链路无正时长窗口'
        assert all(Decimal(w['durationSec'])>0 and w['links'] for w in coverage),'下传覆盖无正时长或链路'
        dimensions=dict(satellites=len({x['satelliteId'] for x in links}),stations=len({x['stationId'] for x in links}),links=len(links),
            satelliteStationWindows=sum(len(x['windows']) for x in links),coverageWindows=len(coverage),
            linkDurationSec=str(sum(Decimal(w['durationSec']) for x in links for w in x['windows'])),coverageDurationSec=str(sum(Decimal(w['durationSec']) for w in coverage)),
            identities=[dict(satelliteId=x['satelliteId'],stationId=x['stationId']) for x in links],skippedLinks=value['totalSkippedLinks'])
    if kind=='downlink':
        # JSON整数/小数书写不同不构成数值差异；Decimal严格规范化，无四舍五入或容差。
        def exact_numbers(item):
            if isinstance(item,bool):return item
            if isinstance(item,(int,Decimal)):
                number=Decimal(item)
                return {'<DECIMAL>':format(number,'f').rstrip('0').rstrip('.') if '.' in format(number,'f') else format(number,'f')}
            if isinstance(item,list):return [exact_numbers(x) for x in item]
            if isinstance(item,dict):return {key:exact_numbers(x) for key,x in item.items()}
            return item
        value=exact_numbers(value)
    normalized=json.dumps([value,csv_hashes],sort_keys=True,ensure_ascii=False,default=str,separators=(',',':')).encode()
    return hashlib.sha256(normalized).hexdigest(),dimensions

def downlink_scenario(runtime):
    """以真实Chrome T04请求/结果为基准；明确定位站名、双星身份及同库星历，不依赖目录首项。"""
    browser=ROOT/'verification/A01/final/browser-summary.json'
    source=json.loads(browser.read_text(encoding='utf-8'))
    event=next(x for x in source['space']['result']['evidence'] if x['name']=='downlink')
    body=copy.deepcopy(event['request'])
    for label,path in [('stations',SPACE+'/stations'),('satellites',SPACE+'/satellites')]:
        raw,row=request('GET',path);assert row['status']==200,row
        evidence=runtime/('prepare-'+label+'-response.bin');evidence.write_bytes(raw)
        PREPARATION.append(dict(name=label,method='GET',path=path,body=None,responsePath=str(evidence),**row))
        value=json.loads(raw)
        if label=='stations':
            matches=[x for x in value if x['id']=='T04-STATION'];assert len(matches)==1,matches
        else:
            matches=[x for x in value if x['id'] in body['satelliteIds']];assert len(matches)==2,matches
            original=json.loads((ROOT/'test-data/baseline/space/satellites.json').read_text(encoding='utf-8'))
            expected=next(x for x in original if x['id']==1)
            # T27双星fixture的第二星平近点角为2度；其余根数与T04观测星相同。
            keys=('epochTime','semiMajorAxis','eccentricity','inclination','meanAnomaly','raan','argumentOfPerigee','modelType')
            for item in matches:
                target=expected|({'meanAnomaly':2} if item['id']==3 else {})
                assert item['name']==('基准共同观测星' if item['id']==3 else '基准观测星'),item
                assert all(item[key]==target[key] for key in keys),(item,target)
        PREPARATION[-1]['matchedIdentities']=matches
    expected_raw=json.dumps(event['response'],ensure_ascii=False).encode('utf-8')
    expected,dim=digest(expected_raw,'downlink')
    return dict(name='space-downlink',path=SPACE+'/downlink/plan',body=body,kind='downlink',method='POST',light=False,contentType='application/json',expected=200,
        expectedDigest=expected,scale=dict(satellites=2,stations=1,links=2,intervalSec=600,coarseStepSec=body['coarseStepSec'],fineStepSec=body['fineStepSec'],expectedOutput=dim),
        expectedSource=dict(path=str(browser.relative_to(ROOT)),sha256=sha(browser),event='space.result.evidence[name=downlink]',cliStdoutSha256=source['space']['cliStdoutSha256'],runtimeResultSha256=source['space']['runtimeResultSha256']))

def scenarios(runtime=None):
    """窗口三模型/点0点1/31偏移大结果、多对象标准SPACE+GROUND、拟合模拟重定时及空间链路。"""
    if os.environ.get('A01_PERF_ONLY')=='downlink':return [downlink_scenario(runtime)]
    fixture=lambda p:json.loads((ROOT/p).read_text(encoding='utf-8-sig'))
    binary=(ROOT/'test-data/reference/ballistic/trajectory-0.1s-standard.csv').read_bytes()
    body,ctype=multipart(binary);raw,row=request('POST',FILES,body,ctype);assert row['status']==201;ref=json.loads(raw)
    other=(ROOT/'test-data/baseline/ballistic/simulate/trajectory-0.csv').read_bytes()
    b,c=multipart(other,'retime.csv');raw,row=request('POST',FILES,b,c);assert row['status']==201;retime_ref=json.loads(raw)
    conditions=fixture('test-data/baseline/ballistic/conditions.json')
    original=copy.deepcopy(next(i['request'] for i in conditions if i['name']=='visibility-http-ground'))
    for target in original['ballistics']:target['csvPath']=ref['serverPath']
    standard=lambda kind:dict(payloadId=kind,payloadName=kind,payloadType=kind,minDistanceKm=0,maxDistanceKm=0,azimuthMinDeg=None,azimuthMaxDeg=None,elevationMinDeg=None,elevationMaxDeg=None,angleRateDegPerSec=-1 if kind=='SPACE' else 180,sunAvoidHalfAngleDeg=0,startupAngleRateDegPerSec=-1)
    result=[]
    def add(name,path,request_body=None,kind='json',method=None,light=False,content_type='application/json',scale=None,expected=200):
        result.append(dict(name=name,path=path,body=request_body,kind=kind,method=method or ('POST' if request_body is not None else 'GET'),light=light,contentType=content_type,scale=scale,expected=expected))
    add('catalog-satellites',SPACE+'/satellites/page?pageNumber=1&pageSize=20',light=True)
    add('payload-preview','/api/v1/ballistic/payloads/preview',standard('SPACE'),light=True)
    add('file-preview',FILES+'/'+ref['fileId']+'/preview?page=2&limit=50',light=True)
    add('file-download-1507045',FILES+'/'+ref['fileId']+'/download',kind='binary',scale={'sourceBytes':len(binary)})
    add('file-upload-1507045',FILES,body,'upload',content_type=ctype,expected=201,scale={'sourceBytes':len(binary)})
    for model in ('TWO_BODY','J2_SECULAR','J2_NUMERICAL'):
        for points in (0,1):
            value=copy.deepcopy(original);value.update(orbitModel=model,trajectoryPointOutput=points)
            # 单目标单星同历元完整标准SPACE/GROUND；三偏移保持原样采样1秒。
            value['ballistics']=value['ballistics'][:1];value['satellites']=value['satellites'][:1]
            value['satellites'][0]['payloads']=[standard('SPACE'),standard('GROUND')]
            add(f'window-{model}-points{points}','/api/orbit/visibility/search',value,'window',scale={'targets':1,'satellites':1,'payloads':2,'offsets':3,'sampleStepSec':1,'points':points})
    value=copy.deepcopy(original);value['satellites'][1]['meanAnomalyDeg']=30
    for s in value['satellites']:s['payloads']=[standard('SPACE'),standard('GROUND')]
    add('window-multi-2t-2s-4p','/api/orbit/visibility/search',value,'window',scale={'targets':2,'satellites':2,'payloads':4,'offsets':3,'sampleStepSec':1})
    value=copy.deepcopy(original);value.update(numCalculations=31);value['ballistics']=value['ballistics'][:1];value['satellites']=value['satellites'][:1];value['satellites'][0]['payloads']=[standard('SPACE')]
    add('window-large-31-offsets','/api/orbit/visibility/search',value,'window',scale={'targets':1,'satellites':1,'payloads':1,'offsets':31,'sampleStepSec':1,'points':1})
    for name,path in [('fit','fit/package102'),('simulate','simulate'),('retime','retime')]:
        value=copy.deepcopy(next(i['request'] for i in conditions if i['name']==name));value.pop('outputDir',None)
        if name=='retime':value['trajectoryCsvPath']=retime_ref['serverPath']
        add(name,'/api/trajectory/'+path,value,'generated')
    plan=fixture('test-data/baseline/space/visibility-request.json')
    # 服务demo初始化只补缺项；明确保存标准无限制profile，不沿用fixture历史活动GROUND。
    for identity in ('1','3'):
        profile=standard('SPACE');profile.pop('payloadId');profile.pop('payloadName')
        raw,row=request('PUT',SPACE+'/satellites/'+identity+'/payload-profiles',profile);assert row['status']==200
    add('space-single',SPACE+'/visibility/plan',plan,'space')
    multi=copy.deepcopy(plan);multi['observerSatelliteIds']=[1,3];multi['observationConstraints']=[{'satelliteIds':[1,3],'minDurationSec':0}]
    add('space-common-two-satellites',SPACE+'/visibility/plan',multi,'space')
    result.append(downlink_scenario(runtime))
    return result

def main():
    """单进程独占逐场景逐级测试，热身2+串行10+并发1/2/4/6；失败完整保留不隐藏。"""
    runtime=RUN/os.environ.get('A01_PERF_RUN','performance');assert not runtime.exists(),'拒绝覆盖性能运行根';runtime.mkdir()
    binding=json.loads((OUT/(os.environ.get('A01_SERVER_NAME','server-fixture')+'-binding.json')).read_text(encoding='utf-8'));pid=binding['pid']
    stop=runtime/'resource.stop';assert not stop.exists()
    monitor_script=ROOT/'verification/A01/monitor.ps1'
    monitor=subprocess.Popen(['powershell.exe','-NoProfile','-File',str(monitor_script),'-ProcessId',str(pid),'-Destination',str(runtime/'resources.csv'),'-StopFile',str(stop)],stdout=(runtime/'monitor.stdout').open('wb'),stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    gc=subprocess.Popen([str(JAVA/'bin/jstat.exe'),'-gc',str(pid),'1000'],stdout=(runtime/'jstat-gc.txt').open('wb'),stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    # 真实监测进程必须先产出至少两行有效CSV，再开始任何性能请求；退出或超时直接停止，避免晚发现整轮资源缺失。
    deadline=time.monotonic()+10
    while True:
        if monitor.poll() is not None:
            gc.terminate();gc.wait(timeout=10);raise AssertionError('进程监测启动失败：'+str(runtime/'monitor.stdout'))
        path=runtime/'resources.csv'
        if path.exists() and len(path.read_text(encoding='utf-8-sig').splitlines())>=3:break
        if time.monotonic()>deadline:
            stop.write_text('stop\n',encoding='utf-8');monitor.wait(timeout=10);gc.terminate();gc.wait(timeout=10);raise AssertionError('资源监测10秒没有真实样本')
        time.sleep(.1)
    results=[];started=time.time();cpu_started=process_cpu_sec(pid)
    try:
        prepare_started=time.time();prepare_cpu_start=process_cpu_sec(pid);cases=scenarios(runtime);prepare_finished=time.time();prepare_cpu_end=process_cpu_sec(pid)
        save('performance-preparation',dict(startedAtEpoch=prepare_started,finishedAtEpoch=prepare_finished,wallSec=prepare_finished-prepare_started,cpuBeforeSec=prepare_cpu_start,cpuAfterSec=prepare_cpu_end,cpuDeltaSec=prepare_cpu_end-prepare_cpu_start,requests=PREPARATION))
        for scenario in cases:
            label=scenario['name'];expected_digest=scenario.get('expectedDigest')
            (runtime/(label+'-request.json')).write_text(json.dumps({k:v for k,v in scenario.items() if k!='body'}|{'body':scenario['body'].decode('utf-8',errors='replace') if isinstance(scenario['body'],bytes) else scenario['body']},ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
            def sample(index):
                try:
                    raw,row=request(scenario['method'],scenario['path'],scenario['body'],scenario['contentType']);row['index']=index
                    if scenario['kind']=='downlink':
                        destination=runtime/(label+'-'+phase+'-'+str(index)+'-response.bin');destination.write_bytes(raw);row['responsePath']=str(destination.relative_to(ROOT))
                    if row['status']!=scenario['expected']:raise AssertionError('HTTP状态'+str(row['status'])+':'+raw[:250].decode('utf-8',errors='replace'))
                    actual,dim=digest(raw,scenario['kind']);row.update(ok=expected_digest is None or actual==expected_digest,numericSha256=actual,dimensions=dim)
                    if not row['ok']:row['error']='完整数值/CSV指纹不一致'
                    return row
                except Exception as error:return dict(index=index,ok=False,ms=row['ms'] if 'row' in locals() else 0,status=row['status'] if 'row' in locals() else 0,bytes=row['bytes'] if 'row' in locals() else 0,error=repr(error))
            warm=[];phase='warmup';warm_started=time.time();warm_cpu_start=process_cpu_sec(pid)
            for i in range(2):
                row=sample(i);warm.append(row)
                if row['ok'] and expected_digest is None:expected_digest=row['numericSha256']
            entry=dict(name=label,kind=scenario['kind'],scale=scenario['scale'],warmup=warm,groups=[],expectedSource=scenario.get('expectedSource'),warmupWallSec=time.time()-warm_started,warmupCpuDeltaSec=process_cpu_sec(pid)-warm_cpu_start)
            results.append(entry)
            for name,workers,count in [('serial',1,10)]+[(f'c{c}',c,100 if scenario['light'] else 12) for c in (1,2,4,6)]:
                phase=name;t=time.perf_counter();group_started=time.time();group_cpu_start=process_cpu_sec(pid)
                with futures.ThreadPoolExecutor(max_workers=workers) as pool:rows=list(pool.map(sample,range(count)))
                entry['groups'].append(dict(name=name,concurrency=workers,requests=rows,startedAtEpoch=group_started,finishedAtEpoch=time.time(),cpuBeforeSec=group_cpu_start,cpuAfterSec=process_cpu_sec(pid),**summarize(rows,time.perf_counter()-t)))
                assert monitor.poll() is None,'资源监测提前退出，立即停止负载'
                save('backend-performance',dict(startedAtEpoch=started,pid=pid,heap='-Xms256m -Xmx4g',sampling='进程250ms；JVM jstat -gc 1000ms；不主动GC',percentile='nearest-rank',scenarios=results,complete=False))
                print(label,name,len(rows),sum(r['ok'] for r in rows),'p95=',round(entry['groups'][-1]['p95Ms'] or 0,2),flush=True)
        requests_finished=time.time();cpu_finished=process_cpu_sec(pid)
    finally:
        stop.write_text('stop\n',encoding='utf-8');monitor.wait(timeout=10);gc.terminate();gc.wait(timeout=10)
        shutil_copy=lambda src,dst:Path(dst).write_bytes(Path(src).read_bytes())
        shutil_copy(runtime/'resources.csv',OUT/'performance-resources.csv');shutil_copy(runtime/'jstat-gc.txt',OUT/'performance-jstat-gc.txt')
        save('monitor-exits',dict(processMonitorPid=monitor.pid,processMonitorExitCode=monitor.returncode,jstatPid=gc.pid,jstatExitCode=gc.returncode,jstatShutdown='测量结束显式terminate采样器，不是JVM自然关闭',processStdoutSha256=sha(runtime/'monitor.stdout'),processCsvSha256=sha(runtime/'resources.csv'),jstatStdoutSha256=sha(runtime/'jstat-gc.txt')))
    rows=list(csv.DictReader((runtime/'resources.csv').open(encoding='utf-8-sig')))
    resource_summary={'samples':len(rows),'periodMs':250,'maxWorkingSetBytes':max(int(r['workingSetBytes']) for r in rows),'maxPrivateBytes':max(int(r['privateBytes']) for r in rows),'maxThreads':max(int(r['threads']) for r in rows),'cpuTotalSec':float(rows[-1]['cpuSec'])-float(rows[0]['cpuSec']),'workingSetIsNotHeap':True}
    result=dict(startedAtEpoch=started,finishedAtEpoch=time.time(),requestsFinishedAtEpoch=requests_finished,requestsWallSec=requests_finished-started,cpuBeforeSec=cpu_started,cpuAfterSec=cpu_finished,cpuDeltaSec=cpu_finished-cpu_started,preparation=PREPARATION,preparationStartedAtEpoch=prepare_started,preparationFinishedAtEpoch=prepare_finished,pid=pid,jarSha256=binding['jarSha256'],heap='-Xms256m -Xmx4g',scenarios=results,resources=resource_summary,percentile='nearest-rank',complete=all(r['ok'] for s in results for r in s['warmup']+[r for g in s['groups'] for r in g['requests']]),sla='用户未提供SLA，仅报告实测；N12的p99是观测上界')
    save('backend-performance',result)
    assert result['complete'],'完整样本失败；禁止宣称通过'
    print('PERFORMANCE COMPLETE',len(results),flush=True)

if __name__=='__main__':main()
