# 在任意 cwd 启动交付包；JAR/数据只读，数据库、文件及日志写入独立运行根。
param([Parameter(Mandatory=$true)][string]$JavaHome, [Parameter(Mandatory=$true)][string]$RuntimeRoot,
      [int]$Port = 8080, [string]$Profile = '', [string]$AllowedOrigins = '')
$ErrorActionPreference = 'Stop'
$javaExe = Join-Path (Resolve-Path -LiteralPath $JavaHome).Path 'bin/java.exe'
# 正常版本信息写入 stderr，在 Windows PowerShell 下也应可靠识别 Java 8。
$versionProcess = New-Object System.Diagnostics.Process
$versionProcess.StartInfo.FileName = $javaExe
$versionProcess.StartInfo.Arguments = '-version'
$versionProcess.StartInfo.UseShellExecute = $false
$versionProcess.StartInfo.CreateNoWindow = $true
$versionProcess.StartInfo.RedirectStandardError = $true
[void]$versionProcess.Start()
$version = $versionProcess.StandardError.ReadToEnd()
$versionProcess.WaitForExit()
$versionProcess.Dispose()
if ($version -notmatch 'version "1\.8\.') { throw '后端必须使用 Java 8' }
$runtimePath = [IO.Path]::GetFullPath($RuntimeRoot)
New-Item -ItemType Directory -Force -Path $runtimePath, "$runtimePath/logs", "$runtimePath/database" | Out-Null
$env:OREKIT_DATA_PATH = (Resolve-Path -LiteralPath "$PSScriptRoot/orekit-data").Path
$env:BALLISTIC_STORAGE_ROOT = "$runtimePath/files"
$env:BALLISTIC_UPLOADS_DIR = "$runtimePath/files/uploads"
$env:BALLISTIC_RESULTS_DIR = "$runtimePath/files/results"
$env:BALLISTIC_TEMP_DIR = "$runtimePath/files/temp"
$env:BALLISTIC_METADATA_DIR = "$runtimePath/files/metadata"
$env:DEMO_DB_PATH = "$runtimePath/database/demo-db"
$env:LOG_FILE = "$runtimePath/logs/backend.log"
$arguments = @('-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-jar', "$PSScriptRoot/backend/server.jar", "--server.port=$Port", "--spring.config.additional-location=file:$PSScriptRoot/config/application-deploy.yml")
# 省略参数时保留外置配置与 FRONTEND_ALLOWED_ORIGINS；显式传空串仍允许部署方主动关闭跨域。
# Spring 的命令行优先级最高，不能把参数默认空值无条件传入并覆盖真实部署配置。
if ($PSBoundParameters.ContainsKey('AllowedOrigins')) { $arguments += "--space.visibility.web.allowed-origins=$AllowedOrigins" }
if ($Profile) { $arguments += "--spring.profiles.active=$Profile" }
# 前台运行便于服务管理器负责生命周期；用户可 Ctrl+C 触发 Spring 正常关闭。
& $javaExe @arguments
exit $LASTEXITCODE
