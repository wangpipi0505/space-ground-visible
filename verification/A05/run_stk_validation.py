"""A05固定STK补充验收；Python仅做解析/统计，实际传播、模拟及窗口由Java完成。

阶段prepare/build/run/measure/protect；证据独占创建，不覆盖失败现场和旧证据。
UTC全量相等才能逐点对比；重复表头按已核实的列位读取，km/km/s显式转换SI单位。
"""
from pathlib import Path
from datetime import datetime, timezone
import csv
import hashlib
import io
import json
import math
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'verification/A05'
RUN = ROOT/'verification/runtime/A05'
CLEAN = RUN/'current-source'
RAW = ROOT/'test-data/reference/stk/A05/raw'
STK = Path('G:/STK-file/Scenario3')
ALG = Path('G:/Code/Code-V3/alg')
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')
ORIGINAL = ROOT/'verification/baseline-copies/ballistic/build/alg-toolkit-1.0.0-plain.jar'
SOURCES = {'missile':'Missile1 ECEFECI-0.1.csv', 'hpop':'Satellite1 J2000 Position Velocity-HPOP.csv',
           'nodrag':'Satellite1 J2000 Position Velocity-hpop-nodrag.csv'}


def sha(path):
    """原字节SHA，日志不经过解码重编码。"""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(name, value):
    """证据独占创建；重复阶段应失败，不覆盖旧记录。"""
    with (OUT/(name+'.json')).open('x',encoding='utf-8',newline='\n') as stream:
        json.dump(value,stream,ensure_ascii=False,indent=2,allow_nan=False)


def execute(name, argv, cwd=ROOT, expected=0):
    """命令真实UTC、argv、cwd、退出码及两路原始流SHA，包括预期RED失败。"""
    stdout,stderr=OUT/(name+'.stdout.bin'),OUT/(name+'.stderr.bin')
    env=dict(os.environ,JAVA_HOME=str(JAVA),PYTHONIOENCODING='utf-8',PYTHONDONTWRITEBYTECODE='1')
    env['PATH']=str(JAVA/'bin')+os.pathsep+env['PATH']
    start=datetime.now(timezone.utc).isoformat()
    with stdout.open('xb') as out,stderr.open('xb') as err:
        result=subprocess.run([str(a) for a in argv],cwd=cwd,env=env,stdout=out,stderr=err)
    inputs=[dict(path=str(a),sha256=sha(a)) for a in argv if isinstance(a,(str,Path)) and Path(a).is_file()
            and Path(a).suffix.lower() in ('.py','.java','.json','.csv')]
    save(name+'-command',dict(argv=[str(a) for a in argv],cwd=str(cwd),startedUtc=start,executorSha256=sha(__file__),inputFiles=inputs,
         endedUtc=datetime.now(timezone.utc).isoformat(),exitCode=result.returncode,expectedExitCode=expected,
         stdoutFile=stdout.name,stderrFile=stderr.name,stdoutSha256=sha(stdout),stderrSha256=sha(stderr)))
    print(name,'exit=',result.returncode,flush=True)
    if result.returncode!=expected:
        raise RuntimeError('命令失败，现场保留：'+name)


def utc(text):
    """固定英文月份，不依赖Windows区域设置。"""
    parts=text.strip().split()
    months='Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec'.split()
    if len(parts)!=4 or parts[1] not in months:
        raise ValueError('非法STK UTCG')
    date='%s-%02d-%02d %s'%(parts[2],months.index(parts[1])+1,int(parts[0]),parts[3])
    return datetime.strptime(date,'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone.utc)


def stamp(value):
    return value.isoformat(timespec='milliseconds').replace('+00:00','Z')


def validate(rows):
    """非空、严格递增、不重复、有限；重排拒绝，不静默排序。"""
    if not rows:
        raise ValueError('空轨迹')
    for i,row in enumerate(rows):
        if i and row['utc']<=rows[i-1]['utc']:
            raise ValueError('UTC重复或非单调：%d'%i)
        for key,value in row.items():
            if key!='utc' and not all(math.isfinite(x) for x in value):
                raise ValueError('非有限状态：%d/%s'%(i,key))
    return rows


def parse_stk_text(text,kind):
    """Missile第一组Fixed、第二组J2000按位置读入，同名header不得覆盖；乘1000。"""
    reader=csv.reader(io.StringIO(text)); header=next(reader)
    missile=kind=='missile'
    expected=(['Time (UTCG)']+['x (km)','y (km)','z (km)','x (km/sec)','y (km/sec)','z (km/sec)']*2) if missile else [
        'Time (UTCG)','x (km)','y (km)','z (km)','vx (km/sec)','vy (km/sec)','vz (km/sec)']
    if header!=expected:
        raise ValueError('STK列/单位不符')
    rows=[]
    for number,row in enumerate(reader,2):
        if not row: continue
        if len(row)!=len(header): raise ValueError('STK列数不符：%d'%number)
        values=[float(v)*1000 for v in row[1:]]; state={'utc':utc(row[0])}
        columns=(('ecefP',0),('ecefV',3),('eciP',6),('eciV',9)) if missile else (('eciP',0),('eciV',3))
        for key,start in columns: state[key]=tuple(values[start:start+3])
        rows.append(state)
    return validate(rows)


def parse_stk(path,kind):
    return parse_stk_text(Path(path).read_text(encoding='utf-8-sig'),kind)


def parse_software(path):
    """软件21列UTC毫秒量化及三位位置/九位速度按原格式解析。"""
    rows=[]
    with Path(path).open(encoding='utf-8-sig',newline='') as stream:
        reader=csv.reader(stream); header=next(reader)
        if len(header)!=21 or header[11:17]!=['ECI_X','ECI_Y','ECI_Z','ECI_VX','ECI_VY','ECI_VZ']:
            raise ValueError('软件21列不符')
        for r in reader:
            if len(r)!=21: raise ValueError('软件列缺失')
            time=datetime.strptime('-'.join(r[:3])+' '+':'.join(r[3:6]),'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone.utc)
            rows.append(dict(utc=time,ecefP=tuple(map(float,r[6:9])),eciP=tuple(map(float,r[11:14])),
                             eciV=tuple(map(float,r[14:17])),ecefV=tuple(map(float,r[17:20]))))
    return validate(rows)


def parse_states(path):
    """探针保留完整Double.toString精度，速度来自真实RK4状态。"""
    with Path(path).open(encoding='utf-8',newline='') as stream:
        rows=[dict(utc=datetime.fromisoformat(r['utc'].replace('Z','+00:00')),
                   eciP=tuple(float(r[k]) for k in ('px','py','pz')),
                   eciV=tuple(float(r[k]) for k in ('vx','vy','vz'))) for r in csv.DictReader(stream)]
    return validate(rows)


def statistics(values,times):
    """分量保留有符号均值；P95=绝对值nearest-rank，最大/最差UTC按绝对值。"""
    i=max(range(len(values)),key=lambda j:abs(values[j])); ordered=sorted(abs(v) for v in values)
    return dict(mean=math.fsum(values)/len(values),rmse=math.sqrt(math.fsum(v*v for v in values)/len(values)),
                p95=ordered[math.ceil(.95*len(values))-1],max=abs(values[i]),worstUtc=stamp(times[i]))


def compare(reference,actual,keys=('eciP','eciV','ecefP','ecefV')):
    """必须全量同UTC同序，不容忍1ms错位、缺行或最近时刻匹配。"""
    validate(reference); validate(actual)
    if len(reference)!=len(actual) or any(r['utc']!=a['utc'] for r,a in zip(reference,actual)):
        raise ValueError('完整UTC对齐失败：参考%d/实测%d'%(len(reference),len(actual)))
    times=[r['utc'] for r in reference]
    result=dict(count=len(times),firstUtc=stamp(times[0]),lastUtc=stamp(times[-1]))
    for key in keys:
        deltas=[tuple(a[key][i]-r[key][i] for i in range(3)) for r,a in zip(reference,actual)]
        result[key]={axis:statistics([d[i] for d in deltas],times) for i,axis in enumerate(('x','y','z'))}
        result[key]['norm']=statistics([math.sqrt(math.fsum(v*v for v in d)) for d in deltas],times)
    return result


def compare_windows(first,second):
    """弧段身份/数量/点数/起止/时长完全保留，1ms变化不可隐藏。"""
    paired=len(first)==len(second) and all(a['satellite']==b['satellite'] for a,b in zip(first,second))
    return dict(equal=first==second,paired=paired,originalArcCount=len(first),currentArcCount=len(second),
         maxStartDeltaSec=max((abs(a['start']-b['start']) for a,b in zip(first,second)),default=0) if paired else None,
         maxEndDeltaSec=max((abs(a['end']-b['end']) for a,b in zip(first,second)),default=0) if paired else None)


def require_stk_access(name,metadata):
    """外部真值身份显式检查；软件诊断/.e不能当STK Access起止表。"""
    filename=Path(name).name.lower()
    if metadata.get('origin')!='independent_stk_access' or filename.endswith('.e') or filename.startswith(('access_diagnostic','software_visibility')):
        raise ValueError('拒绝软件输出充当STK Access：'+name)


def prepare():
    """按现有635项目录逐SHA核对、复制新源，保护原版/产品/旧证据后纳入参考。"""
    RUN.mkdir(parents=True,exist_ok=True)
    if CLEAN.exists() or RAW.exists(): raise FileExistsError('A05副本/参考已存在')
    source_manifest=ROOT/'verification/A04/current-source-manifest.json'
    copied=[]
    for item in json.loads(source_manifest.read_text(encoding='utf-8'))['files']:
        source=ROOT/item['path']
        if sha(source)!=item['sha256']: raise ValueError('当前源SHA不同：'+item['path'])
        dest=CLEAN/item['path']; dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(source,dest)
        copied.append(dict(path=item['path'],sha256=sha(dest)))
    save('current-source-manifest',dict(sourceCommit='e38880d85883a7780dd01bc115d95cb3ac2aee20',
         reusedPathInventorySha256=sha(source_manifest),fileCount=len(copied),files=copied,newlyCopied=True))
    protected=[]
    for directory in ('backend','frontend','scripts','test-data','verification/A01','verification/A02','verification/A03','verification/A04'):
        for path in sorted((ROOT/directory).rglob('*')):
            if path.is_file() and not any(p in ('target','node_modules','.git','__pycache__') for p in path.parts):
                protected.append(dict(path=str(path),sha256=sha(path)))
    for directory in (ROOT/'verification').glob('T*'):
        for path in sorted(directory.rglob('*')):
            if path.is_file(): protected.append(dict(path=str(path),sha256=sha(path)))
    old=json.loads((ROOT/'verification/A04/original-directory-before.json').read_text(encoding='utf-8'))
    original_count=0
    for group in old['comparisons']:
        for record in group['records']:
            path=Path(group['root'])/record['path']; actual=sha(path)
            if actual!=record['expected']: raise ValueError('原目录已变化：'+str(path))
            protected.append(dict(path=str(path),sha256=actual)); original_count+=1
    extras=[ALG/'STK可见窗口误差诊断与验证说明.md',ALG/'弹道拟合算法说明/弹道拟合算法关键技术说明.md',
            ALG/'trajectory-0.1s.profile.txt',ALG/'examples/target_trajectory_simulate_default.json',ALG/'examples/target_trajectory_simulate_lla.json',ORIGINAL]
    for path in sorted(list(STK.rglob('*'))+extras):
        if path.is_file(): protected.append(dict(path=str(path),sha256=sha(path)))
    save('protected-before',dict(fileCount=len(protected),originalDirectoryFileCount=original_count,files=protected))
    RAW.mkdir(parents=True); bindings=[]
    for kind,name in SOURCES.items():
        shutil.copy2(STK/name,RAW/name); rows=parse_stk(RAW/name,kind)
        if len(rows)!=(7371 if kind=='missile' else 8641): raise ValueError('完整点数不符')
        bindings.append(dict(kind=kind,originalPath=str(STK/name),fixturePath=(RAW/name).relative_to(ROOT).as_posix(),
             sha256=sha(RAW/name),originalSha256=sha(STK/name),count=len(rows),firstUtc=stamp(rows[0]['utc']),lastUtc=stamp(rows[-1]['utc'])))
    save('raw-input-bindings',dict(files=bindings,units='原km/km/s乘1000；软件m/m/s',missileFrameOrder='先Fixed后J2000按列位'))
    missile=parse_stk(RAW/SOURCES['missile'],'missile')
    request=dict(taskName='A05_STK_ECI_endpoint_reconstruction',targetId='A05-STK-REBUILT',launchTimeUtc='2026/07/01 08:24:00.000',
        impactTimeUtc='2026/07/01 08:36:16.990',taskStartTime='2026/07/01 08:24:00.000',taskEndTime='2026/07/01 08:36:16.990',
        launchPointCoordFrame='J2000',impactPointCoordFrame='J2000',launchPointUnit='m',impactPointUnit='m',
        outputPointDurationSec=.1,integrationStepSec=.1,phaseEvents=[])
    for prefix,row in [('launch',missile[0]),('impact',missile[-1])]:
        for axis,value in zip('XYZ',row['eciP']): request[prefix+'Point'+axis]=value
    save('simulation-request',request)
    configs=[]
    needles=('StartTime','StopTime','Epoch','MaxDegree','MaxOrder','UseDrag','UseSRP','ConeAngle','LaunchTime','ImpactTime',
             'Latitude','Longitude','Altitude','IncludeSun','IncludeMoon','TimeConvergence','ClockHost','TimeSense')
    for name in ('Scenario3.sc','Scenario3.sca','Satellite1.sa','Sensor3.sn','Missile1.mi'):
        source=STK/name; lines=source.read_text(encoding='utf-8',errors='replace').splitlines()
        configs.append(dict(path=str(source),sha256=sha(source),excerpts=[dict(line=i+1,text=line) for i,line in enumerate(lines)
                       if any(n in line for n in needles)][:70]))
    save('scene-source-bindings',dict(configs=configs,reports=[dict(path=str(p),sha256=sha(p)) for p in extras if p.suffix in ('.md','.txt','.json')],
         oldExactRequest='NOT_FOUND',oldDiagnosticCsv='NOT_FOUND defaultSimTask_2026-07-01_04_24_00.000_2026-07-01_12_24_00.000_SIM-001_20260625_182251_789.csv',
         reconstruction='STK原始J2000首末精确位置重建，非旧报告完全复现',accessStatus='NOT_TESTED_MISSING_STK_ACCESS'))
    rows=parse_stk(RAW/SOURCES['hpop'],'hpop')
    (RUN/'satellite-times.csv').write_text('utc,legacyUtc\n'+''.join(stamp(r['utc'])+','+r['utc'].strftime('%Y/%m/%d %H:%M:%S.%f')[:-3]+'\n' for r in rows),encoding='utf-8')
    # 原STK状态适配旧21列仅供窗口读取，不执行任何传播或重新计算参考轨迹。
    with (RUN/'stk-target-21.csv').open('x',encoding='utf-8',newline='') as stream:
        writer=csv.writer(stream)
        writer.writerow('YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt,ECI_X,ECI_Y,ECI_Z,ECI_VX,ECI_VY,ECI_VZ,ECEF_Vx,ECEF_Vy,ECEF_Vz,isOriginal'.split(','))
        for r in missile:
            t=r['utc']; writer.writerow([t.strftime(f) for f in ('%Y','%m','%d','%H','%M')]+[t.strftime('%S.%f')[:-3]]+
                list(r['ecefP'])+[0,100]+list(r['eciP'])+list(r['eciV'])+list(r['ecefV'])+[1])
    save('runtime-input-bindings',dict(files=[dict(path=str(p),sha256=sha(p)) for p in (RUN/'satellite-times.csv',RUN/'stk-target-21.csv')]))
    execute('java-version',[JAVA/'bin/java.exe','-version']); execute('maven-version',[shutil.which('mvn.cmd'),'-version'])
    execute('tests-green',[sys.executable,'-B','-m','unittest','test_stk_validation','-v'],OUT)


def build():
    """实际新副本Java8 package，不重跑未修改产品A04全套77例。"""
    execute('current-maven-build',[shutil.which('mvn.cmd'),'-DskipTests','package'],CLEAN/'backend')
    jar=CLEAN/'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
    classes=[dict(path=p.relative_to(CLEAN).as_posix(),sha256=sha(p)) for p in sorted((CLEAN/'backend/target/classes').rglob('*')) if p.is_file()]
    save('new-build-bindings',dict(jarPath=str(jar),jarSha256=sha(jar),sourceManifestSha256=sha(OUT/'current-source-manifest.json'),classes=classes,
        originalJarPath=str(ORIGINAL),originalJarSha256=sha(ORIGINAL),testsSkipped=True,
        reason='仅新增A05验证工具/参考，定向Python与Java真实双跑独立执行'))


def run():
    """原JAR/新classes各自classpath，同请求隔离双跑，不复用旧数值结果。"""
    dependency_path=ROOT/'verification/runtime/A04/ballistic-runner/classpath.txt'; dependencies=dependency_path.read_text().strip()
    binding=dict(javaSourceSha256=sha(OUT/'StkStateProbe.java'),dependenciesPath=str(dependency_path),dependenciesSha256=sha(dependency_path),
        dependencies=dependencies,originalKernel='OrbitCalculator.private propagateJ2Numerical(...).position/velocity',
        currentKernel='BallisticOrbitPropagation.bind(Elements).stateAt(OrbitTime,J2000).positionM/velocityMps',
        satelliteElements=dict(a=7530612.,e=.000850,i=64.8,raan=120.,argumentOfPerigee=270.,meanAnomaly=30.,epochUtc='2026/07/01 10:00:00',model='J2_NUMERICAL'))
    if (OUT/'probe-bindings-repaired.json').exists():
        if json.loads((OUT/'probe-bindings-repaired.json').read_text(encoding='utf-8'))!=binding:
            raise ValueError('重试探针绑定发生变化')
    else: save('probe-bindings-repaired',binding)
    def pending(name,argv):
        """恢复执行只跳过已成功且原流SHA完整的命令；失败以新具名记录复测。"""
        original_name=name; attempt=0
        record=OUT/(name+'-command.json')
        while record.exists():
            command=json.loads(record.read_text(encoding='utf-8'))
            if command['exitCode']==0:
                if sha(OUT/command['stdoutFile'])!=command['stdoutSha256'] or sha(OUT/command['stderrFile'])!=command['stderrSha256']:
                    raise ValueError('已成功证据流SHA变化')
                return
            attempt+=1; name=original_name+'-retry'+str(attempt)
            record=OUT/(name+'-command.json')
        execute(name,argv)
    for label,product in [('original',ORIGINAL),('current',CLEAN/'backend/target/classes')]:
        runner=RUN/(label+'-runner'); runner.mkdir(exist_ok=True); cp=str(product)+os.pathsep+dependencies
        compile_name=label+'-compile-repaired' if label=='original' else label+'-compile'
        pending(compile_name,[JAVA/'bin/javac.exe','-encoding','UTF-8','-cp',cp,'-d',runner,OUT/'StkStateProbe.java'])
        base=[JAVA/'bin/java.exe','-Dfile.encoding=UTF-8','-cp',str(runner)+os.pathsep+cp,'com.tx.alg.api.StkStateProbe']
        pending(label+'-simulate',base+['simulate',OUT/'simulation-request.json',RUN/(label+'-simulate')])
        pending(label+'-satellite-state',base+['states',label,RUN/'satellite-times.csv',RUN/(label+'-satellite.csv')])
        pending(label+'-frames',base+['frames',RUN/'stk-target-21.csv',RUN/(label+'-frames.json')])
        for target,csv_path in [('stk',RUN/'stk-target-21.csv'),('reconstructed',RUN/(label+'-simulate/trajectory.csv'))]:
            for offset in (0,1120):
                request=dict(ballisticCsvPath=str(csv_path),baseLaunchUtc='2026/07/01 08:24:00.000',startOffsetSec=offset,stepSizeSec=1,
                    numCalculations=1,sensorFovDeg=130.,targetSampleStepSec=.5,trajectoryPointOutput=0,orbitModel='J2_NUMERICAL')
                request['ballistics']=[dict(csvPath=str(csv_path),baseLaunchUtc='2026/07/01 08:24:00.000',targetId='A05-STK',targetName='A05-'+target)]
                request['satellites']=[dict(satelliteId=name,name=name,semiMajorAxisM=7530612.,eccentricity=.000850,inclinationDeg=64.8,
                    raanDeg=120.,argumentOfPerigeeDeg=270.,meanAnomalyDeg=mean,epochUtc='2026/07/01 10:00:00') for name,mean in [('SAT-A',30.),('SAT-B',0.)]]
                name=label+'-window-'+target+'-'+str(offset)
                request_name=name+'-request-final'
                if (OUT/(request_name+'.json')).exists():
                    if json.loads((OUT/(request_name+'.json')).read_text(encoding='utf-8'))!=request: raise ValueError('重试请求变化')
                else: save(request_name,request)
                pending(name,base+['windows',OUT/(request_name+'.json'),RUN/(name+'.json')])
    outputs=[dict(path=str(p),sha256=sha(p),bytes=p.stat().st_size) for p in sorted(RUN.rglob('*')) if p.is_file()
             and 'current-source' not in p.parts and p.suffix in ('.csv','.json')]
    save('runtime-output-bindings',dict(files=outputs))


def window_arcs(value):
    """遍历全部visibleArcs，保留原业务输出字段和平台身份。"""
    result=[]
    def walk(node,satellite=None):
        if isinstance(node,dict):
            satellite=node.get('satelliteId',satellite)
            if 'visibleArcs' in node:
                for arc in node['visibleArcs']: result.append(dict(satellite=satellite,**arc))
            for key,child in node.items():
                if key!='visibleArcs': walk(child,satellite)
        elif isinstance(node,list):
            for child in node: walk(child,satellite)
    # best是allResults的重复最优结果，实际弧段只遍历allResults避免双计数。
    walk(value['allResults']); return result


def physical_window_json(value):
    """只剔除隔离进程必然不同的csvPath来源元数据，其他所有业务字段仍严格相等。

    两版模拟CSV实际SHA相同才允许此具名路径映射，不能借机去掉起止/点数/约束字段。
    """
    if isinstance(value,dict): return {k:physical_window_json(v) for k,v in value.items() if k!='csvPath'}
    if isinstance(value,list): return [physical_window_json(v) for v in value]
    return value


def measure():
    """7371目标点、8641卫星点完整UTC对齐，窗口子范围单列；无STK Access不填零。"""
    missile=parse_stk(RAW/SOURCES['missile'],'missile')
    sims={label:parse_software(RUN/(label+'-simulate/trajectory.csv')) for label in ('original','current')}
    metrics=dict(scene='STK_J2000_ENDPOINT_RECONSTRUCTION',trajectory={label:compare(missile,rows) for label,rows in sims.items()},
                 originalCurrentTrajectory=compare(sims['original'],sims['current']))
    states={label:parse_states(RUN/(label+'-satellite.csv')) for label in ('original','current')}; metrics['satellite']={}
    subset=lambda rows:[r for r in rows if missile[0]['utc']<=r['utc']<=missile[-1]['utc']]
    for kind in ('hpop','nodrag'):
        reference=parse_stk(RAW/SOURCES[kind],kind); metrics['satellite'][kind]={}
        for label in ('original','current'):
            metrics['satellite'][kind][label]=dict(fullDay=compare(reference,states[label],('eciP','eciV')),
                missileWindow=compare(subset(reference),subset(states[label]),('eciP','eciV')))
    metrics['originalCurrentSatellite']=compare(states['original'],states['current'],('eciP','eciV'))
    metrics['frames']={label:json.loads((RUN/(label+'-frames.json')).read_text()) for label in ('original','current')}
    if sha(RUN/'original-simulate/trajectory.csv')!=sha(RUN/'current-simulate/trajectory.csv'):
        raise ValueError('重建目标原/当前CSV字节不同，不允许映射来源路径')
    windows={}
    for target in ('stk','reconstructed'):
        for offset in (0,1120):
            key=target+'-'+str(offset)
            runs={label:json.loads((RUN/(label+'-window-'+key+'.json')).read_text(encoding='utf-8')) for label in ('original','current')}
            arcs={label:window_arcs(value) for label,value in runs.items()}
            normalized=lambda seq:[dict(satellite=a['satellite'],start=a['startJ2000Sec'],end=a['endJ2000Sec'],
                duration=a['durationSeconds'],points=a['pointCount']) for a in seq]
            windows[key]=dict(parameters=dict(target=target,offsetSec=offset,sampleStepSec=.5,fullConeDeg=130.,halfConeDeg=65.,
                satellites=['SAT-A M=30','SAT-B M=0'],model='J2_NUMERICAL',baseLaunchUtc='2026/07/01 08:24:00.000'),
                arcs=arcs,exactJsonEqual=runs['original']==runs['current'],exactPhysicalJsonEqual=physical_window_json(runs['original'])==physical_window_json(runs['current']),
                exactArcsEqual=arcs['original']==arcs['current'],comparison=compare_windows(normalized(arcs['original']),normalized(arcs['current'])),
                sourcePathDifferenceOnly=target=='reconstructed',
                absoluteStkAccess='NOT_TESTED_MISSING_STK_ACCESS')
    metrics['windows']=windows
    if (OUT/'metrics-final.json').exists():
        if json.loads((OUT/'metrics-final.json').read_text(encoding='utf-8'))!=metrics: raise ValueError('重算摘要发生变化')
        save('metrics-final-recomputed',dict(executorSha256=sha(__file__),metricsSha256=sha(OUT/'metrics-final.json'),
             fullMetricsExactlyEqual=True,inputBindingsSha256=sha(OUT/'runtime-output-bindings.json')))
    else: save('metrics-final',metrics)
    if any(metrics['originalCurrentTrajectory'][k]['norm']['max']!=0 for k in ('eciP','eciV','ecefP','ecefV')) or any(
            metrics['originalCurrentSatellite'][k]['norm']['max']!=0 for k in ('eciP','eciV')) or not all(v['exactPhysicalJsonEqual'] and v['comparison']['equal'] for v in windows.values()):
        raise ValueError('原/当前双跑不同，禁止放宽阈值')
    print('Measured 7371 trajectory/8641 satellite/4 window scenarios; exact original/current equality')


def protect():
    """保护清单前后SHA对照，原目录/产品/旧证据/原STK不可变化。"""
    before=json.loads((OUT/'protected-before.json').read_text(encoding='utf-8'))
    changes=[i['path'] for i in before['files'] if not Path(i['path']).is_file() or sha(i['path'])!=i['sha256']]
    manifest=json.loads((OUT/'current-source-manifest.json').read_text(encoding='utf-8'))
    copied=[i['path'] for i in manifest['files'] if sha(CLEAN/i['path'])!=i['sha256']]
    result_name='protected-after-ecef' if (OUT/'protected-after.json').exists() else 'protected-after'
    save(result_name,dict(passed=not changes and not copied,filesChecked=len(before['files']),changes=changes,
         currentSourceFilesChecked=len(manifest['files']),currentSourceChanges=copied))
    if changes or copied: raise ValueError('保护SHA变化')
    print('Protected',len(before['files']),'files;',len(manifest['files']),'new copy input files')


def ecef():
    """独立补跑精确Fixed端点重建，贴合旧报告地固输入口径；ECI既有证据不覆盖。

    原精确LLA请求缺失，ECEF端点来自STK首末原字节乘1000；原转换核产生的差异具名统计，
    不选取误差较小的场景当旧报告完整复现，不改模型/积分/阈值。
    """
    missile=parse_stk(RAW/SOURCES['missile'],'missile')
    request=json.loads((OUT/'simulation-request.json').read_text(encoding='utf-8'))
    request['taskName']='A05_STK_ECEF_endpoint_reconstruction'; request['targetId']='A05-STK-ECEF-REBUILT'
    request['launchPointCoordFrame']=request['impactPointCoordFrame']='ECEF'
    for prefix,row in [('launch',missile[0]),('impact',missile[-1])]:
        for axis,value in zip('XYZ',row['ecefP']): request[prefix+'Point'+axis]=value
    save('simulation-ecef-request',request)
    dependencies=(ROOT/'verification/runtime/A04/ballistic-runner/classpath.txt').read_text().strip()
    for label,product in [('original',ORIGINAL),('current',CLEAN/'backend/target/classes')]:
        cp=str(RUN/(label+'-runner'))+os.pathsep+str(product)+os.pathsep+dependencies
        execute(label+'-simulate-ecef',[JAVA/'bin/java.exe','-Dfile.encoding=UTF-8','-cp',cp,'com.tx.alg.api.StkStateProbe',
            'simulate',OUT/'simulation-ecef-request.json',RUN/(label+'-simulate-ecef')])
    sims={label:parse_software(RUN/(label+'-simulate-ecef/trajectory.csv')) for label in ('original','current')}
    result=dict(scene='STK_ECEF_ENDPOINT_RECONSTRUCTION',trajectory={label:compare(missile,rows) for label,rows in sims.items()},
         originalCurrentTrajectory=compare(sims['original'],sims['current']),
         eciVersusEcefInputScene=compare(parse_software(RUN/'current-simulate/trajectory.csv'),sims['current']),
         outputBindings=[dict(path=str(RUN/(label+'-simulate-ecef/trajectory.csv')),sha256=sha(RUN/(label+'-simulate-ecef/trajectory.csv'))) for label in ('original','current')])
    save('metrics-ecef',result)
    if any(result['originalCurrentTrajectory'][k]['norm']['max']!=0 for k in ('eciP','eciV','ecefP','ecefV')):
        raise ValueError('ECEF场景双跑不同')
    print('ECEF independent reconstruction: full 7371 UTC; original/current four PV classes exactly equal')


def legacy_roi():
    """旧诊断base+1120秒时域具名ROI，复用本轮全天真实state，不再传播。

    不按旧362/501数值挑选时域；只固定README可核实的08:42:40至08:54:56.990。
    参考10秒采样最后实际点08:54:50，统计原始完整范围内全部74点，不插值伪造真值。
    """
    start=datetime(2026,7,1,8,42,40,tzinfo=timezone.utc)
    end=datetime(2026,7,1,8,54,56,990000,tzinfo=timezone.utc)
    subset=lambda rows:[r for r in rows if start<=r['utc']<=end]
    states={label:subset(parse_states(RUN/(label+'-satellite.csv'))) for label in ('original','current')}
    result=dict(name='legacy_diagnostic_base_plus_1120',requestedStartUtc=stamp(start),requestedEndUtc=stamp(end),satellite={})
    for kind in ('hpop','nodrag'):
        reference=subset(parse_stk(RAW/SOURCES[kind],kind))
        result['satellite'][kind]={label:compare(reference,rows,('eciP','eciV')) for label,rows in states.items()}
    result['originalCurrentSatellite']=compare(states['original'],states['current'],('eciP','eciV'))
    save('metrics-legacy-roi',result)
    print('Legacy diagnostic +1120 ROI: 74 existing actual states per reference/version; no Java rerun')


if __name__=='__main__':
    {'prepare':prepare,'build':build,'run':run,'measure':measure,'protect':protect,'ecef':ecef,'legacy-roi':legacy_roi}[sys.argv[1]]()
