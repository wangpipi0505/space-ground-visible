# 前后端分别构建；不复制 Vue 产物到 Maven resources，不升级依赖锁。
param([ValidateSet('backend','frontend','all')][string]$Component = 'all', [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path $PSScriptRoot -Parent
if ($Component -in @('backend','all')) {
    if (-not $JavaHome) { throw '请指定 Java 8 的 -JavaHome 或 JAVA_HOME' }
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
    # Java 把正常版本信息写到 stderr；用标准 Process 读取，避免 Windows PowerShell 将其当成失败。
    $versionProcess = New-Object System.Diagnostics.Process
    $versionProcess.StartInfo.FileName = "$env:JAVA_HOME/bin/java.exe"
    $versionProcess.StartInfo.Arguments = '-version'
    $versionProcess.StartInfo.UseShellExecute = $false
    $versionProcess.StartInfo.CreateNoWindow = $true
    $versionProcess.StartInfo.RedirectStandardError = $true
    [void]$versionProcess.Start()
    $version = $versionProcess.StandardError.ReadToEnd()
    $versionProcess.WaitForExit()
    $versionProcess.Dispose()
    if ($version -notmatch 'version "1\.8\.') { throw '后端必须使用 Java 8' }
    # 防止历史手工合并页进入独立 API 包；干净 checkout 中该路径本就不存在。
    if (Test-Path -LiteralPath "$sourceRoot/backend/src/main/resources/static") { throw '请在干净副本构建：存在历史生成 static' }
    Push-Location "$sourceRoot/backend"
    try { & mvn.cmd -B clean verify; if ($LASTEXITCODE) { throw '后端验证失败' } } finally { Pop-Location }
}
if ($Component -in @('frontend','all')) {
    Push-Location "$sourceRoot/frontend"
    try {
        & pnpm.cmd install --frozen-lockfile; if ($LASTEXITCODE) { throw '冻结锁安装失败' }
        & pnpm.cmd test; if ($LASTEXITCODE) { throw '前端测试失败' }
        & pnpm.cmd build; if ($LASTEXITCODE) { throw '前端构建失败' }
    } finally { Pop-Location }
}
