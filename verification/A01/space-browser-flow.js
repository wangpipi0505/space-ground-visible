async (page) => {
  /** A01空间真实DOM流程：双星隔离fixture；用同日UTC范围、无限制标准profile，保留真实计划/响应和维护记录。 */
  const assert=(value,text)=>{if(!value)throw new Error(text)}
  const evidence=[],errors=[],pageErrors=[]
  page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});page.on('pageerror',e=>pageErrors.push(e.message))
  const card=name=>page.locator('.planning-card').filter({has:page.getByRole('heading',{name,exact:true})})
  const select=async(locator,name)=>{const item=locator.locator('xpath=ancestor::*[contains(@class,"payload-config-item")][1]');if(await item.locator('.el-collapse-item__header').getAttribute('aria-expanded')!=='true')await item.locator('.el-collapse-item__header').click();const id=await locator.locator('[role=combobox]').getAttribute('aria-controls');await locator.click();await page.locator('#'+id).getByRole('option',{name,exact:true}).click()}
  await page.goto('http://127.0.0.1:15140');await card('任务卫星').locator('tbody tr').first().waitFor()
  const rows=card('任务卫星').locator('tbody tr');assert(await rows.count()===2,'fixture不是双星')
  for(const row of await rows.all())await row.locator('.el-checkbox').click()
  const payloads=card('卫星载荷').locator('.payload-config-item')
  for(const item of await payloads.all()){
    await item.locator('.el-collapse-item__header').click()
    for(const [key,value] of Object.entries({minDistanceKm:'0',maxDistanceKm:'0',azimuthMinDeg:'',azimuthMaxDeg:'',elevationMinDeg:'',elevationMaxDeg:'',angleRateDegPerSec:'-1',sunAvoidHalfAngleDeg:'0',startupAngleRateDegPerSec:'-1'})){
      const input=item.locator(`[data-field=${key}] input`);await input.fill(value);await input.press('Tab')
    }
    const selector=item.locator('[data-field=payloadType] .el-select');await select(selector,'对空载荷')
    let pending=page.waitForResponse(r=>r.url().includes('/payload-profiles')&&r.request().method()==='PUT')
    await item.getByRole('button',{name:'保存并启用'}).click();let response=await pending;assert(response.status()===200,'SPACE保存失败');evidence.push({name:'save-space',request:response.request().postDataJSON(),raw:await response.text()})
    await select(selector,'对地载荷');const angle=item.locator('[data-field=angleRateDegPerSec] input');await angle.fill('180');await angle.press('Tab')
    pending=page.waitForResponse(r=>r.url().includes('/payload-profiles')&&r.request().method()==='PUT');await item.getByRole('button',{name:'保存并启用'}).click();response=await pending;assert(response.status()===200,'GROUND保存失败');evidence.push({name:'save-ground',request:response.request().postDataJSON(),raw:await response.text()})
    await select(selector,'对空载荷');pending=page.waitForResponse(r=>r.url().includes('/payload-profiles')&&r.request().method()==='PUT');await item.getByRole('button',{name:'保存并启用'}).click();assert((await pending).status()===200,'恢复SPACE失败')
  }
  await card('地面站').locator('.el-checkbox-button').filter({hasText:'基准地面站（T04-STATION）'}).click()
  for(const checkbox of await card('站星互选').locator('.el-checkbox').all())await checkbox.click()
  const downlink=card('下传弧段规划'),times=downlink.locator('.el-range-input')
  await times.nth(0).fill('2026-07-01 18:00:00');await times.nth(1).fill('2026-07-01 18:10:00');await times.nth(1).press('Tab');await page.keyboard.press('Escape')
  const guard=downlink.getByRole('spinbutton',{name:'前保护（秒）'});await guard.fill('0');await guard.press('Tab')
  let pending=page.waitForResponse(r=>r.url().endsWith('/downlink/plan'));await downlink.getByRole('button',{name:'计算下传弧段'}).click();let response=await pending;assert(response.status()===200,'下传失败');const link=await response.json();evidence.push({name:'downlink',request:response.request().postDataJSON(),response:link})
  const windows=card('下传窗口');await windows.getByRole('button',{name:'选择',exact:true}).first().waitFor();await windows.getByRole('button',{name:'选择',exact:true}).first().click()
  assert(await page.locator('[data-test=selected-observation-window]').isVisible(),'真实下传范围未选择')
  const custom=windows.locator('.el-range-input');await custom.nth(0).fill('2026-07-01 18:00:00');await custom.nth(1).fill('2026-07-01 18:10:00');await custom.nth(1).press('Tab');await page.keyboard.press('Escape')
  pending=page.waitForResponse(r=>r.url().endsWith('/downlink/validate-window'));await windows.getByRole('button',{name:'校验自选'}).click();response=await pending;evidence.push({name:'validate-window',status:response.status(),response:await response.json()});assert(response.status()===200,'校验接口失败')
  const targets=card('目标筛选');await targets.getByRole('textbox',{name:'历元约束（YYDDD）'}).fill('')
  pending=page.waitForResponse(r=>r.url().endsWith('/targets/query'));await targets.locator('[data-test=query-targets]').click();response=await pending;assert(response.status()===200,'目录筛选失败');evidence.push({name:'target-query',request:response.request().postDataJSON(),response:await response.json()})
  const plan=card('目标观测规划')
  const fine=plan.getByRole('spinbutton',{name:'精选步长（秒）'});await fine.fill('1');await fine.press('Tab')
  async function compute(name){
    const submitted=page.waitForResponse(r=>r.url().endsWith('/visibility/tasks')&&r.request().method()==='POST')
    const result=page.waitForResponse(r=>r.url().includes('/visibility/tasks/')&&r.url().endsWith('/result'),{timeout:120000})
    await plan.getByRole('button',{name:'开始规划'}).click();const submit=await submitted;assert(submit.status()===200,'提交失败');const status=await submit.json(),response=await result
    assert(response.status()===200,'后台结果失败');await plan.locator('[data-test=target-result-group]').first().waitFor();const value=await response.json();evidence.push({name,taskId:status.taskId,request:submit.request().postDataJSON(),response:value});return {status,value}
  }
  const single=await compute('single-satellite-results');assert(single.value.results.length>0,'单星无真实结果')
  const combo=card('目标观测组合');await combo.getByRole('spinbutton').first().fill('0');await combo.getByRole('spinbutton').first().press('Tab');for(const checkbox of await combo.locator('.el-checkbox-button').all())await checkbox.click()
  await combo.getByRole('button',{name:'添加组合约束'}).click();const common=await compute('two-satellite-common')
  assert(common.value.results.some(r=>r.observerSatelliteIds.length===2&&r.arcs.length>0),'实际未产生双星共同观测弧段')
  assert((await plan.innerText()).includes('两星共同观测'),'DOM未显示共同观测')
  await plan.locator('[data-test=target-select-checkbox]').first().click()
  pending=page.waitForResponse(r=>r.url().endsWith('/selection')&&r.request().method()==='PUT');await plan.locator('[data-test=confirm-selection]').click();response=await pending;assert(response.status()===200,'结果选择保存失败');evidence.push({name:'selection',request:response.request().postDataJSON(),response:await response.json()})
  await page.screenshot({path:'G:/5-YG/space-ground-visible/output/playwright/A01/space/common-workbench.png',fullPage:true})
  await page.getByRole('tab',{name:'任务结果记录',exact:true}).click();const history=page.locator('[data-test=task-result-records]')
  await history.locator('[data-test=task-result-keyword] input').fill(common.status.taskId);pending=page.waitForResponse(r=>r.url().includes('/task-results?'));await history.locator('[data-test=query-task-results]').click();await pending;await history.locator('[data-test=open-task-result-detail]').first().waitFor()
  pending=page.waitForResponse(r=>r.url().includes('/task-results/')&&r.request().method()==='GET');await history.locator('[data-test=open-task-result-detail]').first().click();response=await pending;const detail=await response.json();assert(detail.selectionJson,'选择明细未持久化');await page.locator('[data-test=task-result-detail-drawer]').screenshot({path:'G:/5-YG/space-ground-visible/output/playwright/A01/space/result-detail.png'})
  await page.locator('[data-test=task-result-detail-drawer] .el-drawer__close-btn').click()
  await history.locator('[data-test=edit-task-result-remark]').first().click();await page.getByPlaceholder('备注内容').fill('A01真实Chrome备注')
  pending=page.waitForResponse(r=>r.url().includes('/task-results/')&&r.request().method()==='PUT');await page.getByRole('button',{name:'保存',exact:true}).click();response=await pending;assert(response.status()===200,'备注保存失败')
  await history.locator('[data-test=delete-task-result]').first().click();pending=page.waitForResponse(r=>r.url().includes('/task-results/')&&r.request().method()==='DELETE');await page.locator('.el-message-box').getByRole('button',{name:'删除',exact:true}).click();assert((await pending).status()===200,'删除失败')
  await history.screenshot({path:'G:/5-YG/space-ground-visible/output/playwright/A01/space/history-deleted.png'})
  assert(!pageErrors.length,'页面JS异常');assert(!errors.length,'成功流console错误')
  page.__a01_space={evidence,detail,errors,pageErrors,timezone:await page.evaluate(()=>Intl.DateTimeFormat().resolvedOptions().timeZone)};return page.__a01_space
}
