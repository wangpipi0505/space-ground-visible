"""A09真实软件输入、命令双流与HTTP基准；完整数据只写入ignored运行目录。"""
from pathlib import Path
import csv, copy, hashlib, json, os, subprocess, sys, urllib.request, zipfile
ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'verification/A09/software'
RUN = ROOT / 'verification/runtime/A09/software'
INPUT = ROOT / 'verification/runtime/A09/inputs'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin')
CSV = ROOT / 'frontend/public/samples/trajectory-0.1s-standard.csv'
EXPECTED = '79c13e8c50ccc6361f434d0a9e6fb4a57e4d0a0a85e6cee33cde1e090e33c90a'
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(p, data): p.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
def command(name, argv):
    """双流使用原始字节保存；命令工作目录固定ignored，失败证据不覆盖。"""
    argv = list(map(str, argv))
    p = subprocess.run(argv, cwd=RUN, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (OUT / (name+'-stdout.txt')).write_bytes(p.stdout)
    (OUT / (name+'-stderr.txt')).write_bytes(p.stderr)
    save(OUT/(name+'-command.json'), dict(argv=argv,cwd=str(RUN),exitCode=p.returncode,
        stdoutSha256=hashlib.sha256(p.stdout).hexdigest(),stderrSha256=hashlib.sha256(p.stderr).hexdigest()))
    if p.returncode: raise RuntimeError(name+'失败，请查保存的双流')
    print(name+' exitCode='+str(p.returncode), flush=True)

def freeze():
    """冻结正在运行的JAR，与A05源/类逐SHA并与JAR条目逐字节核对。"""
    pointer = ROOT/'verification/runtime/local-app-startup.json'
    startup=json.loads(pointer.read_bytes()); jar=Path(startup['RuntimeRoot'])/'release/server.jar'
    a05=ROOT/'verification/runtime/A05/current-source'
    manifest=ROOT/'verification/A05/current-source-manifest.json'
    source=json.loads(manifest.read_bytes()); current_differences=[]
    for row in source['files']:
        assert sha(a05/row['path'])==row['sha256'], row['path']
        if sha(ROOT/row['path'])!=row['sha256']: current_differences.append(row['path'])
    assert not current_differences, current_differences
    assert sha(jar)==EXPECTED==sha(a05/'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar')
    assert sha(CSV)=='5734dbfce47108374d390497f40b61a1246c451d945604c0d61c5b6f2005fec8'
    classes=a05/'backend/target/classes'; count=0
    with zipfile.ZipFile(jar) as z:
        for p in classes.rglob('*'):
            if p.is_file():
                assert p.read_bytes()==z.read('BOOT-INF/classes/'+p.relative_to(classes).as_posix()), str(p)
                count+=1
    save(OUT/'identity.json',dict(runningPid=startup['BackendPid'],jar=str(jar),jarSha256=sha(jar),
        startupPointerSha256=sha(pointer),sourceManifestSha256=sha(manifest),sourceFiles=len(source['files']),
        sourceDifferences=current_differences,classesMatchingRunningJar=count,csvSha256=sha(CSV),csvBytes=CSV.stat().st_size))
    command('running-process',['powershell','-NoProfile','-Command',
        'Get-CimInstance Win32_Process -Filter "ProcessId = '+str(startup['BackendPid'])+'" | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json'])
    return classes

def inputs():
    classes=freeze(); dependencies=(ROOT/'verification/runtime/A04/ballistic-runner/classpath.txt').read_text().strip()
    cp=str(classes)+os.pathsep+dependencies; runner=RUN/'runner';runner.mkdir(exist_ok=True)
    command('java-version',[JAVA/'java.exe','-version'])
    command('compile-probe',[JAVA/'javac.exe','-encoding','UTF-8','-source','8','-target','8','-cp',cp,'-d',runner,OUT/'ControlledInputProbe.java'])
    command('export-inputs',[JAVA/'java.exe','-Dfile.encoding=UTF-8','-cp',str(runner)+os.pathsep+cp,
        'com.tx.alg.api.ControlledInputProbe',CSV,INPUT])
    rows=list(csv.DictReader((INPUT/'full-states.csv').open(encoding='utf-8')))
    # 星历仅使用原0.1秒节点，避免与精选double网格的近邻节点使高阶插值病态。
    epoch=836167500.0
    original={r['tSec'] for r in csv.DictReader((INPUT/'original-times.csv').open(encoding='utf-8'))}
    unique={float(r['absoluteJ2000Sec']):r for r in rows if r['tSec'] in original}
    for name in ('SAT_A','SAT_B','TARGET'):
        keys=[name+'_'+k for k in ('x','y','z')]
        if name!='TARGET': keys += [name+'_'+k for k in ('vx','vy','vz')]
        with (INPUT/(name+'.e')).open('w',encoding='ascii',newline='\n') as f:
            f.write('stk.v.11.0\nBEGIN Ephemeris\nNumberOfEphemerisPoints '+str(len(unique))+'\nScenarioEpoch 1 Jul 2026 08:45:00.000\nInterpolationMethod Lagrange\nInterpolationOrder 5\nCentralBody Earth\nCoordinateSystem J2000\nDistanceUnit Meters\n'+('EphemerisTimePos\n' if name=='TARGET' else 'EphemerisTimePosVel\n'))
            for t,r in sorted(unique.items()): f.write(' '.join([repr(t-epoch)]+[r[k] for k in keys])+'\n')
            f.write('END Ephemeris\n')
    save(OUT/'input-summary.json',dict(fullRows=len(rows),ephemerisRows=len(unique),samples=sum(1 for _ in (INPUT/'samples.csv').open())-1,
        first=rows[0]['utc'],last=rows[-1]['utc'],lastTSec=rows[-1]['tSec'],probeSha256=sha(OUT/'ControlledInputProbe.java'),
        outputs=[dict(path=str(p),sha256=sha(p),bytes=p.stat().st_size) for p in sorted(INPUT.iterdir()) if p.is_file()]))
    print('受控输入完成：'+str(INPUT),flush=True)

def http():
    """真实上传与逐条件HTTP；只读取旧页面状态中的request，完整响应不打印。"""
    boundary='A09ControlledUpload20261015'
    if not (RUN/'upload-response.json').exists():
        data=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="trajectory-0.1s-standard.csv"\r\nContent-Type: text/csv\r\n\r\n').encode()+CSV.read_bytes()+('\r\n--'+boundary+'--\r\n').encode()
        (RUN/'upload-request.bin').write_bytes(data)
        req=urllib.request.Request('http://127.0.0.1:8080/api/v1/ballistic/files',data=data,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
        with urllib.request.urlopen(req, timeout=120) as response:
            raw=response.read(); (RUN/'upload-response.json').write_bytes(raw)
            save(RUN/'upload-http.json',dict(status=response.status,headers=dict(response.headers)))
    raw=(RUN/'upload-response.json').read_bytes()
    upload=json.loads(raw); print('上传响应字段='+str(list(upload)),flush=True)
    base=json.loads((ROOT/'verification/runtime/A07/implementation/default-state.json').read_bytes())['request']
    base.update(startOffsetSec=2880,stepSizeSec=10,numCalculations=1,targetSampleStepSec=1,trajectoryPointOutput=1)
    base['ballistics'][0]['csvPath']=upload['serverPath']
    for c in range(6):
        request=copy.deepcopy(base)
        for satellite in request['satellites']:
            p=satellite['payloads'][0]
            p.update(minDistanceKm=1000 if 1<=c<=4 else 0,maxDistanceKm=4000 if 1<=c<=4 else 0,
                azimuthMinDeg=100 if c==5 else (90 if c>=2 else None),azimuthMaxDeg=200 if c==5 else (270 if c>=2 else None),
                elevationMinDeg=-90 if 2<=c<=4 else None,elevationMaxDeg=0 if 2<=c<=4 else None,
                angleRateDegPerSec=.3 if 3<=c<=4 else 0,sunAvoidHalfAngleDeg=90 if c==4 else 0)
        save(RUN/('C'+str(c)+'-request.json'),request)
        req=urllib.request.Request('http://127.0.0.1:8080/api/orbit/visibility/search',data=(RUN/('C'+str(c)+'-request.json')).read_bytes(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=180) as response:
            raw=response.read();(RUN/('C'+str(c)+'-response.json')).write_bytes(raw)
            save(RUN/('C'+str(c)+'-http.json'),dict(status=response.status,headers=dict(response.headers)))
        print('C'+str(c)+' HTTP完成；字节数='+str(len(raw)),flush=True)

if __name__=='__main__':
    RUN.mkdir(parents=True,exist_ok=True); INPUT.mkdir(parents=True,exist_ok=True)
    if sys.argv[1]=='inputs': inputs()
    elif sys.argv[1]=='http': http()
