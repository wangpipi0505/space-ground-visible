async page => {
  const assert = (value, message) => { if (!value) throw Error(message) }
  const runtime = 'G:/5-YG/space-ground-visible/verification/runtime/A07/review-fix'
  const evidence = 'G:/5-YG/space-ground-visible/verification/A07/review-fix'
  const state = page.__a07fix || { errors: [], screens: [], downloads: [] }
  state.screens = []
  state.downloads = []
  page.__a07fix = state
  page.on('pageerror', error => state.errors.push(error.message))
  const view = page.locator('.ballistic-fit-view')
  if (!state.result) {
    const pending = page.waitForResponse(response => response.url().endsWith('/api/trajectory/fit/package102'), { timeout: 120000 })
    await view.locator('[data-test=fit-compute]').click()
    const response = await pending
    state.status = response.status()
    state.request = response.request().postDataJSON()
    state.raw = await response.text()
    state.result = JSON.parse(state.raw)
  }
  assert(state.status === 200 && state.result.targetCount === 2, '真实默认102未成功')
  await view.locator('[data-test=fit-trajectory]').last().waitFor()
  const targets = view.locator('[data-fit-target]')
  for (let index = 0; index < state.result.files.length; index++) {
    const target = targets.nth(index), file = state.result.files[index]
    const list = target.locator('ol[aria-label="计算告警"]')
    assert(file.warnings.length === (index === 0 ? 8 : 6), '真实默认102告警数与两目标实际结果不一致')
    assert(JSON.stringify(await list.locator('li').allTextContents()) === JSON.stringify(file.warnings), '告警原文或顺序不一致')
    assert(await list.evaluate(el => el.closest('details').open === false), '告警默认未收起')
    assert(!await list.isVisible(), '默认仍显示完整告警')
    assert(await target.getByText(`计算告警（${file.warnings.length}条）`, { exact: true }).isVisible(), '告警数量不可发现')
    const downloading = page.waitForEvent('download')
    await target.locator('[data-test=fit-download]').click()
    const download = await downloading
    const localName = `fit-${index + 1}.csv`
    await download.saveAs(`${runtime}/${localName}`)
    state.downloads.push({ localName, suggestedName: download.suggestedFilename(), csvPath: file.csvPath })
  }
  async function saveText(text, filename) {
    const downloading = page.waitForEvent('download')
    await page.evaluate(({ text, filename }) => {
      const link = document.createElement('a')
      link.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }))
      link.download = filename
      link.click()
    }, { text, filename })
    await (await downloading).saveAs(`${runtime}/${filename}`)
  }
  const jsonDownloading = page.waitForEvent('download')
  await view.getByRole('button', { name: '下载结果 JSON', exact: true }).click()
  await (await jsonDownloading).saveAs(`${runtime}/fit-response.json`)
  for (const [width, height] of [[1440, 1000], [1920, 1080], [390, 844]]) {
    await page.setViewportSize({ width, height })
    await page.getByRole('tab', { name: '模拟弹道', exact: true }).click()
    await page.getByRole('tab', { name: '102包轨迹拟合', exact: true }).click()
    await page.waitForTimeout(700)
    const panel = view.locator('.ballistic-results-column')
    await panel.evaluate(el => { el.scrollTop = 0 })
    if (width < 1000) {
      await panel.evaluate(el => window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - 8))
    } else await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(400)
    const metric = await targets.first().evaluate(el => {
      const rect = selector => { const box = el.querySelector(selector).getBoundingClientRect(); return { top: box.top, bottom: box.bottom, left: box.left, right: box.right } }
      return { width: innerWidth, height: innerHeight, documentWidth: document.documentElement.scrollWidth, download: rect('[data-test=fit-download]'), firstCurve: rect('[data-test=fit-trajectory] svg'), warningsClosed: !el.querySelector('ol[aria-label="计算告警"]').closest('details').open }
    })
    assert(metric.documentWidth <= width, '页面横向溢出')
    assert(metric.warningsClosed, '切页后告警默认状态丢失')
    assert(metric.download.top >= 0 && metric.download.bottom <= height, '结果首屏下载不可见')
    assert(metric.firstCurve.top >= 0 && metric.firstCurve.bottom <= height, '结果首屏首条曲线不可见')
    await page.screenshot({ path: `${evidence}/fit-result-${width}.png` })
    state.screens.push(metric)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.waitForTimeout(700)
  const first = targets.first(), summary = first.getByText('计算告警（8条）', { exact: true })
  await summary.focus()
  await summary.press('Enter')
  const warnings = first.locator('ol[aria-label="计算告警"]')
  assert(await warnings.isVisible(), '键盘不能展开告警')
  assert(JSON.stringify(await warnings.locator('li').allTextContents()) === JSON.stringify(state.result.files[0].warnings), '展开后告警原文丢失')
  await page.waitForTimeout(400)
  await page.screenshot({ path: `${evidence}/fit-warnings-expanded-1440.png` })
  state.expandedWarningCount = await warnings.locator('li').count()
  await summary.press('Enter')
  assert(!await warnings.isVisible(), '键盘不能收起告警')
  assert(state.errors.length === 0, '浏览器发生异常')
  await saveText(JSON.stringify(state), 'browser-state.json')
  return { status: state.status, targets: state.result.targetCount, pointCounts: state.result.files.map(file => file.pointCount), warningCounts: state.result.files.map(file => file.warnings.length), screens: state.screens, expandedWarningCount: state.expandedWarningCount, keyboardToggle: true, errors: state.errors }
}
