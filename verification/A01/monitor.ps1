param([Parameter(Mandatory=$true)][int]$ProcessId,[Parameter(Mandatory=$true)][string]$Destination,[Parameter(Mandatory=$true)][string]$StopFile)
# A01资源监测：只读取已绑定PID，不重设CPU/堆或强制GC；每250ms保留真实进程工作集/PrivateBytes/CPU累计秒/线程.
Write-Output ('A01_MONITOR_BINDINGS '+(@{ProcessId=$ProcessId;Destination=$Destination;StopFile=$StopFile;BoundCount=$PSBoundParameters.Count}|ConvertTo-Json -Compress))
$ErrorActionPreference='Stop'
$writer=[System.IO.StreamWriter]::new($Destination,$false,[System.Text.UTF8Encoding]::new($false))
$writer.NewLine="`n"
$writer.WriteLine('timeEpochMs,workingSetBytes,privateBytes,cpuSec,threads,handles')
try {
  while (-not (Test-Path -LiteralPath $StopFile)) {
    $process=Get-Process -Id $ProcessId
    $writer.WriteLine(('{0},{1},{2},{3},{4},{5}' -f [DateTimeOffset]::Now.ToUnixTimeMilliseconds(),$process.WorkingSet64,$process.PrivateMemorySize64,$process.CPU.ToString([System.Globalization.CultureInfo]::InvariantCulture),$process.Threads.Count,$process.HandleCount))
    $writer.Flush()
    Start-Sleep -Milliseconds 250
  }
} finally { $writer.Dispose() }
