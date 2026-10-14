async page => {
  const assert=(ok,text)=>{if(!ok)throw new Error(text)}
  const view=page.locator('.ballistic-retime-view')
  assert(await view.locator('[data-retime-field=targetId]').inputValue()==='RET-001'&&await view.locator('[data-retime-field=launchTimeUtc]').inputValue()==='2026-07-01 09:00:00.000','重定时默认不符')
  await view.screenshot({path:'G:/5-YG/space-ground-visible/verification/A06/retime-defaults.png'})
  const pending=page.waitForResponse(r=>r.url().endsWith('/api/trajectory/retime')&&r.request().method()==='POST',{timeout:120000})
  await view.locator('[data-test=retime-compute]').click()
  const response=await pending,body=await response.json(),request=response.request().postDataJSON()
  assert(response.status()===200,`重定时失败${JSON.stringify(body)}`)
  assert(body.files[0].pointCount===2576&&request.targetId==='RET-001'&&request.launchTimeUtc==='2026-07-01 09:00:00.000','重定时请求/点数错误')
  await view.locator('[data-test=fit-trajectory]').waitFor()
  const csvDownload=page.waitForEvent('download');await view.locator('[data-test=fit-download]').click();await(await csvDownload).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A06/retime.csv')
  const resultDownload=page.waitForEvent('download');await view.getByRole('button',{name:'下载原重定时响应 JSON',exact:true}).click();await(await resultDownload).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A06/retime-response.json')
  await view.locator('[data-test=retime-success]').screenshot({path:'G:/5-YG/space-ground-visible/verification/A06/retime-success.png'})
  await page.getByRole('tab',{name:'可视窗口计算',exact:true}).click()
  const window=page.locator('.ballistic-payload-view')
  await window.locator('[data-test=default-base-utc]').fill('')
  await window.locator('[data-test=window-source]').selectOption('generated')
  const options=await window.locator('[data-test=generated-result-select] option').evaluateAll(items=>items.map(i=>({value:i.value,text:i.textContent})))
  assert(options.filter(i=>i.value).length===4,'三类来源未合并四个201引用')
  const selected=options.find(i=>i.text.includes('重定时/retimeTrajectory/RET-001'))
  assert(selected,'重定时结果未跨一级菜单供窗口')
  await window.locator('[data-test=generated-result-select]').selectOption(selected.value)
  for(const satellite of await window.locator('[data-satellite-id]').all())await satellite.locator('[data-orbit-field=epochUtc]').fill('2026/07/01 09:00:00.000')
  for(const star of await window.locator('[data-payload-satellite-id]').all()){
    await star.getByRole('button',{name:'新增载荷',exact:true}).click()
    const payload=star.locator('[data-payload-id]').last(),selector=payload.locator('[data-field=payloadType] .el-select')
    await selector.click();const listId=await selector.getByRole('combobox').getAttribute('aria-controls');await page.locator(`#${listId}`).getByRole('option',{name:'对地载荷',exact:true}).click()
    for(const [key,value] of [['minDistanceKm','0'],['maxDistanceKm','0'],['azimuthMinDeg',''],['azimuthMaxDeg',''],['elevationMinDeg',''],['elevationMaxDeg',''],['angleRateDegPerSec','180'],['sunAvoidHalfAngleDeg','0'],['startupAngleRateDegPerSec','-1']]){const input=payload.locator(`[data-field=${key}] input`);await input.fill(value);await input.press('Tab')}
  }
  await window.locator('[data-option=startOffsetSec]').fill('0');await window.locator('[data-option=numCalculations]').fill('1')
  const pendingWindow=page.waitForResponse(r=>r.url().endsWith('/api/orbit/visibility/search')&&r.request().method()==='POST',{timeout:120000})
  await window.locator('[data-test=compute-window]').click();const reuseResponse=await pendingWindow,reuseRequest=reuseResponse.request().postDataJSON(),reuseBody=await reuseResponse.json()
  assert(reuseResponse.status()===200&&reuseRequest.ballistics[0].targetId==='RET-001'&&!('baseLaunchUTC'in reuseRequest.ballistics[0]),'重定时引用/UTC复用错误')
  assert(reuseRequest.satellites.every(s=>s.payloads.length===2&&s.payloads[0].payloadType==='SPACE'&&s.payloads[1].payloadType==='GROUND'),'多星多载荷草稿未真实提交')
  const reusedDownload=page.waitForEvent('download');await window.getByRole('button',{name:'HTTP 下载原文件',exact:true}).click();await(await reusedDownload).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A06/retime-window-reused.csv')
  await page.getByRole('tab',{name:'目标弹道拟合',exact:true}).click();await page.getByRole('tab',{name:'模拟弹道',exact:true}).click()
  assert(await page.locator('[data-simulation-field=impactPointUnit]').inputValue()==='meter','模拟草稿被跨菜单清空')
  await page.getByRole('tab',{name:'弹道重定时',exact:true}).click()
  assert((await view.innerText()).includes(page.__a06.simulationRun.referenceId),'重定时来源被切页清空')
  page.__a06.retimeRun={status:response.status(),request,body,referenceId:selected.value};page.__a06.reuseRun={status:reuseResponse.status(),request:reuseRequest,response:reuseBody}
  page.__a06.checks.push('重定时真实2576点/201登记/原字节下载/四生成引用合并/跨一级菜单复用/双星SPACE+GROUND四载荷真实HTTP/UTC沿新CSV/草稿来源保留')
  return {retimeStatus:response.status(),pointCount:body.files[0].pointCount,reuseStatus:reuseResponse.status(),generatedCount:4,referenceId:selected.value,errors:page.__a06.errors}
}
