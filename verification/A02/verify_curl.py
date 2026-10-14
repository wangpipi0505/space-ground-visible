"""用临时标准库HTTP接收器核验PowerShell示例的curl参数；不冒充业务服务验收。"""
import hashlib
import json
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from email.parser import BytesParser
from email import policy
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

def assert_transfer(case,event):
    """核对真实接收字节和媒体类型；结构相等不能替代 JSON 请求文件的逐字节一致。"""
    if case['body'] is not None:
        argument=case['arguments'][case['arguments'].index('--data-binary')+1]
        assert argument.startswith('@'),case['operation']+' 未使用原请求文件'
        request_path=ROOT/argument[1:]
        original=request_path.read_bytes()
        assert event['body']==original,case['operation']+' JSON 请求文件字节不同'
        header=case['arguments'][case['arguments'].index('-H')+1]
        expected_type=header.split(':',1)[1].strip()
        assert event['contentType']==expected_type,case['operation']+' Content-Type 不同'
        assert json.loads(original.decode('utf-8'))==case['body'],case['operation']+' 请求文件结构不同'
        return {'requestFile':argument[1:],'requestFileSha256':hashlib.sha256(original).hexdigest(),
                'jsonOriginalBytesEqual':True,'contentTypeEqual':True}
    if '-F' in case['arguments']:
        content_type=event['contentType'] or ''
        message=BytesParser(policy=policy.default).parsebytes(
            ('Content-Type: '+content_type+'\r\nMIME-Version: 1.0\r\n\r\n').encode('ascii')+event['body'])
        assert message.get_content_type()=='multipart/form-data' and message.get_boundary(),case['operation']+' multipart 媒体类型无效'
        files=[part for part in message.iter_parts() if part.get_param('name',header='content-disposition')=='file']
        assert len(files)==1 and files[0].get_filename()=='trajectory-0.1s-standard.csv'
        original=(ROOT/'test-data/reference/ballistic/trajectory-0.1s-standard.csv').read_bytes()
        assert files[0].get_payload(decode=True)==original,case['operation']+' multipart CSV 字节不同'
        return {'multipartCsvOriginalBytesEqual':True,'multipartContentTypeValid':True,
                'sourceCsvSha256':hashlib.sha256(original).hexdigest()}
    assert not event['body'] and event['contentType'] is None,case['operation']+' 无请求体操作的字节/媒体类型不符'
    return {'emptyBodyAndNoContentType':True}


def main():
    """启动独立 echoReceiver，运行 35 条真实 PowerShell/curl 后关闭本项监听器。"""
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
            proof=assert_transfer(case,event)
            results.append({'operation':case['operation'],'exitCode':p.returncode,'method':event['method'],'path':event['path'],'contentType':event['contentType'],'transferAssertions':proof,'bodyBytes':len(event['body']),'bodySha256':hashlib.sha256(event['body']).hexdigest(),'stdout':p.stdout.decode('utf-8'),'stderr':p.stderr.decode('utf-8'),'passed':True})
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    output={'passed':all(x['passed'] for x in results) and len(results)==35,'curlOperations':len(results),'scope':'只核验PowerShell/curl.exe方法、路径、query、JSON请求文件原字节/Content-Type和multipart CSV原字节/媒体类型；echoReceiver只检查传输语法，不是新业务成功证明；不是业务HTTP重跑，不证明当前动态引用仍可用。','listenerClosed':not thread.is_alive(),'results':results}
    (ROOT/'verification/A02/curl-result.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in output.items() if k!='results'},ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
