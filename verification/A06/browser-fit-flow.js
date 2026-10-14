async page => {
  const assert=(ok,text)=>{if(!ok)throw new Error(text)}
  await page.getByRole('tab',{name:'目标弹道拟合',exact:true}).click()
  await page.getByRole('tab',{name:'102包轨迹拟合',exact:true}).click()
  const view=page.locator('.ballistic-fit-view')
  if (!(await view.locator('[data-test=fit-input]').isVisible())) await view.getByText('高级设置：完整JSON导入与兼容字段',{exact:true}).click()
  const draft=JSON.parse(await view.locator('[data-test=fit-input]').inputValue()), row=draft.package102[0]
  row.coordinateInfo=Object.fromEntries(Object.entries(row).filter(([key])=>key.startsWith('tack3d_')))
  row.coordinateInfo.tack3d_time_code='2026-07-01 07:57:00.000'
  row.coords={tack3d_time_code:'备用不改'};row.targetInfo={track_tgt_id:row.track_tgt_id,prop_tgt_type_8:row.prop_tgt_type_8,prop_tgt_shape_8:row.prop_tgt_shape_8,prop_tgt_flight_phase_8:row.prop_tgt_flight_phase_8,prob_tgt_critical_incident:row.prob_tgt_critical_incident}
  row.compatibilityNote='90071992547409931';row.future={enabled:true,originalCode:'20260701075700000'};draft.compatibilityNote='顶层保留'
  await view.locator('[data-test=fit-input]').fill(JSON.stringify(draft,null,2))
  await view.locator('[data-fit-cell="7/impact_pos_unit"]').fill('dm')
  const pending=page.waitForResponse(r=>r.url().endsWith('/api/trajectory/fit/package102')&&r.request().method()==='POST',{timeout:120000})
  await view.locator('[data-test=fit-compute]').click()
  const response=await pending,body=await response.json(),request=response.request().postDataJSON()
  assert(response.status()===200,`拟合失败${JSON.stringify(body)}`)
  assert(request.package102[0].coordinateInfo.tack3d_time_code==='2026-07-01 07:57:00.000'&&request.package102[0].coords.tack3d_time_code==='备用不改'&&request.package102[0].compatibilityNote==='90071992547409931'&&request.package102[0].future.originalCode==='20260701075700000','高级别名丢失/编码舍入')
  assert(request.package102[7].impact_pos_unit==='dm','末格未失焦未提交')
  await view.locator('[data-test=fit-download]').last().waitFor()
  await view.locator('[data-test=fit-trajectory]').last().waitFor()
  const files=[]
  for(const [index,button] of (await view.locator('[data-test=fit-download]').all()).entries()){
    const pendingDownload=page.waitForEvent('download');await button.click();const download=await pendingDownload
    const path=`G:/5-YG/space-ground-visible/verification/runtime/A06/fit-${index+1}.csv`;await download.saveAs(path);files.push(path)
  }
  const resultPromise=page.waitForEvent('download');await view.getByRole('button',{name:'下载原拟合响应 JSON',exact:true}).click();await(await resultPromise).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A06/fit-response.json')
  await view.locator('[data-test=fit-success]').screenshot({path:'G:/5-YG/space-ground-visible/verification/A06/fit-success.png'})
  page.__a06.fitRun={status:response.status(),request,body,downloads:files};page.__a06.checks.push('102真实双目标拟合/嵌套别名未知字段与大整数原文/末格dm/自动201登记/原CSV与响应下载；17位生效时间码原服务拒绝，另存真实400')
  return {status:response.status(),targetCount:body.targetCount,files:body.files,downloads:files,errors:page.__a06.errors}
}
