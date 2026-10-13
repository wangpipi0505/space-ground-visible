param([string]$JavaHome = $env:JAVA_HOME)

# T10 普通Java8独立验证：直接编译领域源码，运行classpath不带backend编译类/依赖JAR。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot 'verification/runtime/T10/standalone'
$evidenceDirectory = Join-Path $projectRoot 'verification/T10'
New-Item -ItemType Directory -Force $runtimeDirectory, $evidenceDirectory | Out-Null
if (!(Test-Path -LiteralPath "$JavaHome/bin/javac.exe")) {
    throw '请通过 -JavaHome 指定 Java8 JDK'
}
# javac 把成功的版本信息写到stderr；隐藏子进程读取，避免PS5判为NativeCommandError。
$versionLog = Join-Path $evidenceDirectory 'ballistic-java-version.log'
$versionProcess = Start-Process -FilePath "$JavaHome/bin/javac.exe" -ArgumentList '-version' -WindowStyle Hidden -RedirectStandardError $versionLog -Wait -PassThru
if ($versionProcess.ExitCode -ne 0) {
    throw '读取Java编译器版本失败'
}
$version = Get-Content -LiteralPath $versionLog -Raw
if ($version -notmatch 'javac 1\.8\.') {
    throw '本验证必须实际使用Java8编译器'
}
$domainRoot = Join-Path $projectRoot 'backend/src/main/java/com/runisys/orbit/spacevisibility/domain'
# 同时独立编译T12纯JDK时间/坐标主体，确保抽取未引入Orekit、Spring或旧兼容核心依赖。
$sources = @("$domainRoot/geometry/Vector3.java", "$domainRoot/time/LegacyJ2000Time.java",
    "$domainRoot/geometry/LegacyJ2000Frames.java", "$domainRoot/geometry/TemeEarthFixedFrames.java")
foreach ($type in @('OrbitTime', 'OrbitDefinition', 'OrbitState', 'OrbitPropagationTool',
        'BallisticOrbitPropagation', 'BallisticCartesianPropagation')) {
    $sources += "$domainRoot/orbit/$type.java"
}
$sources += Join-Path $evidenceDirectory 'PlainJavaBallisticExample.java'
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -d $runtimeDirectory @sources > (Join-Path $evidenceDirectory 'ballistic-java-compile.log') 2>&1
if ($LASTEXITCODE -ne 0) {
    throw '弹道独立领域源码编译失败，见ballistic-java-compile.log'
}
& "$JavaHome/bin/java.exe" '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' -cp $runtimeDirectory PlainJavaBallisticExample (Join-Path $projectRoot 'test-data/baseline/ballistic-propagation') > (Join-Path $evidenceDirectory 'ballistic-java-run.log') 2>&1
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath (Join-Path $evidenceDirectory 'ballistic-java-run.log') -Tail 12
    throw '弹道工具独立Java8数值对照失败'
}
Get-Content -LiteralPath (Join-Path $evidenceDirectory 'ballistic-java-run.log')
