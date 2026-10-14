"""用临时标准库HTTP接收器核验PowerShell示例的curl参数；不冒充业务服务验收。"""
import hashlib
import json
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
cases=json.loads((ROOT/'verification/A02/curl-cases.json').read_text(encoding='utf-8'))
received=[]

class Receiver(BaseHTTPRequestHandler):
    def handle_request(self):
        size=int(self.headers.get('Content-Length',0))
        body=self.rfile.read(size)
        received.append({'method':self.command,'path':self.path,'contentType':self.headers.get('Content-Type'),'body':body})
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.end_headers()
        self.wfile.write(b'{"transportReceived":true}')
    do_GET=do_POST=do_PUT=do_DELETE=handle_request
    def log_message(self,*args): pass

server=ThreadingHTTPServer(('127.0.0.1',0),Receiver)
thread=threading.Thread(target=server.serve_forever,daemon=True)
thread.start()
results=[]
try:
    for case in cases:
        path=case['path']
        for name,value in [('satelliteId','1'),('taskId','syntax-task'),('id','2077206351027527681'),('fileId','syntax-file')]:
            path=path.replace('{'+name+'}',value)
        if case['query']: path+='?'+case['query']
        url='http://127.0.0.1:'+str(server.server_port)+path
        # 真实通过PowerShell解析同一组单引号字面参数，验证不会被curl别名/JSON shell转义改变。
        args=case['arguments']+['--silent','--show-error',url]
        expression='& curl.exe '+' '.join("'"+arg.replace("'","''")+"'" for arg in args)
        p=subprocess.run(['powershell','-NoLogo','-NoProfile','-NonInteractive','-Command',expression],cwd=ROOT,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
        event=received[-1]
        assert p.returncode==0,(case['operation'],p.returncode,p.stderr)
        assert event['method']==case['method'] and event['path']==path,event
        if case['body'] is not None:
            assert json.loads(event['body'].decode('utf-8'))==case['body'],case['operation']
        elif '-F' in case['arguments']:
            assert b'filename="trajectory-0.1s-standard.csv"' in event['body'] and b'name="file"' in event['body']
            original=(ROOT/'test-data/reference/ballistic/trajectory-0.1s-standard.csv').read_bytes()
            assert original in event['body']
        results.append({'operation':case['operation'],'exitCode':p.returncode,'method':event['method'],'path':event['path'],'bodyBytes':len(event['body']),'bodySha256':hashlib.sha256(event['body']).hexdigest(),'stdout':p.stdout.decode('utf-8'),'stderr':p.stderr.decode('utf-8'),'passed':True})
finally:
    server.shutdown()
    server.server_close()
    thread.join()
output={'passed':all(x['passed'] for x in results) and len(results)==35,'curlOperations':len(results),'scope':'只核验PowerShell/curl.exe方法、路径、query、JSON原字节和multipart；不是业务HTTP重跑，不证明当前动态引用仍可用。','listenerClosed':not thread.is_alive(),'results':results}
(ROOT/'verification/A02/curl-result.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in output.items() if k!='results'},ensure_ascii=False,indent=2))
