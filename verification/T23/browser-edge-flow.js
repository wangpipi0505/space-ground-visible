/** 本地非法JSON/危险编码无HTTP；真实两点成功/null任务名保留、单点/非法点原400与失败清成功。 */
async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message) }
  const view = page.locator('.ballistic-fit-view')
  const source = JSON.parse(await view.locator('textarea').inputValue())
  const requests = []
  const consoleErrors = []
  const pageErrors = []
  const observe = request => { if (request.url().endsWith('/package102')) requests.push(request.postDataJSON()) }
  page.on('request', observe)
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
  page.on('pageerror', failure => pageErrors.push(failure.message))
  const evidence = []
  for (const [name, text] of [['非法JSON', '{'], ['17位数字时间码未加引号', '{"package102":[{"tack3d_time_code":20260701075700001}]}']]) {
    const count = requests.length
    await view.locator('textarea').fill(text)
    await view.locator('[data-test=fit-compute]').click()
    await view.getByRole('alert').waitFor()
    assert(requests.length === count && await view.locator('[data-test=fit-success]').count() === 0, '本地非法输入仍HTTP或旧成功残留')
    evidence.push({ name, actualComputeRequests: 0, message: await view.getByRole('alert').innerText() })
  }
  await view.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T23/local-invalid-precision-blocked.png' })
  const calculate = async (value, expectedStatus, name) => {
    await view.locator('textarea').fill(JSON.stringify(value, null, 2))
    const responsePromise = page.waitForResponse(response => response.url().endsWith('/package102') && response.request().method() === 'POST')
    await view.locator('[data-test=fit-compute]').click()
    const response = await responsePromise
    const rawText = await response.text()
    assert(response.status() === expectedStatus, `${name}状态${response.status()}：${rawText}`)
    evidence.push({ name, request: response.request().postDataJSON(), status: response.status(), rawText })
    if (expectedStatus !== 200) {
      await view.getByRole('alert').waitFor()
      assert(await view.locator('[data-test=fit-success]').count() === 0, '原400后成功结果未清')
    }
    return JSON.parse(rawText)
  }
  const two = { ...source, taskName: null, package102: source.package102.slice(4, 6) }
  delete two.outputDir
  const twoResult = await calculate(two, 200, '两条有效观测与taskName:null原服务成功')
  assert(requests.at(-1).taskName === null && twoResult.taskName === 'task' && twoResult.targetCount === 1, '任务名null被浏览器改写或原后端默认不符')
  const trajectory = view.locator('[data-test=fit-trajectory]')
  await trajectory.waitFor()
  assert(await trajectory.locator('tbody tr').count() === 2, '两点原约束数不符')
  const pathCounts = await trajectory.locator('svg path[stroke="#126399"]').evaluateAll(paths => paths.map(path => (path.getAttribute('d').match(/[ML]/g) || []).length))
  assert(pathCounts.every(count => count === twoResult.files[0].pointCount), '少量点未展示全CSV')
  await trajectory.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T23/two-original-points-real.png' })
  await calculate({ ...two, package102: two.package102.slice(0, 1) }, 400, '单条观测由原服务拒绝：至少两个')
  const invalid = { ...two, package102: two.package102.map(record => ({ ...record, tack3d_tgt_pos_x: '非法坐标' })) }
  await calculate(invalid, 400, '真正非法102观测由原服务400')
  await view.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T23/invalid-observation-original-400.png' })
  assert(pageErrors.length === 0, `页面运行异常：${pageErrors}`)
  page.off('request', observe)
  return { browser: 'Chrome', timezone: await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone), evidence, twoPointGraphPathCounts: pathCounts, pageErrors, expectedNetworkConsoleErrors: consoleErrors, note: '404/400网络日志为主动真实负例；成功主流另已验证0console/pageerror，不把负例错误隐藏为成功。' }
}
