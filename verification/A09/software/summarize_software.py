"""将真实HTTP可见输出映射到完整857点网格；缺点为false而非缺失状态。"""
from pathlib import Path
import csv, hashlib, json
ROOT=Path(__file__).resolve().parents[3]
RUN=ROOT/'verification/runtime/A09/software'
OUT=ROOT/'verification/A09/software'
INPUT=ROOT/'verification/runtime/A09/inputs'
sha=lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
grid=list(csv.DictReader((INPUT/'samples.csv').open(encoding='utf-8')))
times=[float(r['tSec']) for r in grid]
assert len(grid)==857 and times[-1]>855.9
summaries=[]; sets={}; max_delta=0
with (RUN/'visibility-samples.csv').open('w',encoding='utf-8',newline='') as f:
    writer=csv.writer(f);writer.writerow(['condition','satellite','tSec','absoluteJ2000Sec','utc','visible'])
    for c in range(6):
        name='C'+str(c);request=RUN/(name+'-request.json');response=RUN/(name+'-response.json')
        data=json.loads(response.read_bytes());best=data['best'];assert len(data['allResults'])==1
        bysat={'SAT-A':set(),'SAT-B':set()}; arcs=[]
        for sat in best['visibleSatellites']:
            for payload in sat['payloadResults']:
                for target in payload['targets']:
                    for arc in target['visibleArcs']:
                        assert arc['pointCount']==len(arc['trajectoryPoints'])
                        arcs.append(dict(satellite=sat['satelliteName'],startUtc=arc['startTime'],endUtc=arc['endTime'],
                            startJ2000Sec=arc['startJ2000Sec'],endJ2000Sec=arc['endJ2000Sec'],pointCount=arc['pointCount'],durationSeconds=arc['durationSeconds']))
                        for p in arc['trajectoryPoints']:
                            # HTTP十进制输出可能舍入低位，容差仅作唯一时刻索引，不改原double网格。
                            t=p['relativeTimeSec']; candidates=[i for i,v in enumerate(times) if abs(v-t)<1e-6]
                            assert len(candidates)==1,(name,t,candidates)
                            i=candidates[0];max_delta=max(max_delta,abs(times[i]-t))
                            assert i not in bysat[sat['satelliteName']]
                            bysat[sat['satelliteName']].add(i)
        counts={sat:len(values) for sat,values in bysat.items()}
        assert sum(counts.values())==best['visiblePointCount']
        assert len(arcs)==best['visibleArcCount']
        assert abs(sum(a['durationSeconds'] for a in arcs)-best['visibleDurationSec'])<1e-6
        for sat,visible in bysat.items():
            sets[name,sat]=visible
            if 1<=c<=4: assert visible <= sets['C'+str(c-1),sat]
            for i,r in enumerate(grid):writer.writerow([name,sat,r['tSec'],r['absoluteJ2000Sec'],r['utc'],str(i in visible).lower()])
        summaries.append(dict(condition=name,requestSha256=sha(request),responseSha256=sha(response),
            status=json.loads((RUN/(name+'-http.json')).read_bytes())['status'],
            visibleArcCount=best['visibleArcCount'],visiblePointCount=best['visiblePointCount'],
            visibleDurationSec=best['visibleDurationSec'],bySatellite=counts,arcs=arcs))
assert (summaries[4]['visibleArcCount'],summaries[4]['visiblePointCount'],summaries[4]['visibleDurationSec'])==(2,246,244.0)
summary=dict(samplesPerSatellite=len(grid),maskRows=6*2*len(grid),maskPath=str(RUN/'visibility-samples.csv'),
    maskSha256=sha(RUN/'visibility-samples.csv'),maxHttpRelativeTimeRoundingSec=max_delta,
    cumulativeConditionsAreSubsets=True,conditions=summaries,
    uploadRequestSha256=sha(RUN/'upload-request.bin'),uploadResponseSha256=sha(RUN/'upload-response.json'),
    uploadStatus=json.loads((RUN/'upload-http.json').read_bytes())['status'])
(OUT/'http-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({r['condition']:{'arcs':r['visibleArcCount'],'points':r['visiblePointCount'],'duration':r['visibleDurationSec'],'satellites':r['bySatellite']} for r in summaries}))
print('maskRows='+str(summary['maskRows'])+'; actualGrid='+str(len(grid))+'; maxHttpTimeRoundingSec='+str(max_delta))
