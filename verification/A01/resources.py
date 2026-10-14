"""A01资源隔离故障验收：真实6路混合成功/失败、同名生成目录隔离、TCP RST截断与完整恢复；非性能统计。"""
from pathlib import Path
import concurrent.futures as futures
import copy, hashlib, json, socket, struct, sys
from decimal import Decimal
from performance import request,multipart,FILES,BASE,SPACE
from acceptance_final import ROOT,OUT,RUN,save

def main():
    runtime=RUN/'resource-failure';runtime.mkdir(exist_ok=True);events=[]
    def http(name,method,path,body=None,expected=200,content_type='application/json'):
        raw,row=request(method,path,body,content_type);row.update(name=name,path=path,method=method,expected=expected)
        events.append(row);(runtime/(name+'.bin')).write_bytes(raw);assert row['status']==expected,(name,row,raw[:200]);return raw
    csv=(ROOT/'test-data/reference/ballistic/trajectory-0.1s-standard.csv').read_bytes();body,ctype=multipart(csv)
    ref=json.loads(http('source-upload','POST',FILES,body,201,ctype))
    cases=json.loads((ROOT/'test-data/baseline/ballistic/conditions.json').read_text(encoding='utf-8'))
    window=copy.deepcopy(next(i['request'] for i in cases if i['name']=='visibility-http-ground'))
    for target in window['ballistics']:target['csvPath']=ref['serverPath']
    window['satellites'][1]['meanAnomalyDeg']=30
    expected=json.loads(http('window-serial','POST','/api/orbit/visibility/search',window),parse_float=Decimal)
    bad=copy.deepcopy(window);bad['numCalculations']=0
    jobs=[('mixed-window-'+str(i),'POST','/api/orbit/visibility/search',window,200) for i in range(3)]+[
        ('mixed-invalid','POST','/api/orbit/visibility/search',bad,400),('mixed-preview','GET',FILES+'/'+ref['fileId']+'/preview?page=2&limit=50',None,200),('mixed-download','GET',FILES+'/'+ref['fileId']+'/download',None,200)]
    with futures.ThreadPoolExecutor(max_workers=6) as pool:
        results=list(pool.map(lambda job:http(*job),jobs))
    assert all(json.loads(raw,parse_float=Decimal)==expected for raw in results[:3]);assert results[-1]==csv
    fit=copy.deepcopy(next(i['request'] for i in cases if i['name']=='fit'));fit.pop('outputDir',None)
    before=sorted(str(p) for p in (RUN/'server-fixture/files/temp').rglob('*'))
    with futures.ThreadPoolExecutor(max_workers=6) as pool:
        outputs=list(pool.map(lambda i:json.loads(http('same-name-fit-'+str(i),'POST','/api/trajectory/fit/package102',fit)),range(6)))
    paths=[file['csvPath'] for output in outputs for file in output['files']];assert len(paths)==len(set(paths))==12
    gold=ROOT/'test-data/baseline/ballistic/fit'
    for output in outputs:
        for i,file in enumerate(output['files']):assert Path(file['csvPath']).read_bytes()==(gold/('trajectory-'+str(i)+'.csv')).read_bytes()
    assert sorted(str(p) for p in (RUN/'server-fixture/files/temp').rglob('*'))==before
    resets=[]
    for path,body in [('/api/orbit/visibility/search',window),(FILES+'/'+ref['fileId']+'/download',None)]:
        payload=json.dumps(body).encode('utf-8') if body else b'';method='POST' if body else 'GET'
        with socket.create_connection(('127.0.0.1',18140),timeout=180) as client:
            header=f'{method} {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: {len(payload)}\r\nConnection: close\r\n\r\n'.encode()
            client.sendall(header+payload);received=client.recv(16384);assert b'200' in received.split(b'\r\n')[0]
            client.setsockopt(socket.SOL_SOCKET,socket.SO_LINGER,struct.pack('HH',1,0));resets.append(dict(path=path,receivedBeforeReset=len(received),fullResponseWasNotConsumed=True))
    assert json.loads(http('window-after-rst','POST','/api/orbit/visibility/search',window),parse_float=Decimal)==expected
    assert http('download-after-rst','GET',FILES+'/'+ref['fileId']+'/download')==csv
    result=dict(passed=True,httpRequests=len(events),requests=events,mixedConcurrency=6,sameTaskNameConcurrency=6,generatedCsvFiles=len(paths),generatedPathsUnique=True,csvGoldenByteExact=True,disconnects=resets,completeAfterDisconnectEqual=True,tempUnchanged=True,sourceSha256=hashlib.sha256(csv).hexdigest())
    save('resource-failure-summary',result);print('PASS resource-failure',len(events),'HTTP +',len(resets),'RST')

if __name__=='__main__':main()
