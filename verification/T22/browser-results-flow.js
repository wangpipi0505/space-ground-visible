/** 原生上传已由main-flow和CLI upload完成；独立捕获真实计算HTTP原文与全部偏移/层级/轨迹下载。 */
async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message) }
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  const view = page.locator('.ballistic-payload-view')
  const targets = view.locator('[data-target-id]')
  const files = []
  for (const target of await targets.all()) files.push(await target.innerText())
  const responsePromise = page.waitForResponse(response => response.url().endsWith('/api/orbit/visibility/search') && response.request().method() === 'POST')
  await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).click()
  assert(await view.getByRole('button', { name: '新增手工卫星' }).isDisabled(), '计算未冻结卫星编辑')
  const response = await responsePromise
  const rawText = await response.text()
  const request = response.request().postDataJSON()
  assert(response.status() === 200, `计算失败 ${rawText}`)
  const body = JSON.parse(rawText)
  assert(request.ballistics.length === 2 && request.satellites.length === 2 && request.satellites.every(star => star.payloads.length === 2), '实际DTO层级错误')
  assert(request.ballistics[1].baseLaunchUTC === '2026/07/01 07:57:10.000' && request.satellites.every(star => star.epochUtc === '2026/07/01 10:00:00.000'), 'UTC漂移')
  assert(body.best.offsetSecond === 1220 && body.best.visibleArcCount === 8, '原基准best/弧数错误')
  const results = view.locator('.ballistic-window-results')
  await results.locator('[data-test=offset-select]').waitFor()
  const checks = []
  for (let i = 0; i < 3; i++) {
    await results.locator('[data-test=offset-select]').selectOption(String(i))
    const buttons = results.locator('[data-test=show-trajectory]')
    assert(await buttons.count() === body.allResults[i].visibleArcCount, '真实弧段数显示错误')
    assert(await results.locator('.ballistic-result-satellite').count() === 2 && await results.locator('.ballistic-result-payload').count() === 4, '双星四载荷呈现错误')
    for (let j = 0; j < await buttons.count(); j++) {
      await buttons.nth(j).click()
      assert(await results.locator('[data-test=trajectory-view] svg').count() === 2, '真实点图不完整')
    }
    checks.push({ offset: body.allResults[i].offsetSecond, arcs: await buttons.count(), points: body.allResults[i].visiblePointCount, durationSec: body.allResults[i].visibleDurationSec, satellites: 2, payloads: 4 })
    await results.screenshot({ path: `G:/5-YG/space-ground-visible/output/playwright/T22/results-offset-${body.allResults[i].offsetSecond}.png` })
  }
  await results.locator('.ballistic-trajectory').screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T22/trajectory-real-points.png' })
  await results.getByRole('button', { name: '轨迹下一页' }).click()
  assert((await results.locator('.ballistic-trajectory').innerText()).includes('第 2 /'), '原点表未分页')
  const originalPromise = page.waitForEvent('download')
  await targets.nth(1).getByRole('button', { name: 'HTTP 下载原文件' }).click()
  const original = await originalPromise
  await original.saveAs('G:/5-YG/space-ground-visible/verification/runtime/T22/download-original.csv')
  assert(original.suggestedFilename() === '弹道目标乙.csv', '中文原文件名错误')
  const jsonPromise = page.waitForEvent('download')
  await view.getByRole('button', { name: '下载原始响应 JSON' }).click()
  const json = await jsonPromise
  await json.saveAs('G:/5-YG/space-ground-visible/verification/runtime/T22/download-response.json')
  assert(errors.length === 0, 'Chrome控制台错误')
  return { browser: 'Chrome headless', timezone: await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), files, request, status: response.status(), rawText, checks, originalDownloadName: original.suggestedFilename(), jsonDownloadName: json.suggestedFilename(), errors }
}
