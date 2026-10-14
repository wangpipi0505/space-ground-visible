async (page) => { page.__a01_retime = await (/** 真Chrome原生CSV上传/原重定时/201/完整四图/原下载→模拟引用→自身结果再输入→窗口，无mock。 */
async (page) => {
  const assert = (ok, text) => { if (!ok) throw new Error(text) }
  const root = 'G:/5-YG/space-ground-visible', runtime = `${root}/verification/runtime/A01/retime`
  const errors = [], pageErrors = [], auxiliary = [], computations = []
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) }); page.on('pageerror', e => pageErrors.push(e.message))
  const record = async response => {
    if (response.url().includes('/api/v1/ballistic/files')) auxiliary.push({ url: response.url(), status: response.status(), request: response.request().postData(), raw: response.url().includes('/download') ? undefined : await response.text() })
  }
  page.on('response', record)
  const view = page.locator('.ballistic-retime-view')
  await page.getByRole('tab', { name: '弹道重定时', exact: true }).click()
  const uploaded = page.waitForResponse(r => r.url().endsWith('/api/v1/ballistic/files'))
  const choice = page.waitForEvent('filechooser'); await view.locator('input[type=file]').click(); await (await choice).setFiles(`${root}/test-data/baseline/ballistic/simulate/trajectory-0.csv`)
  const uploadResponse = await uploaded, inputReference = await uploadResponse.json()
  assert(uploadResponse.status() === 201 && inputReference.sizeBytes === 561736, '真实上传201失败')
  await view.locator('[data-test=retime-source-reference]').waitFor()
  await view.locator('[data-retime-field=taskName]').fill('T04-retime')
  await view.locator('[data-retime-field=targetId]').fill('RETIMED-TGT')
  await view.locator('[data-retime-field=launchTimeUtc]').fill('2026-07-02 07:57:00.000')
  async function compute(name) {
    const pending = page.waitForResponse(r => r.url().endsWith('/trajectory/retime'))
    await view.locator('[data-test=retime-compute]').click(); const response = await pending, raw = await response.text(), result = JSON.parse(raw), request = response.request().postDataJSON()
    assert(response.status() === 200 && !('outputDir' in request), `${name}原响应/省略目录失败${raw}`)
    const trajectory = view.locator('[data-test=fit-trajectory]'); await trajectory.waitFor()
    const pathCounts = await trajectory.locator('svg path[stroke="#126399"]').evaluateAll(paths => paths.map(path => (path.getAttribute('d').match(/[ML]/g) || []).length))
    assert(pathCounts.length === 4 && pathCounts.every(count => count === result.files[0].pointCount), '未显示全行四图')
    const downloadPending = page.waitForEvent('download'); await view.locator('[data-test=fit-download]').click(); await (await downloadPending).saveAs(`${runtime}/download-${name}.csv`)
    computations.push({ name, status: response.status(), request, raw, pathCounts, originalMarkers: await trajectory.locator('[data-test=fit-original-marker]').count() })
    return result
  }
  const result = await compute('default')
  assert(result.targetCount === 1 && result.files[0].pointCount === 2576 && result.files[0].model === 'RETIMED_TRAJECTORY', '原默认合同改变')
  const next = page.waitForResponse(r => r.url().includes('/preview?page=2'))
  await view.locator('[data-test=fit-preview-next]').click(); const preview = await (await next).json()
  assert(preview.page === 2 && preview.rows.length === 50 && preview.totalRows === 2576 && preview.header.length === 22, '22列第二页错误')
  await view.locator('[data-test=fit-trajectory]').screenshot({ path: `${root}/output/playwright/A01/retime/default-full-trajectory.png` })
  for (const [button, name] of [['下载实际提交 JSON', 'download-input.json'], ['下载原重定时响应 JSON', 'download-response.json']]) {
    const pending = page.waitForEvent('download'); await view.getByRole('button', { name: button, exact: true }).click(); await (await pending).saveAs(`${runtime}/${name}`)
  }
  await view.locator('[data-retime-field=launchTimeUtc]').fill('2026-07-02 07:57:01.000')
  assert((await view.innerText()).includes('输入已修改'), '输入过期提示缺失')
  await view.locator('[data-retime-field=launchTimeUtc]').fill('2026-07-02 07:57:00.000')
  await page.getByRole('tab', { name: '模拟弹道', exact: true }).click()
  const simulation = page.locator('.ballistic-simulation-view')
  const simChoice = page.waitForEvent('filechooser'); await simulation.locator('input[type=file]').click(); await (await simChoice).setFiles(`${root}/test-data/reference/ballistic/target_trajectory_simulate_default.json`)
  const simulationPending = page.waitForResponse(r => r.url().endsWith('/trajectory/simulate'))
  await simulation.locator('[data-test=simulation-compute]').click(); const simResponse = await simulationPending
  assert(simResponse.status() === 200, '已有模拟准备失败'); await simulation.locator('[data-test=fit-trajectory]').waitFor()
  await page.getByRole('tab', { name: '弹道重定时', exact: true }).click()
  assert(await view.locator('[data-test=fit-trajectory]').count() === 1 && await view.locator('[data-retime-field=taskName]').inputValue() === 'T04-retime', 'tab丢重定时草稿/结果')
  await view.locator('[data-test=retime-source-mode]').selectOption('generated')
  const simulatedOption = view.locator('[data-test=retime-result-select] option').filter({ hasText: /^模拟\// })
  const simulatedId = await simulatedOption.getAttribute('value')
  await view.locator('[data-test=retime-result-select]').selectOption(simulatedId)
  await view.locator('[data-retime-field=taskName]').fill('T25-existing-simulation')
  await compute('existing-simulation')
  const ownOption = view.locator('[data-test=retime-result-select] option').filter({ hasText: '重定时/T25-existing-simulation/' })
  const ownId = await ownOption.getAttribute('value')
  await view.locator('[data-test=retime-result-select]').selectOption(ownId)
  const ownInput = await view.locator('[data-test=retime-source-reference]').innerText()
  await view.locator('[data-retime-field=taskName]').fill('T25-retime-again')
  await compute('retime-again')
  assert(ownInput.includes(computations[2].request.trajectoryCsvPath), '再次重定时没有截取自身有效输入路径')
  await page.getByRole('tab', { name: '弹道载荷配置', exact: true }).click()
  const window = page.locator('.ballistic-payload-view')
  await window.locator('[data-test=add-manual-satellite]').click(); await window.locator('[data-test=use-reference-orbit]').click()
  await window.locator('[data-orbit-field=epochUtc]').fill('2026/07/02 07:57:00.000')
  for (const [key, value] of Object.entries({ minDistanceKm: '0', maxDistanceKm: '0', azimuthMinDeg: '', azimuthMaxDeg: '', elevationMinDeg: '', elevationMaxDeg: '', angleRateDegPerSec: '0', sunAvoidHalfAngleDeg: '0' })) {
    const input = window.locator(`[data-field=${key}] input`); await input.fill(value); await input.press('Tab')
  }
  await window.locator('[data-test=window-source]').selectOption('generated')
  const options = await window.locator('[data-test=generated-result-select] option').allTextContents()
  assert(options.some(text => text.includes('模拟/')) && options.some(text => text.includes('重定时/T25-retime-again')), '引用来源没有隔离保留')
  const windowId = await window.locator('[data-test=generated-result-select] option').filter({ hasText: '重定时/T25-retime-again' }).getAttribute('value')
  await window.locator('[data-test=generated-result-select]').selectOption(windowId)
  await window.locator('[data-option=startOffsetSec]').fill('0'); await window.locator('[data-option=numCalculations]').fill('1')
  const winPending = page.waitForResponse(r => r.url().endsWith('/orbit/visibility/search'), { timeout: 60000 })
  await window.locator('[data-test=compute-window]').click(); const winResponse = await winPending, winRaw = await winResponse.text(), winRequest = winResponse.request().postDataJSON()
  assert(winResponse.status() === 200, `原窗口失败${winRaw}`); await window.locator('[data-test=window-success]').waitFor()
  const windowReference = auxiliary.filter(item => item.url.endsWith('/register-result') && item.status === 201).map(item => JSON.parse(item.raw)).find(item => item.fileId === windowId)
  assert(windowReference && winRequest.ballistics[0].csvPath === windowReference.serverPath && !('baseLaunchUTC' in winRequest.ballistics[0]), '窗口未精确引用新CSV')
  assert(winRequest.satellites[0].epochUtc === '2026/07/02 07:57:00.000', '卫星历元未同日')
  await window.locator('[data-test=window-success]').screenshot({ path: `${root}/output/playwright/A01/retime/generated-window-result.png` })
  page.off('response', record)
  assert(!errors.length && !pageErrors.length, `成功流浏览器错误${errors}/${pageErrors}`)
  return { timezone: await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), inputReference, computations, preview, simulationStatus: simResponse.status(), windowId, windowReference, winStatus: winResponse.status(), winRequest, winRaw, auxiliary, errors, pageErrors }
}
)(page); return page.__a01_retime; }