"""保持原卫星/文件/361扫描逐项改变载荷约束，定位默认无弧原因；不改算法。"""
from pathlib import Path
from datetime import datetime, timezone
import copy,hashlib,json,urllib.request
root=Path(__file__).resolve().parents[2]
runtime=root/'verification/runtime/A07'
original=json.loads((runtime/'default-reproduction.json').read_bytes())['request']
cases=[('baseline',{}),('distance',{'minDistanceKm':0,'maxDistanceKm':0}),
       ('azimuth',{'azimuthMinDeg':None,'azimuthMaxDeg':None}),
       ('elevation',{'elevationMinDeg':None,'elevationMaxDeg':None}),
       ('rate',{'angleRateDegPerSec':-1}),('sun',{'sunAvoidHalfAngleDeg':0}),
       ('earth-facing',{'elevationMinDeg':-90,'elevationMaxDeg':0}),
       ('earth-facing-sun45',{'elevationMinDeg':-90,'elevationMaxDeg':0,'sunAvoidHalfAngleDeg':45}),
       ('earth-facing-azimuth',{'elevationMinDeg':-90,'elevationMaxDeg':0,'azimuthMinDeg':None,'azimuthMaxDeg':None}),
       ('full-geometry',{'minDistanceKm':0,'maxDistanceKm':0,'azimuthMinDeg':None,'azimuthMaxDeg':None,'elevationMinDeg':None,'elevationMaxDeg':None,'angleRateDegPerSec':-1,'sunAvoidHalfAngleDeg':0})]
rows=[]
for name,changes in cases:
    request=copy.deepcopy(original)
    for satellite in request['satellites']:
        for payload in satellite['payloads']:payload.update(changes)
    started=datetime.now(timezone.utc).isoformat()
    raw_request=json.dumps(request,ensure_ascii=False).encode('utf-8')
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8080/api/orbit/visibility/search',data=raw_request,headers={'Content-Type':'application/json'}),timeout=120) as response:
        raw=response.read();status=response.status
    (runtime/(name+'-request.json')).write_bytes(raw_request)
    (runtime/(name+'-response.json')).write_bytes(raw)
    result=json.loads(raw);best=result.get('best') or {}
    row={'name':name,'startedUtc':started,'endedUtc':datetime.now(timezone.utc).isoformat(),'changes':changes,'status':status,
         'offsets':len(result['allResults']),'bestOffset':best.get('offsetSecond'),'arcs':best.get('visibleArcCount'),
         'points':best.get('visiblePointCount'),'duration':best.get('visibleDurationSec'),'responseSha256':hashlib.sha256(raw).hexdigest()}
    rows.append(row)
    print(json.dumps(row,ensure_ascii=False),flush=True)
(root/'verification/A07/default-diagnostics.json').write_bytes((json.dumps(rows,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
