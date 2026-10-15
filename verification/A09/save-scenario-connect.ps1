$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

# 使用 STK Connect 的 SaveAs 命令另存；只允许操作本次验证场景。
$taskRoot = [Runtime.InteropServices.Marshal]::GetActiveObject('STK11.Application').Personality2
if ($null -eq $taskRoot.CurrentScenario -or $taskRoot.CurrentScenario.InstanceName -ne 'WebPayload_A09_20261015') {
    throw '活动场景不是本次 STK 验证场景，拒绝保存。'
}

$taskPath = 'G:\5-YG\space-ground-visible\verification\runtime\A09\WebPayload_A09_20261015'
$taskCommand = 'SaveAs / * "' + $taskPath + '"'
$taskResult = $taskRoot.ExecuteCommand($taskCommand)
$taskResponse = $null
if ($taskResult.Count -gt 0) {
    $taskResponse = $taskResult.Item(0)
}
$taskScenarioFile = $taskPath + '.sc'
$taskRecord = [pscustomobject]@{
    Scenario = $taskRoot.CurrentScenario.InstanceName
    Command = $taskCommand
    ResponseCount = $taskResult.Count
    Response = $taskResponse
    ScenarioFile = $taskScenarioFile
    Exists = Test-Path -LiteralPath $taskScenarioFile
}
$taskRecord | ConvertTo-Json | Set-Content -LiteralPath 'G:\5-YG\space-ground-visible\verification\runtime\A09\saved-scenario-connect.json' -Encoding UTF8
if (-not $taskRecord.Exists) {
    throw 'STK Connect SaveAs 没有生成场景文件。'
}
$taskRecord | ConvertTo-Json -Compress
