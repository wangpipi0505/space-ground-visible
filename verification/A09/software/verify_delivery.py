"""只读核对最终共同输入、HTTP响应与实际命令原双流的绑定。"""
from pathlib import Path
import csv, hashlib, json, math
ROOT=Path(__file__).resolve().parents[3]; OUT=ROOT/'verification/A09/software'
INPUT=ROOT/'verification/runtime/A09/inputs'; RUN=ROOT/'verification/runtime/A09/software'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
full={float(r['absoluteJ2000Sec']):r for r in csv.DictReader((INPUT/'full-states.csv').open(encoding='utf-8'))}
original=list(csv.DictReader((INPUT/'original-times.csv').open(encoding='utf-8')))
assert len(original)==8560 and len(full)==9311
for name in ('SAT_A','SAT_B','TARGET'):
    lines=(INPUT/(name+'.e')).read_text().splitlines(); marker='EphemerisTimePos' if name=='TARGET' else 'EphemerisTimePosVel'
    data=lines[lines.index(marker)+1:-1];assert len(data)==8560
    assert 'ScenarioEpoch 1 Jul 2026 08:45:00.000' in lines
    previous=-math.inf
    for line,source in zip(data,original):
        parts=list(map(float,line.split()));t=parts[0]+836167500.0
        assert t>previous; previous=t
        assert t==float(source['absoluteJ2000Sec'])
        expected=full[t];keys=[name+'_'+k for k in ('x','y','z')]
        if name!='TARGET':keys+=[name+'_'+k for k in ('vx','vy','vz')]
        assert parts[1:]==[float(expected[k]) for k in keys]
        assert all(math.isfinite(v) for v in parts)
bindings=json.loads((OUT/'input-summary.json').read_bytes())
for row in bindings['outputs']:assert sha(Path(row['path']))==row['sha256'],row['path']
http=json.loads((OUT/'http-summary.json').read_bytes())
for c in http['conditions']:
    assert sha(RUN/(c['condition']+'-request.json'))==c['requestSha256']
    assert sha(RUN/(c['condition']+'-response.json'))==c['responseSha256']
assert sha(Path(http['maskPath']))==http['maskSha256']
commands=[]
for path in OUT.glob('*-command.json'):
    d=json.loads(path.read_bytes());name=path.name[:-len('-command.json')]
    assert sha(OUT/(name+'-stdout.txt'))==d['stdoutSha256']
    assert sha(OUT/(name+'-stderr.txt'))==d['stderrSha256']
    assert d['exitCode']==0
    commands.append(name)
result=dict(ephemerisFiles=3,ephemerisRowsEach=8560,directStateRows=len(full),samplesPerSatellite=857,
    commandsVerified=sorted(commands),httpConditions=6,maskRows=10284,
    dependencyClasspathSha256=sha(ROOT/'verification/runtime/A04/ballistic-runner/classpath.txt'),
    scripts=[dict(name=p.name,sha256=sha(p)) for p in sorted(OUT.iterdir()) if p.suffix in ('.java','.py')],
    compiledProbeSha256=sha(RUN/'runner/com/tx/alg/api/ControlledInputProbe.class'))
(OUT/'delivery-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=True))
