async (page) => { page.__a01_edge = await (/** 真实CSV边界与原400/恢复；图和原点表使用命名列，服务计算与辅助失败分别记录。 */
async (page) => {
  const assert = (ok, text) => { if (!ok) throw new Error(text) }
  const root = 'G:/5-YG/space-ground-visible', runtime = `${root}/verification/runtime/A01/retime`
  const errors = [], pageErrors = []
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) }); page.on('pageerror', e => pageErrors.push(e.message))
  await page.getByRole('tab', { name: '弹道重定时', exact: true }).click()
  const view = page.locator('.ballistic-retime-view')
  await view.locator('[data-test=retime-source-mode]').selectOption('upload')
  const uploaded = page.waitForResponse(r => r.url().endsWith('/api/v1/ballistic/files'))
  await (async () => { const pending = page.waitForEvent('filechooser'); await view.locator('input[type=file]').click(); await (await pending).setFiles(`${root}/test-data/reference/ballistic/T25/retime-irregular-reordered-source.csv`) })()
  const upload = await uploaded, inputReference = await upload.json()
  assert(upload.status() === 201, '边界上传201失败'); await view.locator('[data-test=retime-source-reference]').waitFor()
  await view.locator('[data-retime-field=taskName]').fill('T25-boundary'); await view.locator('[data-retime-field=targetId]').fill('BOUNDARY-TGT')
  await view.locator('[data-retime-field=launchTimeUtc]').fill('2026-07-02 23:59:59.375')
  const pending = page.waitForResponse(r => r.url().endsWith('/trajectory/retime'))
  await view.locator('[data-test=retime-compute]').click(); const response = await pending, raw = await response.text()
  assert(response.status() === 200, `边界计算失败${raw}`)
  const trajectory = view.locator('[data-test=fit-trajectory]'); await trajectory.waitFor()
  assert((await trajectory.innerText()).includes('完整CSV 3 行') && (await trajectory.innerText()).includes('0…2.875 相对UTC s'), '非整秒横轴错误')
  assert(await trajectory.locator('[data-test=fit-original-marker]').count() === 4 && await trajectory.locator('tbody tr').count() === 1, '1/-1/0原点标记错误')
  const originalTable = await trajectory.locator('tbody tr').innerText()
  const downloadPending = page.waitForEvent('download'); await view.locator('[data-test=fit-download]').click(); await (await downloadPending).saveAs(`${runtime}/download-boundary.csv`)
  const previewTable = view.locator('[data-fit-target] details table')
  const header = await previewTable.locator('thead th').allTextContents(), cells = await previewTable.locator('tbody tr').first().locator('td').allTextContents()
  const first = Object.fromEntries(header.map((name, index) => [name, cells[index]]))
  assert(!header.includes('dt') && first.isOriginal === '1' && originalTable.includes(first.ECI_VX) && originalTable.includes(first.ECI_X), '列重排原点表未按命名列')
  assert((await previewTable.innerText()).includes('边界来源') && (await previewTable.innerText()).includes('1625.000'), '未知文本/非整秒间隔丢失')
  await trajectory.screenshot({ path: `${root}/output/playwright/A01/retime/boundary-full-trajectory.png` })
  const boundary = { name: 'boundary', status: response.status(), request: response.request().postDataJSON(), raw, originalTable, inputReference, header, pathCounts: await trajectory.locator('svg path[stroke="#126399"]').evaluateAll(paths => paths.map(p => (p.getAttribute('d').match(/[ML]/g) || []).length)), originalMarkerCount: await trajectory.locator('[data-test=fit-original-marker]').count() }
  const successConsoleErrors = errors.slice(), negatives = []
  await view.locator('[data-retime-field=launchTimeUtc]').fill('非法UTC')
  const invalidPending = page.waitForResponse(r => r.url().endsWith('/trajectory/retime')); await view.locator('[data-test=retime-compute]').click(); const invalid = await invalidPending, invalidRaw = await invalid.text()
  assert(invalid.status() === 400 && await view.locator('[data-test=retime-success]').count() === 0 && (await view.innerText()).includes(JSON.parse(invalidRaw).error), '原UTC400全文/清旧成功失败')
  negatives.push({ name: 'invalid-utc', status: invalid.status(), request: invalid.request().postDataJSON(), raw: invalidRaw })
  await view.locator('[data-retime-field=launchTimeUtc]').fill('2026-07-02 23:59:59.375')
  const unsupportedUpload = page.waitForResponse(r => r.url().endsWith('/api/v1/ballistic/files'))
  await (async () => { const pending = page.waitForEvent('filechooser'); await view.locator('input[type=file]').click(); await (await pending).setFiles(`${root}/test-data/reference/ballistic/trajectory-0.1s-standard.csv`) })()
  const unsupportedRef = await (await unsupportedUpload).json(); await view.locator('[data-test=retime-source-reference]').waitFor()
  const badHeaderPending = page.waitForResponse(r => r.url().endsWith('/trajectory/retime')); await view.locator('[data-test=retime-compute]').click(); const badHeader = await badHeaderPending, badHeaderRaw = await badHeader.text()
  assert(badHeader.status() === 400 && await view.locator('[data-test=retime-success]').count() === 0 && (await view.innerText()).includes(JSON.parse(badHeaderRaw).error), '原不支持表头400全文失败')
  negatives.push({ name: 'invalid-header', status: badHeader.status(), request: badHeader.request().postDataJSON(), raw: badHeaderRaw, inputReference: unsupportedRef })
  await view.screenshot({ path: `${root}/output/playwright/A01/retime/original-400-failure.png` })
  const recoveryUpload = page.waitForResponse(r => r.url().endsWith('/api/v1/ballistic/files'))
  await (async () => { const pending = page.waitForEvent('filechooser'); await view.locator('input[type=file]').click(); await (await pending).setFiles(`${root}/test-data/reference/ballistic/T25/retime-irregular-reordered-source.csv`) })(); await recoveryUpload
  await view.locator('[data-test=retime-source-reference]').waitFor()
  const recoveryPending = page.waitForResponse(r => r.url().endsWith('/trajectory/retime')); await view.locator('[data-test=retime-compute]').click(); const recovery = await recoveryPending
  await view.locator('[data-test=fit-trajectory]').waitFor()
  assert(recovery.status() === 200 && successConsoleErrors.length === 0 && pageErrors.length === 0, '恢复/成功流异常')
  return { timezone: await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), boundary, negatives, recoveryStatus: recovery.status(), successConsoleErrors, negativeConsoleErrors: errors.slice(successConsoleErrors.length), pageErrors }
}
)(page); return page.__a01_edge; }