async (page) => {
  /** 31偏移单目标单星单SPACE实际大结果：完整响应/轨迹原样渲染和下载；计时不含原生chooser等待。 */
  const assert=(ok,text)=>{if(!ok)throw new Error(text)}
  await page.goto('http://127.0.0.1:15140');await page.getByRole('tab',{name:'弹道载荷配置',exact:true}).click();const view=page.locator('.ballistic-payload-view')
  const upload=page.waitForResponse(r=>r.url().endsWith('/api/v1/ballistic/files')&&r.request().method()==='POST'),choice=page.waitForEvent('filechooser')
  await view.locator('input[type=file]').click();await(await choice).setFiles('G:/5-YG/space-ground-visible/test-data/reference/ballistic/trajectory-0.1s-standard.csv');const ref=await(await upload).json()
  await view.locator('[data-test=add-manual-satellite]').click();await view.locator('[data-test=use-reference-orbit]').click()
  for(const [key,value]of Object.entries({minDistanceKm:'0',maxDistanceKm:'0',azimuthMinDeg:'',azimuthMaxDeg:'',elevationMinDeg:'',elevationMaxDeg:'',angleRateDegPerSec:'-1',sunAvoidHalfAngleDeg:'0',startupAngleRateDegPerSec:'-1'})){const input=view.locator(`[data-field=${key}] input`);await input.fill(value);await input.press('Tab')}
  for(const [key,value]of Object.entries({startOffsetSec:'1200',stepSizeSec:'10',numCalculations:'31',targetSampleStepSec:'1'}))await view.locator(`[data-option=${key}]`).fill(value)
  const pending=page.waitForResponse(r=>r.url().endsWith('/orbit/visibility/search'),{timeout:120000}),start=Date.now();await view.locator('[data-test=compute-window]').click();const response=await pending;await response.finished();const transferFinished=Date.now(),raw=await response.text(),value=JSON.parse(raw)
  assert(response.status()===200&&value.allResults.length===31,'31偏移未完整返回');await view.locator('[data-test=window-success]').waitFor();const ready=Date.now(),results=view.locator('.ballistic-window-results'),offsets=[]
  for(const index of [0,15,30]){const t=Date.now();await results.locator('[data-test=offset-select]').selectOption(String(index));await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));offsets.push({index,offset:value.allResults[index].offsetSecond,ms:Date.now()-t})}
  const target=results.locator('.ballistic-result-target summary');await target.first().click();const drawStart=Date.now();await results.locator('[data-test=show-trajectory]').first().click();await results.locator('[data-test=trajectory-view] svg').first().waitFor();await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));const drawMs=Date.now()-drawStart
  await results.screenshot({path:'G:/5-YG/space-ground-visible/output/playwright/A01/final/window/large-31-offsets.png'})
  const downloadStart=Date.now(),downloadPending=page.waitForEvent('download');await view.getByRole('button',{name:'下载原始响应 JSON',exact:true}).click();const download=await downloadPending;await download.saveAs('G:/5-YG/space-ground-visible/verification/runtime/A01/final-candidate/window/download-large-response.json');assert(!await download.failure(),'大响应真实下载失败')
  const points=value.allResults.reduce((total,o)=>total+o.visiblePointCount,0);assert(points>10000,'重结果没有足够实际点')
  page.__a01_large={request:response.request().postDataJSON(),responseStatus:response.status(),responseBytes:new TextEncoder().encode(raw).length,raw,offsets:31,pointCount:points,clickToTransferCompleteMs:transferFinished-start,transferCompleteToResultDomMs:ready-transferFinished,clickToResultDomMs:ready-start,fullTrajectoryDrawMs:drawMs,offsetTimings:offsets,downloadInteractionMs:Date.now()-downloadStart,httpTiming:response.request().timing(),heap:await page.evaluate(()=>performance.memory?{used:performance.memory.usedJSHeapSize,total:performance.memory.totalJSHeapSize}:null)}
  return page.__a01_large
}
