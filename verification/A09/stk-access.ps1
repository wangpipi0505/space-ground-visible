param([string]$OutputDirectory='G:/5-YG/space-ground-visible/verification/runtime/A09/stk-results')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
[Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::InvariantCulture
Add-Type -Path 'C:/Program Files/AGI/STK 11/bin/Primary Interop Assemblies/AGI.STKUtil.Interop.dll'
Add-Type -Path 'C:/Program Files/AGI/STK 11/bin/Primary Interop Assemblies/AGI.STKObjects.Interop.dll'
$taskRoot=([Runtime.InteropServices.Marshal]::GetActiveObject('STK11.Application')).Personality2
if ($taskRoot.CurrentScenario.InstanceName -ne 'WebPayload_A09_20261015') {throw '拒绝操作非本次验证场景'}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$taskScenario=$taskRoot.CurrentScenario
$taskRoot.UnitPreferences.SetCurrentUnit('DateFormat','UTCG')
$taskRoot.UnitPreferences.SetCurrentUnit('DistanceUnit','m')
$taskRoot.UnitPreferences.SetCurrentUnit('AngleUnit','deg')
$taskRoot.UnitPreferences.SetCurrentUnit('TimeUnit','sec')
$taskScenario.Epoch='1 Jul 2026 08:45:00.000'
$taskScenario.SetTimePeriod('1 Jul 2026 08:44:59.999','1 Jul 2026 08:59:16.000')

# 星历是双方共同输入，Access由STK实际求解，绝不导入软件可见区间作为STK窗口。
foreach($name in @('SAT_A','SAT_B')) {
    $object=$taskRoot.GetObjectFromPath('Satellite/'+$name)
    $object.Propagator.Filename='G:/5-YG/space-ground-visible/verification/runtime/A09/inputs/'+$name+'.e'
    $object.Propagator.Propagate()
}
$taskTarget=$taskRoot.GetObjectFromPath('Missile/TARGET')
$taskTarget.Trajectory.Filename='G:/5-YG/space-ground-visible/verification/runtime/A09/inputs/TARGET.e'
$taskTarget.Trajectory.Propagate()
$taskRoot.UnitPreferences.SetCurrentUnit('DateFormat','EpSec')
$taskSamples=@(Import-Csv -LiteralPath 'G:/5-YG/space-ground-visible/verification/runtime/A09/inputs/samples.csv')
$taskStart=[double]$taskSamples[0].absoluteJ2000Sec-836167500.0
$taskStop=[double]$taskSamples[-1].absoluteJ2000Sec-836167500.0

function Export-Result($result,[string]$path) {
    $fields=@();$values=@()
    for($i=0;$i -lt $result.DataSets.Count;$i++) {
        $data=$result.DataSets.Item($i);$fields+=@([string]$data.ElementName);$values+=,@($data.GetValues())
    }
    $rows=@()
    if($values.Count -gt 0) {
        for($i=0;$i -lt $values[0].Count;$i++) {
            $row=[ordered]@{}
            for($j=0;$j -lt $fields.Count;$j++) {
                $value=$values[$j][$i]
                $row[$fields[$j]]=if($value -is [double]) {$value.ToString('R',[Globalization.CultureInfo]::InvariantCulture)} else {[string]$value}
            }
            $rows+=,[pscustomobject]$row
        }
    }
    if($rows.Count -gt 0) {$rows | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8}
    else {[IO.File]::WriteAllText($path,($fields -join ',')+"`n",(New-Object Text.UTF8Encoding($false)))}
    return [pscustomobject]@{Path=$path;Rows=$rows.Count;Fields=$fields}
}
function Export-AngularRate($access,[string]$path) {
    # 从 STK 的 Constraint Data 读取 Access 约束自身使用的惯性视线角速度。
    # 查询区间向内取整到整秒，使其与 Web 的一秒采样历元逐点对齐。
    $provider=$access.DataProviders.Item('Constraint Data')
    $intervals=$access.ComputedAccessIntervalTimes
    $rows=@()
    for($index=0;$index -lt $intervals.Count;$index++) {
        $start=$null;$stop=$null
        $intervals.GetInterval($index,[ref]$start,[ref]$stop)
        $sampleStart=[Math]::Ceiling([double]$start)
        $sampleStop=[Math]::Floor([double]$stop)
        if($sampleStart -gt $sampleStop) {continue}
        $result=$provider.ExecElements($sampleStart,$sampleStop,1,[object[]]@('Time','FromAngularRate'))
        $times=$result.DataSets.Item(0).GetValues()
        $rates=$result.DataSets.Item(1).GetValues()
        for($rowIndex=0;$rowIndex -lt $times.Count;$rowIndex++) {
            $rows+=,[pscustomobject]@{
                tSec=([double]$times[$rowIndex]).ToString('R',[Globalization.CultureInfo]::InvariantCulture)
                stkFromAngularRateDegPerSec=([double]$rates[$rowIndex]).ToString('R',[Globalization.CultureInfo]::InvariantCulture)
            }
        }
        # 输入目标最后一个星历时刻可能不是整秒（如 855.9000002 秒）；单独导出 STK 连续弧段终点，
        # 后续按 20 微秒容差与 Web 同一终点采样匹配，避免把数值尾差误判成缺样。
        $endpointTime=[double]$stop
        if([Math]::Abs($endpointTime-[Math]::Round($endpointTime)) -gt 0.000000001) {
            $endpoint=$provider.ExecElements($endpointTime,$endpointTime,1,[object[]]@('Time','FromAngularRate'))
            if($endpoint.DataSets.Item(0).GetValues().Count -gt 0) {
                $rows+=,[pscustomobject]@{
                    tSec=([double]$endpoint.DataSets.Item(0).GetValues()[0]).ToString('R',[Globalization.CultureInfo]::InvariantCulture)
                    stkFromAngularRateDegPerSec=([double]$endpoint.DataSets.Item(1).GetValues()[0]).ToString('R',[Globalization.CultureInfo]::InvariantCulture)
                }
            }
        }
    }
    if($rows.Count -gt 0) {$rows | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8}
    else {[IO.File]::WriteAllText($path,"tSec,stkFromAngularRateDegPerSec`n",(New-Object Text.UTF8Encoding($false)))}
    return [pscustomobject]@{Path=$path;Rows=$rows.Count;Field='Constraint Data.FromAngularRate';Unit='deg/sec'}
}
function Set-MinMax($sat,[AGI.STKObjects.AgEAccessConstraints]$type,[double]$min,[double]$max) {
    $constraint=$sat.AccessConstraints.AddConstraint($type)
    $constraint.EnableMin=$true;$constraint.Min=$min
    $constraint.EnableMax=$true;$constraint.Max=$max
}
$taskRecords=@()
# C5非对称方向诊断先做，最后保留C4完整默认条件，便于用户在GUI直接查看。
foreach($case in @('C0','C1','C2','C3','C5','C4')) {
    foreach($name in @('SAT_A','SAT_B')) {
        $sat=$taskRoot.GetObjectFromPath('Satellite/'+$name)
        foreach($type in @(3,6,14,29,34)) {
            if($sat.AccessConstraints.IsConstraintActive($type)) {$sat.AccessConstraints.RemoveConstraint($type)}
        }
        if($case -in @('C1','C2','C3','C4')) {Set-MinMax $sat 34 1000000 4000000}
        if($case -in @('C2','C3','C4')) {Set-MinMax $sat 6 90 270;Set-MinMax $sat 14 -90 0}
        if($case -eq 'C5') {Set-MinMax $sat 6 160 260}
        if($case -in @('C3','C4')) {
            $rate=$sat.AccessConstraints.AddConstraint(3);$rate.EnableMin=$false;$rate.EnableMax=$true;$rate.Max=0.3
        }
        if($case -eq 'C4') {$sun=$sat.AccessConstraints.AddConstraint(29);$sun.Angle=90.0}
        $access=$sat.GetAccessToObject($taskTarget)
        # STK11在关闭光行时后不允许修改像差，故先设置像差再关闭。
        $access.Advanced.EnableLightTimeDelay=$true
        $access.Advanced.AberrationType=[AGI.STKObjects.AgEAberrationType]::eAberrationNone
        $access.Advanced.EnableLightTimeDelay=$false
        $access.Advanced.UseFixedTimeStep=$true;$access.Advanced.FixedStepSize=1.0
        $access.Advanced.UsePreciseEventTimes=$true;$access.Advanced.TimeConvergence=0.00001
        $access.SpecifyAccessTimePeriod($taskStart,$taskStop)
        $access.ComputeAccess()
        $result=$access.DataProviders.Item('Access Data').Exec($taskStart,$taskStop)
        $record=Export-Result $result (Join-Path $OutputDirectory ($case+'-'+$name+'-access.csv'))
        $rateRecord=$null
        if($case -in @('C3','C4')) {
            $rateRecord=Export-AngularRate $access (Join-Path $OutputDirectory ($case+'-'+$name+'-angular-rate.csv'))
        }
        $taskRecords+=,[pscustomobject]@{Case=$case;Satellite=$name;Result=$record;AngularRate=$rateRecord;LightTime=$access.Advanced.EnableLightTimeDelay;Aberration=$access.Advanced.AberrationType;Constraints=@($sat.AccessConstraints | ForEach-Object {$_.ConstraintName})}
        Write-Output ($case+' '+$name+' STK_ACCESS_INTERVALS='+$record.Rows)
        if($case -eq 'C0') {
            $aer=$access.DataProviders.Item('AER Data').Group.Item('Default').Exec($taskStart,$taskStop,1)
            Export-Result $aer (Join-Path $OutputDirectory ($name+'-aer.csv')) | Out-Null
            $access.DataProviders.GetSchema() | Set-Content -LiteralPath (Join-Path $OutputDirectory ($name+'-access-schema.xml')) -Encoding UTF8
        }
    }
}
$taskRecords | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'access-bindings.json') -Encoding UTF8
$taskRoot.UnitPreferences.SetCurrentUnit('DateFormat','UTCG')
$taskRoot.Rewind()
