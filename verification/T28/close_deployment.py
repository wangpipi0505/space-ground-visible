"""真实 context.close 后等待自然退出码；静态网关按精确PID和命令身份清理，逐端口复绑。"""
import ctypes
from ctypes import wintypes
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/T28/independent-server'
EVIDENCE = ROOT / 'verification/T28'
kernel = ctypes.WinDLL('kernel32', use_last_error=True)
kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
kernel.CloseHandle.argtypes = [wintypes.HANDLE]

command = json.loads((RUNTIME / 'command.json').read_text(encoding='utf-8'))
handle = kernel.OpenProcess(0x100000 | 0x1000, False, command['pid'])
assert handle
started = time.perf_counter()
try:
    (RUNTIME / 'STOP').write_text('context.close\n', encoding='utf-8')
    assert kernel.WaitForSingleObject(handle, 20000) == 0
    code = wintypes.DWORD()
    assert kernel.GetExitCodeProcess(handle, ctypes.byref(code)) and code.value == 0
finally:
    kernel.CloseHandle(handle)
stdout = (RUNTIME / 'stdout.log').read_text(encoding='utf-8')
closing = stdout[stdout.index('T26_CONTEXT_CLOSE_BEGIN'):]
assert 'T26_CONTEXT_CLOSE_END uncaught=0' in closing and 'T26_MAIN_RETURN' in closing
assert 'NullPointerException' not in closing and 'T26_NON_DAEMON_REMAINING' not in closing
gateway = json.loads((RUNTIME / 'gateway-command.json').read_text(encoding='utf-8'))
gateway_pid = gateway['pid']
# 身份校验和结束都在同一 PowerShell 完成，不能只按端口误杀其它服务。
script = "$p=Get-CimInstance Win32_Process -Filter 'ProcessId=" + str(gateway_pid) + "'; if(-not $p -or $p.Name -ne 'node.exe' -or $p.CommandLine -notlike '*static-validation-gateway.cjs*deployment-package-v2*15130*'){throw '网关PID身份不符'}; $p | Select-Object ProcessId,Name,CommandLine | ConvertTo-Json; Stop-Process -Id $p.ProcessId -ErrorAction Stop"
cleanup = subprocess.run(['pwsh.exe', '-NoProfile', '-Command', script], capture_output=True)
(EVIDENCE / 'gateway-cleanup.txt').write_bytes(cleanup.stdout + cleanup.stderr)
assert cleanup.returncode == 0
for port in (18130, 15130):
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))
summary = dict(passed=True, serverPid=command['pid'], exitCode=code.value, actualContextClose=True, naturalMainReturn=True, closeSeconds=time.perf_counter()-started, shutdownLog=closing, serverJarSha256=hashlib.sha256((ROOT/'verification/runtime/T28/deployment-package-v2/backend/server.jar').read_bytes()).hexdigest(), gatewayPid=gateway_pid, gatewayIdentityVerified=True, gatewayForcedProcessCleanup=True, reboundPorts=[18130,15130])
(EVIDENCE / 'shutdown-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(passed=True, exitCode=code.value, actualContextClose=True, reboundPorts=[18130,15130]), ensure_ascii=False))
