async page => {
  const assert=(ok,message)=>{if(!ok)throw Error(message)}
  await page.getByRole('tab',{name:'可视窗口计算',exact:true}).click()
  const view=page.locator('.ballistic-payload-view')
  await view.locator('[data-test=window-source]').selectOption('file')
  const failed=page.waitForResponse(r=>r.url().endsWith('/api/v1/ballistic/files')&&r.request().method()==='POST')
  await view.locator('input[type=file]').setInputFiles('G:/5-YG/space-ground-visible/verification/runtime/A07/implementation/invalid.txt')
  const failure=await failed
  assert(failure.status()===201,'文件登记未完成')
  const rejected=page.waitForResponse(r=>r.url().endsWith('/api/orbit/visibility/search'),{timeout:120000})
  await view.locator('[data-test=compute-window]').click()
  const invalid=await rejected
  assert(!invalid.ok(),'非弹道文本计算应失败')
  await view.locator('.ballistic-results-column [role=alert]').waitFor()
  const uploaded=page.waitForResponse(r=>r.url().endsWith('/api/v1/ballistic/files')&&r.request().method()==='POST')
  const preview=page.waitForResponse(r=>r.url().includes('/preview?'))
  await view.locator('input[type=file]').setInputFiles('G:/5-YG/space-ground-visible/verification/runtime/A07/implementation/default-original.csv')
  const reference=await (await uploaded).json(); await preview
  await view.locator('[data-test=open-payload-settings]').click()
  assert(await view.locator('[data-payload-satellite-id=MANUAL-2] [data-field=maxDistanceKm] input').inputValue()==='2','上传静默改变自定义载荷')
  const dataDetails=view.locator('.ballistic-target > details').first()
  await dataDetails.locator('summary').first().click()
  const next=page.waitForResponse(r=>r.url().includes('/preview?page=2'))
  await view.getByRole('button',{name:'预览下一页',exact:true}).click(); await next
  assert((await dataDetails.locator('summary').first().innerText()).includes('第 2 页'),'数据分页未更新')
  await dataDetails.locator('summary').first().click()
  // 显式恢复示例后再次计算；上传动作本身并未调整用户载荷。
  await view.locator('[data-test=load-constellation]').click()
  await view.locator('[data-option=numCalculations]').fill('361')
  const computed=page.waitForResponse(r=>r.url().endsWith('/api/orbit/visibility/search'),{timeout:240000})
  await view.locator('[data-test=compute-window]').click()
  const response=await computed, result=await response.json()
  assert(response.status()===200 && result.best.visibleArcCount===2,'上传恢复后的示例计算失败')
  page.__a07.recovery={invalidComputeStatus:invalid.status(),recoveredUpload:true,previewPage:2,customPayloadUnchanged:true,finalStatus:response.status(),finalArcs:result.best.visibleArcCount}
  for(const [width,height] of [[1440,1000],[1920,1080],[390,844]]){
    await page.setViewportSize({width,height});await page.evaluate(()=>window.scrollTo(0,0));await view.locator('.ballistic-input-scroll').evaluate(el=>{el.scrollTop=0})
    const metric=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth}))
    assert(metric.scrollWidth<=width,'最终窗口横向溢出')
    assert(await view.locator('[data-test=compute-window]').evaluate(el=>{const r=el.getBoundingClientRect();return r.bottom<=innerHeight&&r.top>=0}),'主动作不在可视范围')
    await page.screenshot({path:`G:/5-YG/space-ground-visible/verification/A07/implementation/final-window-${width}.png`})
  }
  await page.setViewportSize({width:1440,height:1000})
  await page.getByRole('tab',{name:'目标弹道拟合',exact:true}).click()
  for(const [name,selector,key] of [['102包轨迹拟合','.ballistic-fit-view','fit'],['模拟弹道','.ballistic-simulation-view','simulation'],['弹道重定时','.ballistic-retime-view','retime']]){
    await page.getByRole('tab',{name,exact:true}).click()
    for(const [width,height] of [[1440,1000],[390,844]]){
      await page.setViewportSize({width,height});await page.evaluate(()=>window.scrollTo(0,0));await page.locator(`${selector} .ballistic-input-scroll`).evaluate(el=>{el.scrollTop=0})
      assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${key}横向溢出`)
      assert(await page.locator(`${selector} [data-test=${key==='fit'?'fit':key}-compute]`).evaluate(el=>{const r=el.getBoundingClientRect();return r.bottom<=innerHeight&&r.top>=0}),`${key}主动作不可见`)
      await page.screenshot({path:`G:/5-YG/space-ground-visible/verification/A07/implementation/stable-${key}-${width}.png`})
    }
  }
  await page.setViewportSize({width:1440,height:1000})
  const download=page.waitForEvent('download')
  await page.evaluate(text=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([text]));a.download='browser-state.json';a.click()},JSON.stringify(page.__a07))
  await(await download).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A07/implementation/browser-state.json')
  return {recovery:page.__a07.recovery,jsErrors:page.__a07.window.errors,networkCount:page.__a07.network.length,allPrimaryButtonsVisible:true}
}
