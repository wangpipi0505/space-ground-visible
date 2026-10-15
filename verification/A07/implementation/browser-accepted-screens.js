async page => {
  const assert=(ok,message)=>{if(!ok)throw Error(message)}
  const view=page.locator('.ballistic-payload-view')
  await view.locator('[data-test=window-source]').selectOption('file')
  const preview=page.waitForResponse(r=>r.url().includes('/preview?'))
  await view.locator('[data-test=upload-default-window]').click();await preview
  await view.getByLabel('目标名称',{exact:true}).fill('目标1')
  const targetDetails=view.getByText('目标时间与来源详情',{exact:true})
  await targetDetails.click();await view.getByLabel('目标 ID',{exact:true}).fill('TGT-1');await targetDetails.click()
  await view.locator('[data-option=numCalculations]').fill('361')
  const pending=page.waitForResponse(r=>r.url().endsWith('/api/orbit/visibility/search'),{timeout:240000})
  await view.locator('[data-test=compute-window]').click()
  const response=await pending,result=await response.json()
  assert(result.best.visibleArcCount===2 && result.best.visiblePointCount===246,'最终示例不是两弧246点')
  const sizes=[]
  async function capture(key,selector,button){
    for(const [width,height] of [[1440,1000],[1920,1080],[390,844]]){
      await page.setViewportSize({width,height});await page.evaluate(()=>window.scrollTo(0,0))
      await page.locator(`${selector} .ballistic-input-scroll`).evaluate(el=>{el.scrollTop=0})
      await page.locator(`${selector} .ballistic-table-scroll`).evaluateAll(items=>items.forEach(el=>{el.scrollLeft=0}))
      await page.waitForTimeout(400)
      const metric=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth}))
      assert(metric.scrollWidth<=width,`${key}横向溢出`)
      assert(await page.locator(`${selector} [data-test=${button}]`).evaluate(el=>{const b=el.getBoundingClientRect();return b.top>=0&&b.bottom<=innerHeight}),`${key}主动作不可见`)
      await page.screenshot({path:`G:/5-YG/space-ground-visible/verification/A07/implementation/accepted-${key}-${width}.png`})
      sizes.push({page:key,width,height,scrollWidth:metric.scrollWidth,primaryVisible:true})
    }
  }
  await capture('window','.ballistic-payload-view','compute-window')
  await page.setViewportSize({width:1440,height:1000})
  await page.getByRole('tab',{name:'目标弹道拟合',exact:true}).click()
  for(const [name,key,selector] of [['102包轨迹拟合','fit','.ballistic-fit-view'],['模拟弹道','simulation','.ballistic-simulation-view'],['弹道重定时','retime','.ballistic-retime-view']]){
    await page.getByRole('tab',{name,exact:true}).click();await capture(key,selector,`${key}-compute`)
  }
  await page.setViewportSize({width:1440,height:1000});await page.getByRole('tab',{name:'可视窗口计算',exact:true}).click();await page.waitForTimeout(400)
  page.__a07.acceptedSizes=sizes
  const download=page.waitForEvent('download')
  await page.evaluate(text=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([text]));a.download='browser-state.json';a.click()},JSON.stringify(page.__a07))
  await(await download).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A07/implementation/browser-state.json')
  return {sizes,finalStatus:response.status(),finalBest:{offset:result.best.offsetSecond,arcs:result.best.visibleArcCount,points:result.best.visiblePointCount,duration:result.best.visibleDurationSec},jsErrors:page.__a07.window.errors}
}
