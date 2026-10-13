# 将已验证构建分成后端、独立静态页及外置 Orekit 数据；目标必须为新目录，拒绝覆盖运行包。
param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path $PSScriptRoot -Parent
$packageRoot = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $packageRoot) { throw '交付目标已存在，请选择新的目录，不能覆盖活运行包' }
New-Item -ItemType Directory -Path "$packageRoot/backend", "$packageRoot/frontend", "$packageRoot/orekit-data", "$packageRoot/config" | Out-Null
Copy-Item -LiteralPath "$sourceRoot/backend/target/space-target-visible-1.0.0-SNAPSHOT.jar" -Destination "$packageRoot/backend/server.jar"
Copy-Item -Path "$sourceRoot/frontend/dist/*" -Destination "$packageRoot/frontend" -Recurse
Copy-Item -Path "$sourceRoot/backend/orekit-data/*" -Destination "$packageRoot/orekit-data" -Recurse
Copy-Item -LiteralPath "$PSScriptRoot/application-deploy.yml" -Destination "$packageRoot/config"
Copy-Item -LiteralPath "$PSScriptRoot/start-backend.ps1", "$PSScriptRoot/nginx.conf", "$PSScriptRoot/部署说明.md" -Destination $packageRoot
Get-ChildItem -LiteralPath $packageRoot -File -Recurse | ForEach-Object {
    # 使用 .NET 标准 SHA256，兼容未提供 Get-FileHash cmdlet 的 Windows PowerShell 环境。
    $stream = [IO.File]::OpenRead($_.FullName)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $digest = [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $hash.Dispose() }
    [PSCustomObject]@{ path = $_.FullName.Substring($packageRoot.Length + 1).Replace('\','/'); sha256 = $digest }
} | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$packageRoot/manifest.json" -Encoding UTF8
