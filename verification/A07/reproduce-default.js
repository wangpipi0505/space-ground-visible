async page => {
  const state = { startedUtc: new Date().toISOString(), network: [], errors: [] }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.getByRole('tab', {name:'可视窗口计算',exact:true}).click()
  const view=page.locator('.ballistic-payload-view')
  await page.screenshot({path:'G:/5-YG/space-ground-visible/verification/A07/before-window.png',fullPage:true})
  const upload=page.waitForResponse(r=>r.url().endsWith('/api/v1/ballistic/files')&&r.request().method()==='POST')
  await view.locator('[data-test=upload-default-window]').click()
  state.file=await(await upload).json()
  const pending=page.waitForResponse(r=>r.url().endsWith('/api/orbit/visibility/search')&&r.request().method()==='POST',{timeout:240000})
  await view.locator('[data-test=compute-window]').click()
  const response=await pending
  state.request=response.request().postDataJSON()
  state.status=response.status()
  state.response=await response.json()
  await view.locator('[data-test=window-success]').waitFor()
  await page.screenshot({path:'G:/5-YG/space-ground-visible/verification/runtime/A07/before-default-result.png',fullPage:true})
  const download=page.waitForEvent('download')
  await page.evaluate(text=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([text],{type:'application/json'}));a.download='A07-default.json';a.click()},JSON.stringify(state,null,2))
  await(await download).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A07/default-reproduction.json')
  return {status:state.status,candidates:state.response.allResults.length,best:state.response.best?{offset:state.response.best.offsetSecond,arcs:state.response.best.visibleArcCount,points:state.response.best.visiblePointCount}:null,payloads:state.request.satellites.map(s=>s.payloads),errors:state.errors}
}
