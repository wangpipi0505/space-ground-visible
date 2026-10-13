param([int]$Port = 18128, [string]$Name = 'nio', [switch]$Nio2)
$ErrorActionPreference = 'Stop'
# 从候选正式包加载应用；探针只提供受控关闭入口，数据库/文件/日志完全按服务名隔离。
$projectRoot = 'G:/5-YG/space-ground-visible'
$runtime = Join-Path $projectRoot "verification/runtime/T27/$Name"
$javaHomeT27 = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot'
$probeClasses = Join-Path $projectRoot 'verification/runtime/T27/managed-classes'
$candidateJar = Join-Path $projectRoot 'verification/runtime/T27/space-target-visible-T27.jar'
# Boot会按需从JAR读尚未加载的类；运行包不能随公共候选重新打包而覆盖，每个服务独立冻结副本。
$jarPath = Join-Path $runtime 'server.jar'
New-Item -ItemType Directory -Force $runtime, $probeClasses | Out-Null
if (Test-Path -LiteralPath (Join-Path $runtime 'STOP')) { throw '该服务目录已存在STOP，请为新一轮使用新Name' }
if (Test-Path -LiteralPath $jarPath) { throw '该服务目录已有冻结运行包，请使用新Name，避免覆盖在运行的JAR' }
Copy-Item -LiteralPath $candidateJar -Destination $jarPath
$compileClasspath = (Join-Path $projectRoot 'backend/target/classes') + ';' + (Get-Content -LiteralPath (Join-Path $projectRoot 'verification/runtime/T20/ballistic-runner/classpath.txt') -Raw).Trim()
& "$javaHomeT27/bin/javac.exe" -encoding UTF-8 -cp $compileClasspath -d $probeClasses (Join-Path $projectRoot 'verification/T26/ManagedServer.java')
if ($LASTEXITCODE -ne 0) { throw '验证入口编译失败' }
$arguments = @('-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai',
    "-Dt26.control=$runtime", "-Dloader.path=$probeClasses", '-Dloader.main=ManagedServer', '-cp', $jarPath,
    'org.springframework.boot.loader.PropertiesLauncher', '--spring.profiles.active=demo', "--server.port=$Port",
    "--ballistic.files.root=$runtime/storage", "--logging.file.name=$runtime/backend.log",
    "--spring.datasource.url=jdbc:h2:file:$runtime/demo-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
if ($Nio2) { $arguments += '--space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol' }
$process = Start-Process -FilePath "$javaHomeT27/bin/java.exe" -ArgumentList $arguments -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log')
$process.Id | Set-Content -LiteralPath (Join-Path $runtime 'pid.txt')
@{ pid = $process.Id; executable = "$javaHomeT27/bin/java.exe"; arguments = $arguments; jarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash; stopPath = (Join-Path $runtime 'STOP') } | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $runtime 'command.json')
Write-Output "PID=$($process.Id) PORT=$Port STOP=$runtime/STOP"
