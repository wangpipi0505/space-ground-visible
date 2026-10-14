async (page) => { page.__a01_window = await (/** Chrome Asia/Shanghai 原生文件选择→双目标双星四载荷→三偏移→层级/轨迹/下载；无route/mock。 */
async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message) }
  const view = page.locator('.ballistic-payload-view')
  const consoleErrors = []
  page.on('pageerror', error => consoleErrors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
  await page.getByRole('tab', { name: '弹道载荷配置', exact: true }).click()
  await view.getByRole('button', { name: '新增弹道目标', exact: true }).click()
  const evidence = []
  const upload = async (target, path) => {
    const responsePromise = page.waitForResponse(response => response.url().endsWith('/api/v1/ballistic/files') && response.request().method() === 'POST')
    const chooserPromise = page.waitForEvent('filechooser')
    await target.locator('input[type=file]').click()
    const chooser = await chooserPromise
    await chooser.setFiles(path)
    const response = await responsePromise
    const reference = await response.json()
    assert(response.status() === 201, '真实上传未201')
    assert(reference.sizeBytes === 1507045 && reference.serverPath !== reference.originalName, '上传字节或服务引用错误')
    await target.getByText('原数据预览', { exact: false }).waitFor()
    evidence.push({ source: '原生文件选择器与实际HTTP上传', status: response.status(), reference, contentType: response.request().headers()['content-type'] })
    return reference
  }
  const targets = view.locator('[data-target-id]')
  const reference1 = await upload(targets.nth(0), 'G:/5-YG/space-ground-visible/test-data/reference/ballistic/trajectory-0.1s-standard.csv')
  const reference2 = await upload(targets.nth(1), 'G:/5-YG/space-ground-visible/test-data/reference/ballistic/trajectory-0.1s-standard.csv')
  await targets.nth(1).getByRole('textbox', { name: '基准发射 UTC（留空沿文件历元）' }).fill('2026/07/01 07:57:10.000')
  await targets.nth(0).getByRole('button', { name: '预览下一页' }).click()
  await targets.nth(0).getByText('第 2 页', { exact: false }).waitFor()
  await view.getByRole('button', { name: '新增手工卫星', exact: true }).click()
  await view.getByRole('button', { name: '新增手工卫星', exact: true }).click()
  const stars = view.locator('[data-satellite-id]')
  const fillPayload = async (payload, key, value) => { const input = payload.locator(`[data-field="${key}"] input`); await input.fill(value); await input.press('Tab') }
  for (let i = 0; i < 2; i++) {
    const star = stars.nth(i)
    assert(await star.locator('[data-orbit-field="semiMajorAxisM"]').inputValue() === '', '未授权静默填入示例根数')
    await star.getByRole('button', { name: '采用非生产原基准根数' }).click()
    await star.getByRole('button', { name: '新增载荷', exact: true }).click()
    const payloads = star.locator('[data-payload-id]')
    for (let j = 0; j < 2; j++) {
      const payload = payloads.nth(j)
      if (j === 1) {
        const selector = payload.locator('[data-field="payloadType"] .el-select')
        await selector.click()
        const listId = await selector.getByRole('combobox').getAttribute('aria-controls')
        await page.locator(`#${listId}`).getByRole('option', { name: '对地载荷', exact: true }).click()
      }
      await fillPayload(payload, 'minDistanceKm', '0')
      await fillPayload(payload, 'maxDistanceKm', '0')
      for (const key of ['azimuthMinDeg', 'azimuthMaxDeg', 'elevationMinDeg', 'elevationMaxDeg']) await fillPayload(payload, key, '')
      await fillPayload(payload, 'angleRateDegPerSec', j === 0 ? '-1' : '180')
      await fillPayload(payload, 'sunAvoidHalfAngleDeg', '0')
      await fillPayload(payload, 'startupAngleRateDegPerSec', '-1')
    }
  }
  for (const [key, value] of [['startOffsetSec', '1200'], ['stepSizeSec', '10'], ['numCalculations', '3'], ['targetSampleStepSec', '1']]) await view.locator(`[data-option="${key}"]`).fill(value)
  const responsePromise = page.waitForResponse(response => response.url().endsWith('/api/orbit/visibility/search') && response.request().method() === 'POST', { timeout: 120000 })
  await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).click()
  assert(await view.getByRole('button', { name: '新增手工卫星' }).isDisabled(), '计算时卫星编辑未冻结')
  const response = await responsePromise
  const rawText = await response.text()
  assert(response.status() === 200, `计算HTTP失败 ${rawText}`)
  const body = JSON.parse(rawText)
  const request = response.request().postDataJSON()
  assert(request.ballistics.length === 2 && request.satellites.length === 2 && request.satellites.every(star => star.payloads.length === 2), '实际DTO不是双目标双星四载荷')
  assert(request.ballistics[0].csvPath === reference1.serverPath && request.ballistics[1].csvPath === reference2.serverPath, '实际DTO未使用HTTP引用')
  assert(request.ballistics[1].baseLaunchUTC === '2026/07/01 07:57:10.000' && request.satellites.every(star => star.epochUtc === '2026/07/01 10:00:00.000'), 'UTC漂移')
  assert(body.best.offsetSecond === 1220 && body.best.visibleArcCount === 8, '原基准best/8弧不符')
  assert(body.allResults.length === 3, '偏移未三次')
  await view.locator('[data-test=window-success]').waitFor()
  const results = view.locator('.ballistic-window-results')
  const offsetEvidence = []
  for (let i = 0; i < 3; i++) {
    await results.locator('[data-test=offset-select]').selectOption(String(i))
    assert(await results.locator('.ballistic-result-satellite').count() === body.allResults[i].visibleSatellites.length, '星层级不符')
    assert(await results.locator('[data-test=show-trajectory]').count() === body.allResults[i].visibleArcCount, '弧段层级不符')
    for (const summary of await results.locator('.ballistic-result-target summary').all()) await summary.click()
    const buttons = results.locator('[data-test=show-trajectory]')
    for (let j = 0; j < await buttons.count(); j++) {
      await buttons.nth(j).click()
      assert(await results.locator('[data-test=trajectory-view] svg').count() === 2, '真实轨迹未绘图')
    }
    offsetEvidence.push({ offset: body.allResults[i].offsetSecond, arcs: await buttons.count(), satellites: await results.locator('.ballistic-result-satellite').count(), payloads: await results.locator('.ballistic-result-payload').count() })
    await results.screenshot({ path: `G:/5-YG/space-ground-visible/output/playwright/A01/window/results-offset-${body.allResults[i].offsetSecond}.png` })
  }
  await results.locator('.ballistic-trajectory').screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/A01/window/trajectory-real-points.png' })
  const originalDownloadPromise = page.waitForEvent('download')
  await targets.nth(1).getByRole('button', { name: 'HTTP 下载原文件' }).click()
  const originalDownload = await originalDownloadPromise
  await originalDownload.saveAs('G:/5-YG/space-ground-visible/verification/runtime/A01/window/download-original.csv')
  assert(originalDownload.suggestedFilename() === 'trajectory-0.1s-standard.csv', '中文原下载名错误')
  const jsonDownloadPromise = page.waitForEvent('download')
  await view.getByRole('button', { name: '下载原始响应 JSON' }).click()
  const jsonDownload = await jsonDownloadPromise
  await jsonDownload.saveAs('G:/5-YG/space-ground-visible/verification/runtime/A01/window/download-response.json')
  assert(!await jsonDownload.failure() && !await originalDownload.failure(), '浏览器下载失败')
  assert(consoleErrors.length === 0, `控制台错误${consoleErrors.join(';')}`)
  return { browser: 'Chrome', timezone: await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), evidence, request, status: response.status(), rawText, offsetEvidence, originalDownloadName: originalDownload.suggestedFilename(), jsonDownloadName: jsonDownload.suggestedFilename(), consoleErrors }
}
)(page); return page.__a01_window; }