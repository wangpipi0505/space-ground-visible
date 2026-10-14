"""A01真实Chrome结果与下载收口：读取CLI原stdout的Result，保留大原文于runtime；摘要仅存SHA/规模/真实状态。"""
from pathlib import Path
from decimal import Decimal
import copy,hashlib,json,sys
from acceptance_final import ROOT,OUT,RUN,save,sha

def main():
    store=RUN/'browser-results';store.mkdir(exist_ok=True);summary={};downloads=[]
    names=['space','window','window-edge','fit','fit-edge','fit-aux-failure','fit-aux-recovery','simulation','retime','retime-edge','navigation','large']
    for name in names:
        path=OUT/('chrome-'+name+'-result.txt')
        if not path.exists():
            command=json.loads((OUT/('chrome-'+name+'-result-exit.json')).read_text(encoding='utf-8'));path=Path(command.get('stdoutPath',str(RUN/'browser-stdout'/path.name)))
        text=path.read_text(encoding='utf-8')
        assert '### Result\n' in text,(name,'无实际Result')
        value=json.loads(text.split('### Result\n',1)[1].split('\n### Ran',1)[0].strip());assert isinstance(value,dict),(name,value)
        (store/(name+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
        def shrink(obj):
            if isinstance(obj,dict):return {key:({'bytes':len(item.encode('utf-8')),'sha256':hashlib.sha256(item.encode('utf-8')).hexdigest()} if key in ('raw','rawText','winRaw','requestJson','resultJson','selectionJson') and isinstance(item,str) else shrink(item)) for key,item in obj.items()}
            if isinstance(obj,list):return [shrink(item) for item in obj]
            return obj
        summary[name]=dict(status='PASS',cliStdoutSha256=sha(path),runtimeResultSha256=sha(store/(name+'.json')),result=shrink(value))
    source=ROOT/'test-data/reference/ballistic/trajectory-0.1s-standard.csv'
    assert (RUN/'window/download-original.csv').read_bytes()==source.read_bytes()
    downloads.append(dict(path='window/download-original.csv',sha256=sha(source),source=str(source),byteExact=True))
    for scene,names in [('fit',['download-trajectory-0.csv','download-trajectory-1.csv']),('simulation',['download-default.csv']),('retime',['download-default.csv'])]:
        for i,name in enumerate(names):
            expected=ROOT/'test-data/baseline/ballistic'/('simulate' if scene=='simulation' else scene)/('trajectory-'+str(i)+'.csv');actual=RUN/scene/name
            assert actual.read_bytes()==expected.read_bytes(),(scene,name,'CSV黄金差异')
            downloads.append(dict(path=scene+'/'+name,sha256=sha(actual),bytes=actual.stat().st_size,source=str(expected),byteExact=True))
    for scene in ['fit','simulation','retime']:
        value=json.loads((store/(scene+'.json')).read_text(encoding='utf-8'));request=value['request'] if 'request'in value else value['computations'][0]['request'];raw=value.get('rawText',value.get('raw',value['computations'][0]['raw'] if 'computations'in value else ''))
        actual=json.loads((RUN/scene/'download-input.json').read_text(encoding='utf-8'),parse_float=Decimal);expected=json.loads(json.dumps(request),parse_float=Decimal);assert actual==expected,(scene,'实际提交JSON下载差异')
        actual=json.loads((RUN/scene/'download-response.json').read_text(encoding='utf-8'),parse_float=Decimal);expected=json.loads(raw,parse_float=Decimal);assert actual==expected,(scene,'原响应JSON下载差异')
        downloads.extend(dict(path=scene+'/'+name,sha256=sha(RUN/scene/name),bytes=(RUN/scene/name).stat().st_size,structureDecimalExact=True) for name in ['download-input.json','download-response.json'])
    assert (RUN/'window/download-large-response.json').read_bytes()==json.loads((store/'large.json').read_text(encoding='utf-8'))['raw'].encode('utf-8')
    screenshots=[dict(path=p.relative_to(ROOT).as_posix(),sha256=sha(p),bytes=p.stat().st_size) for p in sorted((ROOT/'output/playwright/A01/final').rglob('*.png'))]
    summary['downloads']=downloads;summary['screenshots']=screenshots
    summary['source']='本轮最终JAR+生产dist+真实Chrome CLI，无mock/注入结果，native chooser/upload和download/saveAs实际执行'
    save('browser-summary',summary)
    nav=json.loads((store/'navigation.json').read_text(encoding='utf-8'))
    nav['cacheState']='not-controlled';nav['note']='首次及后续导航缓存均未控制；此前已有功能流程，不能称冷启动。原观测数值不改。'
    large=json.loads((store/'large.json').read_text(encoding='utf-8'));large.pop('raw')
    metrics={name:json.loads((store/(name+'.json')).read_text(encoding='utf-8')).get('frontendMetrics',[]) for name in ['window','fit','simulation','retime']}
    save('frontend-performance',dict(navigation=nav,large=large,interactions=metrics,serviceComputeOnlyAvailable=False,note='HTTP传输包括服务计算/序列化/本机网络与Node代理；未暴露服务计算专用计时，不把HTTP当纯数学耗时。Node测试调用观测DOM完成含协议等待，不能解释为纯浏览器CPU。'))
    print('PASS browser/download collection',len(summary)-3,'flows',len(downloads),'download comparisons',len(screenshots),'screenshots')
    # 超过128KiB的原工具stdout留runtime并记录原字节SHA；不提交计算产物或以重排后的文本冒充原stdout。
    raw_dir=RUN/'browser-stdout';raw_dir.mkdir(exist_ok=True)
    for path in OUT.glob('chrome-*.txt'):
        if path.stat().st_size>131072:
            target=raw_dir/path.name
            if target.exists():target=raw_dir/(path.stem+'-revision-'+hashlib.sha256(path.read_bytes()).hexdigest()[:8]+path.suffix)
            assert not target.exists();path.rename(target)
            exit_file=OUT/(path.stem+'-exit.json')
            if exit_file.exists():
                value=json.loads(exit_file.read_text(encoding='utf-8'));assert value['stdoutSha256']==sha(target);value['stdoutPath']=str(target);value['stdoutStorage']='大原文留ignored runtime，原字节未改';exit_file.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')

if __name__=='__main__':main()
