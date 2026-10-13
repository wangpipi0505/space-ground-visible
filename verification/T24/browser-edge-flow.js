/** 原Java已支持的km/WGS84/时间回退/预留事件，均经真实Vue提交与浏览器下载；负例单独保留console。 */
async (page) => {
  const assert = (ok, text) => { if (!ok) throw new Error(text) }
  await page.getByRole('tab', { name: '模拟弹道', exact: true }).click()
  const view = page.locator('.ballistic-simulation-view'), original = await view.locator('textarea').inputValue(), source = JSON.parse(original)
  const consoleErrors = [], pageErrors = [], calls = []
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) }); page.on('pageerror', e => pageErrors.push(e.message))
  const recorder = r => { if (r.url().endsWith('/trajectory/simulate')) calls.push(r.status()) }
  page.on('response', recorder)
  const km = { ...source, taskName: '浏览器km原别名', launchPointCoordFrame: 'ECI_J2000', impactPointCoordFrame: 'eci-j2000', phaseEvents: null }
  for (const prefix of ['launchPoint', 'impactPoint']) { km[`${prefix}Unit`] = 'kilometers'; for (const axis of ['X', 'Y', 'Z']) km[prefix + axis] = source[prefix + axis] / 1000 }
  const estimated = { ...source, taskName: null, impactTimeUtc: null, impactTime: '', launchPointCoordFrame: 'WGS84', impactPointCoordFrame: 'WGS84_LLA', launchPointX: 120, launchPointY: 30, launchPointZ: 0, impactPointX: 121, impactPointY: 31, impactPointZ: 0, launchPointUnit: 'km', impactPointUnit: 'km', outputPointDurationSec: 0, outputStepSec: 2.5, integrationStepSec: 9, initialSpeedMs: 1e20, boostAccelerationMs2: 100, phaseEvents: [{ phaseType: 'BOOST', eventType: null, startTime: '原预留开始', endTime: null, eventTime: 'ignored', dynamics: '旧动力学', directionMode: 'CUSTOM', accelerationMs2: 30, deltaVms: 20, liftDragRatio: 2, dragCdAreaOverMass: .1 }] }
  const outcomes = []
  for (const [name, draft] of [['km', km], ['estimated-events', estimated]]) {
    await view.locator('textarea').fill(JSON.stringify(draft, null, 2))
    const pending = page.waitForResponse(r => r.url().endsWith('/trajectory/simulate'))
    await view.locator('[data-test=simulation-compute]').click(); const response = await pending, raw = await response.text(), result = JSON.parse(raw)
    assert(response.status() === 200, `${name}失败${raw}`)
    const expected = { ...draft }; delete expected.outputDir
    assert(JSON.stringify(response.request().postDataJSON()) === JSON.stringify(expected), `${name}原请求值改变`)
    const target = view.locator('[data-fit-target]'), graph = target.locator('[data-test=fit-trajectory]'); await graph.waitFor()
    assert((await graph.innerText()).includes(`完整CSV ${result.files[0].pointCount} 行`), '变体曲线不完整')
    assert(JSON.stringify(await target.locator('ol li').allTextContents()) === JSON.stringify(result.files[0].warnings), '变体原warning未展示')
    if (name === 'estimated-events') assert(result.files[0].warnings.some(w => w.includes('落点时间由系统模拟模型计算得到')) && result.files[0].warnings.some(w => w.includes('phaseEvents') && w.includes('数量=1')), '真实估计/预留事件warning缺失')
    const downloadPending = page.waitForEvent('download'); await target.locator('[data-test=fit-download]').click(); const download = await downloadPending
    await download.saveAs(`G:/5-YG/space-ground-visible/verification/runtime/T24/download-${name}.csv`)
    await graph.screenshot({ path: `G:/5-YG/space-ground-visible/output/playwright/T24/${name}-full-trajectory.png` })
    outcomes.push({ name, status: response.status(), request: response.request().postDataJSON(), raw, pointCount: result.files[0].pointCount, warnings: result.files[0].warnings })
  }
  const successConsoleErrors = consoleErrors.slice()
  const countBeforeLocal = calls.length
  await view.locator('input[type=file]').setInputFiles('G:/5-YG/space-ground-visible/verification/runtime/T24/invalid-local.json')
  await view.getByRole('alert').filter({ hasText: 'JSON格式错误' }).waitFor()
  await view.locator('[data-test=simulation-compute]').click()
  assert(calls.length === countBeforeLocal && await view.locator('[data-test=simulation-success]').count() === 0, '非法本地JSON发送HTTP/保留旧成功')
  const negatives = []
  for (const [name, draft] of [['invalid-utc', { ...source, launchTimeUtc: '非法UTC' }], ['invalid-frame', { ...source, launchPointCoordFrame: '原入口不支持的坐标' }]]) {
    await view.locator('textarea').fill(JSON.stringify(draft))
    const pending = page.waitForResponse(r => r.url().endsWith('/trajectory/simulate'))
    await view.locator('[data-test=simulation-compute]').click(); const response = await pending, raw = await response.text()
    assert(response.status() === 400 && await view.locator('[data-test=simulation-success]').count() === 0, '原400失败未清旧成功')
    assert((await view.innerText()).includes(JSON.parse(raw).error), '原错误中文未完整回显')
    negatives.push({ name, status: response.status(), request: response.request().postDataJSON(), raw })
  }
  await view.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T24/original-400-failure.png' })
  await view.locator('textarea').fill(original)
  const recoveryPending = page.waitForResponse(r => r.url().endsWith('/trajectory/simulate')); await view.locator('[data-test=simulation-compute]').click(); const recovered = await recoveryPending
  await view.locator('[data-test=fit-trajectory]').waitFor()
  assert(recovered.status() === 200 && successConsoleErrors.length === 0 && pageErrors.length === 0, '恢复或成功流错误')
  page.off('response', recorder)
  return { timezone: await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), outcomes, negatives, invalidLocalHttpCalls: calls.length - countBeforeLocal - 3, recoveryStatus: recovered.status(), successConsoleErrors, negativeConsoleErrors: consoleErrors.slice(successConsoleErrors.length), pageErrors }
}
