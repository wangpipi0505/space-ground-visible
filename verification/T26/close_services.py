"""对本项明确命名的独立服务执行context.close，记录自然退出码、关闭日志和端口复绑。"""
import ctypes
from ctypes import wintypes
import hashlib
import json
from pathlib import Path
import socket
import time

ROOT = Path(__file__).resolve().parents[2]
kernel = ctypes.WinDLL('kernel32', use_last_error=True)
kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
kernel.CloseHandle.argtypes = [wintypes.HANDLE]
results = []
for name, port in [('verified-nio', 18126), ('verified-nio2', 18127)]:
    runtime = ROOT / 'verification/runtime/T26' / name
    command = json.loads((runtime / 'command.json').read_text(encoding='utf-8-sig'))
    handle = kernel.OpenProcess(0x100000 | 0x1000, False, command['pid'])
    assert handle, (name, ctypes.get_last_error())
    started = time.perf_counter()
    try:
        (runtime / 'STOP').write_text('context.close\n', encoding='utf-8')
        assert kernel.WaitForSingleObject(handle, 20000) == 0, name + ' 未在20秒内自然退出'
        exit_code = wintypes.DWORD()
        assert kernel.GetExitCodeProcess(handle, ctypes.byref(exit_code))
    finally:
        kernel.CloseHandle(handle)
    stdout = (runtime / 'stdout.log').read_text(encoding='utf-8')
    stderr = (runtime / 'stderr.log').read_text(encoding='utf-8')
    closing = stdout[stdout.index('T26_CONTEXT_CLOSE_BEGIN'):]
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))
    item = dict(name=name, pid=command['pid'], port=port, exitCode=exit_code.value,
                secondsToNaturalExit=time.perf_counter() - started,
                jarSha256=hashlib.sha256((runtime / 'server.jar').read_bytes()).hexdigest(),
                actualContextClose='T26_CONTEXT_CLOSE_END uncaught=0' in closing,
                mainReturned='T26_MAIN_RETURN' in closing, portRebound=True,
                nonDaemonRemaining='T26_NON_DAEMON_REMAINING' in closing,
                shutdownNpe='NullPointerException' in closing,
                shutdownAsyncCloseLogged='AsynchronousCloseException' in closing,
                stderrBytes=len(stderr.encode()), shutdownLog=closing)
    assert item['exitCode'] == 0 and item['actualContextClose'] and item['mainReturned']
    assert not item['nonDaemonRemaining'] and not item['shutdownNpe']
    results.append(item)
(ROOT / 'verification/T26/shutdown-summary.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(results, ensure_ascii=False, indent=2))
