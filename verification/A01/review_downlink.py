"""A01 I01定向补测：新运行根/新证据，复用已停止的非生产T04同库，始终自然关闭。"""
from pathlib import Path
import csv, json, os, shutil, statistics, sys
os.environ['A01_FINAL']='1'
os.environ['A01_PERF_ONLY']='downlink'
SUFFIX=sys.argv[1] if len(sys.argv)>1 else 'v2'
os.environ['A01_SERVER_NAME']='server-downlink-review-'+SUFFIX
os.environ['A01_PERF_RUN']='performance-downlink-review-'+SUFFIX
import acceptance_final as candidate
import services, performance

OUT=candidate.ROOT/('verification/A01/review-fix/downlink-'+SUFFIX)
RUN=candidate.ROOT/('verification/runtime/A01/review-fix-downlink-'+SUFFIX)

def save(name,value):
    (OUT/(name+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')

def main():
    OUT.mkdir(parents=True,exist_ok=False);RUN.mkdir(parents=True,exist_ok=False)
    shutil.copytree(candidate.RUN/'managed-classes',RUN/'managed-classes')
    binding=json.loads((candidate.OUT/'server-performance-r2-binding.json').read_text(encoding='utf-8'))
    assert candidate.sha(candidate.PACKAGE/'backend/server.jar')=='d9c9956c389e79d1d52619c3e8310fb4b887d2009ad8f4c226d597cc030e3c71'
    datasource=binding['actualDatasourceUrl'];storage=binding['actualStorageRoot']
    db=candidate.RUN/'server-fixture/database/demo.mv.db'
    save('data-origin',dict(sourceBinding='verification/A01/final/server-performance-r2-binding.json',sourceBindingSha256=candidate.sha(candidate.OUT/'server-performance-r2-binding.json'),databasePath=str(db),databaseBeforeSha256=candidate.sha(db),filesRoot=storage,note='复用本轮Chrome验证T04双星同一隔离H2/files，已自然停止；非生产；不复制不注入响应'))
    services.RUN=RUN;services.OUT=OUT;services.save=save
    performance.RUN=RUN;performance.OUT=OUT;performance.save=save
    name=os.environ['A01_SERVER_NAME']
    services.start(name,extra=('--spring.datasource.url='+datasource,'--ballistic.files.root='+storage))
    try:performance.main()
    finally:services.close(name)
    save('data-after',dict(databaseSha256=candidate.sha(db),jarSha256=candidate.sha(candidate.PACKAGE/'backend/server.jar')))
    rows=list(csv.DictReader((OUT/'performance-resources.csv').open(encoding='utf-8-sig')))
    raw=(OUT/'performance-jstat-gc.txt').read_text();lines=[line.split() for line in raw.splitlines() if line.strip()]
    header=lines[0];samples=[dict(zip(header,map(float,line))) for line in lines[1:]]
    assert rows and samples and all(len(line)==len(header) for line in lines[1:])
    def heap(row,columns):return sum(row[key] for key in columns)*1024
    intervals=[int(b['timeEpochMs'])-int(a['timeEpochMs']) for a,b in zip(rows,rows[1:])]
    summary=dict(jstatSamples=len(samples),configuredPeriodMs=1000,unit='jstat容量/使用为KiB，乘1024；进程工作集不是Java堆',
        peakHeapUsedBytes=heap(max(samples,key=lambda s:heap(s,('S0U','S1U','EU','OU'))),('S0U','S1U','EU','OU')),
        peakHeapCommittedBytes=max(heap(s,('S0C','S1C','EC','OC')) for s in samples),before=samples[0],after=samples[-1],
        youngGcDelta=samples[-1]['YGC']-samples[0]['YGC'],fullGcDelta=samples[-1]['FGC']-samples[0]['FGC'],gcTimeDeltaSec=samples[-1]['GCT']-samples[0]['GCT'],
        processSampling=dict(samples=len(rows),configuredMs=250,actualMinMs=min(intervals),actualMaxMs=max(intervals),actualMedianMs=statistics.median(intervals),firstEpochMs=int(rows[0]['timeEpochMs']),lastEpochMs=int(rows[-1]['timeEpochMs'])),forcedGc=False)
    save('performance-resource-summary',summary)
    print('PASS targeted downlink 2 warmup + 58 measured; process samples',len(rows),'jstat',len(samples),flush=True)

if __name__=='__main__':main()
