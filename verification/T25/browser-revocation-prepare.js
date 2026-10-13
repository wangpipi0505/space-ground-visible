/** 为辅助404真实注销/恢复建立本浏览器本次独占三行结果，后续只移动该明确服务路径。 */
async (page) => {
  await page.getByRole('tab', { name: '弹道重定时', exact: true }).click()
  const view = page.locator('.ballistic-retime-view')
  await view.locator('[data-test=retime-source-mode]').selectOption('upload')
  const upload = page.waitForResponse(r => r.url().endsWith('/api/v1/ballistic/files'))
  await view.locator('input[type=file]').setInputFiles('G:/5-YG/space-ground-visible/test-data/reference/ballistic/T25/retime-irregular-reordered-source.csv'); await upload
  await view.locator('[data-retime-field=taskName]').fill('T25-revocation')
  await view.locator('[data-retime-field=targetId]').fill('REVOCATION-TGT')
  await view.locator('[data-retime-field=launchTimeUtc]').fill('2026-07-02 23:59:59.375')
  const pending = page.waitForResponse(r => r.url().endsWith('/trajectory/retime'))
  const registerPending = page.waitForResponse(r => r.url().endsWith('/register-result'))
  await view.locator('[data-test=retime-compute]').click(); const response = await pending
  const registered = await registerPending; await view.locator('[data-test=fit-trajectory]').waitFor()
  if (response.status() !== 200 || registered.status() !== 201) throw new Error('辅助注销准备失败')
  return { request: response.request().postDataJSON(), raw: await response.text(), reference: await registered.json() }
}
