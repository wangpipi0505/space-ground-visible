param([string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('T08', 'T09')][string]$ValidationTask = 'T08')

# T08/T09 可重复的普通 Java8 验证；仅编译纯领域类型和原 provider，不编译应用服务/实体。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot "verification/runtime/$ValidationTask/standalone"
$evidenceDirectory = Join-Path $projectRoot "verification/$ValidationTask"
New-Item -ItemType Directory -Force $runtimeDirectory, $evidenceDirectory | Out-Null
if (!(Test-Path -LiteralPath "$JavaHome/bin/javac.exe")) {
    throw '请通过 -JavaHome 指定 Java8 JDK'
}
$env:JAVA_HOME = $JavaHome
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
$version = & "$JavaHome/bin/javac.exe" -version 2>&1
if ("$version" -notmatch 'javac 1\.8\.') { throw "需要Java8，实际：$version" }
$version | Set-Content -Encoding UTF8 (Join-Path $evidenceDirectory 'plain-java-version.log')

# 仅解析 Orekit/Hipparchus、原初始化器使用的 SLF4J 及日志实现；无 Spring/MyBatis/JUnit。
$classpathFile = Join-Path $runtimeDirectory 'classpath.txt'
& mvn -f "$projectRoot/backend/pom.xml" dependency:build-classpath '-DincludeScope=runtime' `
    '-DincludeGroupIds=org.orekit,org.hipparchus,org.slf4j,ch.qos.logback' `
    "-Dmdep.outputFile=$classpathFile" *> (Join-Path $evidenceDirectory 'plain-java-dependencies.log')
if ($LASTEXITCODE -ne 0) { throw '独立依赖解析失败，见日志' }
$dependencies = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
if ($dependencies -match 'spring|mybatis|junit') { throw '独立类路径混入应用/测试依赖' }
$sourceRoot = Join-Path $projectRoot 'backend/src/main/java/com/runisys/orbit/spacevisibility/domain'
$sources = @("$sourceRoot/geometry/Vector3.java")
foreach ($type in @('OrbitTime', 'OrbitDefinition', 'OrbitState', 'OrbitPropagationTool',
        'OrbitStateProvider', 'TleOrbitStateProvider', 'KeplerOrbitStateProvider', 'SpaceOrbitStateProvider',
        'OrekitDataInitializer')) {
    $sources += "$sourceRoot/orbit/$type.java"
}
$sources += Join-Path $evidenceDirectory 'PlainJavaOrbitExample.java'
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -cp $dependencies -d $runtimeDirectory @sources `
    *> (Join-Path $evidenceDirectory 'plain-java-compile.log')
if ($LASTEXITCODE -ne 0) { throw '普通Java独立编译失败，见日志' }
$classpath = "$runtimeDirectory;$projectRoot/backend/src/main/resources;$dependencies"
& "$JavaHome/bin/java.exe" '-Dfile.encoding=UTF-8' '-Djava.awt.headless=true' `
    "-Dorekit.data.path=$projectRoot/backend/orekit-data" -cp $classpath PlainJavaOrbitExample `
    *> (Join-Path $evidenceDirectory 'plain-java-run.log')
if ($LASTEXITCODE -ne 0) { throw '普通Java验证失败，见日志' }
Get-Content -LiteralPath (Join-Path $evidenceDirectory 'plain-java-run.log')
