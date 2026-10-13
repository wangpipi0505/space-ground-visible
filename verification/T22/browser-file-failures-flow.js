/** 负例只用真实原生input Files事件与实际HTTP；主流程的两个上传已实测原生chooser。 */
async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message) }
  const view = page.locator('.ballistic-payload-view')
  const target = view.locator('[data-target-id]').first()
  const evidence = []
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const upload = async (path, name) => {
    const promise = page.waitForResponse(response => response.url().endsWith('/api/v1/ballistic/files') && response.request().method() === 'POST')
    await target.locator('input[type=file]').setInputFiles(path)
    const response = await promise
    const rawText = await response.text()
    evidence.push({ name, status: response.status(), rawText, requestContentType: response.request().headers()['content-type'] })
    return response
  }
  const invalidExtension = await upload('G:/5-YG/space-ground-visible/verification/runtime/T22/超过限额.csv', '替换为超过64MiB限额文件')
  assert(invalidExtension.status() === 413, '超过实际服务限额不是413')
  await target.getByRole('alert').waitFor()
  assert(!await target.getByRole('button', { name: 'HTTP 下载原文件' }).count(), '失败沿用旧上传引用')
  let blockedRequests = 0
  const counter = request => { if (request.url().endsWith('/api/orbit/visibility/search')) blockedRequests++ }
  page.on('request', counter)
  await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).click()
  assert((await view.innerText()).includes('请成功上传文件') && blockedRequests === 0 && !await view.locator('[data-test=window-success]').count(), '失败上传仍可计算或伪装旧成功')
  page.off('request', counter)
  await target.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T22/upload-failed-reference-cleared.png' })
  const malformed = await upload('G:/5-YG/space-ground-visible/verification/runtime/T22/损坏弹道.csv', '原CSV结构无效')
  assert(malformed.status() === 201, '损坏CSV没有进入真实解析边界')
  await target.getByRole('button', { name: 'HTTP 下载原文件' }).waitFor()
  await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).waitFor({ state: 'visible' })
  const responsePromise = page.waitForResponse(response => response.url().endsWith('/api/orbit/visibility/search') && response.request().method() === 'POST')
  await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).click()
  const response = await responsePromise
  const rawText = await response.text()
  assert(response.status() >= 400, '错误CSV竟成功计算')
  evidence.push({ name: '损坏CSV真实服务计算失败', request: response.request().postDataJSON(), status: response.status(), rawText })
  await view.locator('ul[role=alert]').waitFor()
  assert(!await view.locator('[data-test=window-success]').count(), '计算失败仍展示成功')
  await view.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T22/compute-malformed-csv-failed.png' })
  await target.getByRole('combobox', { name: '文件来源' }).selectOption('remote')
  await target.getByRole('textbox', { name: '网络地址' }).fill('C:\\fakepath\\trajectory.csv')
  await target.getByRole('button', { name: '导入网络地址' }).click()
  assert((await target.innerText()).includes('本机路径请使用上传'), '网络导入允许本机路径')
  await target.getByRole('textbox', { name: '网络地址' }).fill('http://127.0.0.1:19122/trajectory.csv')
  await target.getByRole('button', { name: '导入网络地址' }).click()
  const remotePromise = page.waitForResponse(response => response.url().endsWith('/api/orbit/visibility/search') && response.request().method() === 'POST')
  await view.getByRole('button', { name: '执行弹道窗口计算', exact: true }).click()
  const remote = await remotePromise
  const remoteRaw = await remote.text()
  assert(remote.status() === 200, `真实HTTP远程解析失败${remoteRaw}`)
  const body = JSON.parse(remoteRaw)
  assert(remote.request().postDataJSON().ballistics[0].csvPath === 'http://127.0.0.1:19122/trajectory.csv' && body.best.visibleArcCount === 8, '远程来源或弧数不符')
  evidence.push({ name: '真实HTTP远程地址导入解析', request: remote.request().postDataJSON(), status: remote.status(), rawText: remoteRaw })
  await view.locator('[data-test=window-success]').waitFor()
  await target.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T22/remote-http-source.png' })
  assert(errors.length === 0, '页面运行异常')
  return { evidence, blockedRequests, pageErrors: errors, checks: ['超限文件上传413清旧引用', '无引用提交阻止HTTP', '损坏原CSV服务失败清旧结果', '本机路径导入阻止', '真实本地HTTP fixture远程解析200八弧'] }
}
