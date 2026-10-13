async (page) => { page.__t27_window_edge = await (/** 点开关/全空/非法参数/不同轨道/快照与导航，直接走已完成的真实上传配置。 */
async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message) }
  const view = page.locator('.ballistic-payload-view')
  const evidence = []
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const calculate = async name => {
    const responsePromise = page.waitForResponse(response => response.url().endsWith('/api/orbit/visibility/search') && response.request().method() === 'POST')
    await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).click()
    const response = await responsePromise
    const rawText = await response.text()
    assert(response.status() === 200, `${name}计算失败${rawText}`)
    await view.locator('[data-test=window-success]').waitFor()
    const body = JSON.parse(rawText)
    evidence.push({ name, request: response.request().postDataJSON(), status: response.status(), rawText })
    return body
  }
  await view.locator('[data-option=trajectoryPointOutput]').selectOption('0')
  const point0 = await calculate('点输出0三偏移')
  assert(point0.allResults.every(offset => offset.visibleSatellites.every(star => star.payloadResults.every(payload => payload.targets.every(target => target.visibleArcs.every(arc => arc.trajectoryPoints.length === 0))))), '点0返回人工点')
  await view.locator('[data-test=show-trajectory]').first().click()
  assert((await view.locator('.ballistic-trajectory').innerText()).includes('本次轨迹点输出为0') && await view.locator('.ballistic-trajectory svg').count() === 0, '点0仍伪造轨迹图')
  await view.locator('.ballistic-window-results').screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T27/window/point-output-zero.png' })
  await view.locator('[data-option=trajectoryPointOutput]').selectOption('1')
  await view.locator('[data-option=numCalculations]').fill('1')
  assert((await view.locator('[data-test=window-success]').innerText()).includes('输入已修改'), '编辑后结果未说明属于旧快照')
  const secondStar = view.locator('[data-satellite-id]').nth(1)
  await secondStar.locator('[data-orbit-field=meanAnomalyDeg]').fill('31')
  const distinct = await calculate('双星不同根数单偏移')
  assert(evidence.at(-1).request.satellites[0].meanAnomalyDeg === 30 && evidence.at(-1).request.satellites[1].meanAnomalyDeg === 31, '双星轨道串扰')
  assert(distinct.allResults.length === 1, '单偏移未一项')
  await view.locator('[data-option=stepSizeSec]').fill('0')
  let invalidRequests = 0
  const counter = request => { if (request.url().endsWith('/api/orbit/visibility/search')) invalidRequests++ }
  page.on('request', counter)
  await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).click()
  assert((await view.innerText()).includes('偏移步长必须大于0秒') && !await view.locator('[data-test=window-success]').count(), '非法参数仍显示旧成功')
  assert(invalidRequests === 0, '非法参数发送HTTP')
  await view.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T27/window/invalid-parameter.png' })
  page.off('request', counter)
  await view.locator('[data-option=stepSizeSec]').fill('10')
  for (const payload of await view.locator('[data-payload-id]').all()) {
    const input = payload.locator('[data-field=sunAvoidHalfAngleDeg] input')
    await input.fill('180'); await input.press('Tab')
  }
  const empty = await calculate('真实太阳约束全空')
  assert(empty.allResults.every(offset => offset.visibleArcCount === 0 && offset.visibleSatellites.length === 0), '全空约束未空')
  assert((await view.locator('.ballistic-window-results').innerText()).includes('该偏移没有可见卫星或弧段') && await view.locator('.ballistic-window-results svg').count() === 0, '全空显示错误')
  await view.locator('.ballistic-window-results').screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T27/window/empty-real-constraint.png' })
  const identities = await view.locator('[data-payload-id]').evaluateAll(items => items.map(item => item.dataset.payloadId))
  await page.getByRole('tab', { name: '规划工作台', exact: true }).click()
  await page.getByRole('tab', { name: '弹道载荷配置', exact: true }).click()
  assert(JSON.stringify(await view.locator('[data-payload-id]').evaluateAll(items => items.map(item => item.dataset.payloadId))) === JSON.stringify(identities), '切页丢失弹道草稿')
  for (const payload of await view.locator('[data-payload-id]').all()) { const input = payload.locator('[data-field=sunAvoidHalfAngleDeg] input'); await input.fill('0'); await input.press('Tab') }
  await secondStar.locator('[data-orbit-field=meanAnomalyDeg]').fill('30')
  assert(errors.length === 0, '页面运行错误')
  return { evidence, invalidRequests, identities, pageErrors: errors, checks: ['point0保持服务累计摘要', '单偏移不同双星轨道', '非法0步长无HTTP且清旧结果', '真实180太阳约束全空', '草稿跨tab保持', '修改后明确原提交快照'] }
}
)(page); return page.__t27_window_edge; }