"""A01隔离服务控制：正式不可覆写JAR+显式256MiB/4GiB堆，控制文件自然关闭并获取真实退出码。"""
from pathlib import Path
import ctypes
from ctypes import wintypes
import json, os, shutil, socket, subprocess, sys, time
if os.environ.get('A01_FINAL'):
    from acceptance_final import ROOT, OUT, RUN, PACKAGE, CLEAN, JAVA, sha, save, execute
else:
    from acceptance import ROOT, OUT, RUN, PACKAGE, CLEAN, JAVA, sha, save, execute

def start(name, fixture=False, extra=()):
    """新服务根拒绝覆盖；fixture仅来源既有非生产H2，fresh时真正新建库。"""
    server=RUN/name; assert not server.exists(),server
    server.mkdir(); (server/'database').mkdir()
    with socket.socket() as port: port.bind(('127.0.0.1',18140))
    loader=RUN/'managed-classes'; loader.mkdir(exist_ok=True)
    cp=(ROOT/'verification/runtime/T04/space-runner/classpath.txt').read_text().strip()
    if not (loader/'ManagedServer.class').exists():
        execute('managed-compile',[str(JAVA/'bin/javac.exe'),'-encoding','UTF-8','-cp',str(CLEAN/'backend/target/classes')+os.pathsep+cp,'-d',str(loader),str(ROOT/'verification/T26/ManagedServer.java')])
    source=ROOT/'verification/runtime/T27/final-regression/browser-fixture.mv.db'
    if fixture: shutil.copy2(source,server/'database/demo.mv.db')
    jar=PACKAGE/'backend/server.jar'
    command=[str(JAVA/'bin/java.exe'),'-Xms256m','-Xmx4g','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-Dt26.control='+str(server),'-Dloader.path='+str(loader),'-Dloader.main=ManagedServer','-Dorekit.data.path='+str(PACKAGE/'orekit-data'),'-cp',str(jar),'org.springframework.boot.loader.PropertiesLauncher','--spring.profiles.active=demo','--server.port=18140','--spring.datasource.url=jdbc:h2:file:'+(server/'database/demo').as_posix()+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1','--ballistic.files.root='+str(server/'files'),'--logging.file.name='+str(server/'backend.log'),*extra]
    # Spring重复命令行属性会逗号拼接，测试覆盖参数必须先移除同名默认属性。
    keys={x.split('=',1)[0] for x in extra if x.startswith('--')}
    command=[x for x in command[:-len(extra)] if x.split('=',1)[0] not in keys]+list(extra) if extra else command
    with (server/'stdout.log').open('wb') as stdout:
        process=subprocess.Popen(command,cwd=server,stdout=stdout,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    binding=dict(pid=process.pid,command=command,jarSha256=sha(jar),port=18140,freshDemo=not fixture and '--spring.datasource.url' not in keys,reusedExistingDatabase='--spring.datasource.url' in keys,actualDatasourceUrl=next(x.split('=',1)[1] for x in command if x.startswith('--spring.datasource.url=')),actualStorageRoot=next(x.split('=',1)[1] for x in command if x.startswith('--ballistic.files.root=')),fixtureSource=str(source) if fixture else None,fixtureSha256=sha(source) if fixture else None)
    save(name+'-binding',binding)
    deadline=time.monotonic()+90
    while not (server/'READY').exists():
        assert process.poll() is None,'服务启动失败'
        assert time.monotonic()<deadline,'READY超时';time.sleep(.2)
    print('READY',name,process.pid,flush=True)

def close(name):
    """只打开本轮已绑定PID句柄；先核对标识，再STOP、等待退出、端口复绑和JAR完整性。"""
    server=RUN/name; binding=json.loads((OUT/(name+'-binding.json')).read_text(encoding='utf-8'))
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD];kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)];kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x100000|0x1000,False,binding['pid']);assert handle,ctypes.get_last_error()
    (server/'STOP').write_text('A01 natural context.close\n',encoding='utf-8',newline='\n')
    assert kernel.WaitForSingleObject(handle,60000)==0,'自然退出超时'
    code=wintypes.DWORD();assert kernel.GetExitCodeProcess(handle,ctypes.byref(code));kernel.CloseHandle(handle)
    raw=(server/'stdout.log').read_bytes();(OUT/(name+'-start-stop.txt')).write_bytes(raw)
    with socket.socket() as port:port.bind(('127.0.0.1',18140))
    result=dict(pid=binding['pid'],naturalExitCode=code.value,contextCloseReturned=b'T26_CONTEXT_CLOSE_END uncaught=0' in raw,mainReturned=b'T26_MAIN_RETURN' in raw,nonDaemonRemaining=b'T26_NON_DAEMON_REMAINING' in raw,portRebind=True,jarUnchanged=sha(PACKAGE/'backend/server.jar')==binding['jarSha256'],warnLines=[line for line in raw.decode('utf-8',errors='replace').splitlines() if ' WARN ' in line or 'ERROR' in line])
    save(name+'-shutdown',result)
    assert code.value==0 and result['contextCloseReturned'] and result['mainReturned'] and not result['nonDaemonRemaining'] and result['jarUnchanged'],result
    print('CLOSED',name,code.value,flush=True)

def gateway():
    """Node标准库验收网关；明确不是Nginx部署验收，端口15140绑定正式dist。"""
    command=['node.exe',str(ROOT/'verification/T28/static-validation-gateway.cjs'),str(PACKAGE/'frontend'),'http://127.0.0.1:18140','15140']
    with (RUN/'gateway.log').open('wb') as stdout:process=subprocess.Popen(command,cwd=RUN,stdout=stdout,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    save('gateway-binding',dict(pid=process.pid,command=command,kind='Node标准库静态验收网关'))
    time.sleep(.5);assert process.poll() is None;print('GATEWAY',process.pid)

if __name__=='__main__':
    mode=sys.argv[1]
    if mode=='start':start(sys.argv[2],len(sys.argv)>3 and sys.argv[3]=='fixture',sys.argv[4:])
    elif mode=='close':close(sys.argv[2])
    elif mode=='gateway':gateway()
