async page => {
  /** 原默认八行→新增→删除后新增；仅补足合法观测后真实提交，原默认字段以字符串验证。 */
  const assert = (ok, text) => { if (!ok) throw new Error(text) }
  await page.getByRole('tab', { name: '目标弹道拟合', exact: true }).click()
  const view = page.locator('.ballistic-fit-view')
  await view.getByText('高级设置：完整JSON导入与兼容字段', { exact: true }).click()
  const text = view.locator('[data-test=fit-input]'), draft = () => text.inputValue().then(JSON.parse)
  const before = await draft()
  before.package102[0].future = { enabled: true, code: '90071992547409931' }
  await text.fill(JSON.stringify(before, null, 2))
  await view.getByRole('button', { name: '新增102记录', exact: true }).click()
  const ninth = (await draft()).package102[8]
  const expected = ['TGT-NEW','2026-07-01 07:57:08.000','','','','','','','0','0','0','0x01','0x02','0x01','0xFF','','','J2000','','','','0.1m','','J2000','','','','0.1m']
  assert(JSON.stringify(Object.values(ninth)) === JSON.stringify(expected), '第九行默认内容不是原Swing')
  assert(JSON.stringify((await draft()).package102.slice(0,8)) === JSON.stringify(before.package102), '新增覆写既有八行或未知JSON')
  await view.locator('[data-fit-row="8"]').getByRole('button').click()
  await view.locator('[data-fit-row="7"]').getByRole('button').click()
  await view.getByRole('button', { name: '新增102记录', exact: true }).click()
  const afterDelete = (await draft()).package102[7]
  assert(afterDelete.tack3d_time_code === '2026-07-01 07:57:07.000' && afterDelete.track_tgt_id === 'TGT-NEW', '删除后新增未按当前行数')
  // 加入已有目标的合法新观测；保留本轮新增的属性/加速度/坐标系/单位默认，避免运行不完整行。
  for (const [field, value] of Object.entries({ track_tgt_id: 'TGT-001', tack3d_tgt_pos_x: '70070000', tack3d_tgt_pos_y: '7000', tack3d_tgt_pos_z: '0', tack3d_tgt_vel_x: '10000000', tack3d_tgt_vel_y: '1000000', tack3d_tgt_vel_z: '0' })) await view.locator(`[data-fit-cell="7/${field}"]`).fill(value)
  await view.locator('[data-fit-cell="7/impact_pos_unit"]').fill('0.1m')
  assert(await view.locator('[data-fit-cell="7/impact_pos_unit"]').evaluate(element => element === document.activeElement), '102末格未在焦点')
  const pending = page.waitForResponse(response => response.url().endsWith('/api/trajectory/fit/package102') && response.request().method() === 'POST')
  await view.locator('[data-test=fit-compute]').evaluate(element => element.click())
  const response = await pending, request = response.request().postDataJSON(), body = await response.json()
  assert(response.status() === 200 && body.targetCount === 2, `真实拟合失败${JSON.stringify(body)}`)
  const sent = request.package102[7]
  assert(sent.tack3d_time_code === afterDelete.tack3d_time_code && sent.tack3d_tgt_acc_x === '0' && sent.tack3d_tgt_acc_y === '0' && sent.tack3d_tgt_acc_z === '0' && sent.prop_tgt_type_8 === '0x01' && sent.prop_tgt_shape_8 === '0x02' && sent.prop_tgt_flight_phase_8 === '0x01' && sent.prob_tgt_critical_incident === '0xFF' && sent.launch_coord_frame === 'J2000' && sent.impact_coord_frame === 'J2000' && sent.launch_pos_unit === '0.1m' && sent.impact_pos_unit === '0.1m', 'HTTP新增字符串默认丢失')
  assert(JSON.stringify(request.package102[0].future) === JSON.stringify(before.package102[0].future), '真实请求未知JSON丢失')
  await view.locator('[data-test=fit-success]').waitFor()
  const downloadPending = page.waitForEvent('download')
  await view.getByRole('button', { name: '下载原拟合响应 JSON', exact: true }).click()
  await (await downloadPending).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A06/review-fix/new102-response.json')
  page.__a06fix.fit = { ninth, afterDelete, status: response.status(), request, targetCount: body.targetCount }
  page.__a06fix.checks.push('默认八行新增第九行28列原字符串；删除后新增按当前行数；唯一JSON/未知字段保持；补足观测后新行真实HTTP字符串与原响应下载')
  await view.locator('[data-test=fit-success]').screenshot({ path: 'G:/5-YG/space-ground-visible/verification/A06/review-fix/new102-success.png' })
  return { ninth, afterDelete, status: response.status(), targetCount: body.targetCount, submittedNewRow: sent, errors: page.__a06fix.errors }
}
