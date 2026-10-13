/** 真实核心数值错误保留唯一草稿并0HTTP，修正JSON后恢复实际计算。 */
async (page) => {
  const assert = (ok, text) => { if (!ok) throw new Error(text) }
  await page.getByRole('tab', { name: '模拟弹道', exact: true }).click()
  const view = page.locator('.ballistic-simulation-view')
  // 最终源码HMR会重建script setup草稿；明确重新读取冻结完整输入，不能依赖开发热更新前状态。
  await view.locator('input[type=file]').setInputFiles('G:/5-YG/space-ground-visible/test-data/reference/ballistic/target_trajectory_simulate_default.json')
  await view.getByText('已读取 target_trajectory_simulate_default.json', { exact: false }).waitFor()
  const original = await view.locator('textarea').inputValue()
  const calls = [], errors = [], pageErrors = []
  const recorder = r => { if (r.url().endsWith('/trajectory/simulate')) calls.push(r.status()) }
  page.on('response', recorder); page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) }); page.on('pageerror', e => pageErrors.push(e.message))
  await view.locator('[data-simulation-field=launchPointX]').fill('9007199254740993')
  await view.locator('[data-simulation-field=launchPointX]').press('Tab')
  assert(JSON.parse(await view.locator('textarea').inputValue()).launchPointX === '9007199254740993', '核心非法原文没有保留')
  await view.locator('[data-test=simulation-compute]').click()
  assert(calls.length === 0 && await view.locator('[data-test=simulation-success]').count() === 0, '非法核心数值偷偷提交旧成功')
  assert((await view.innerText()).includes('launchPointX须为有限数或null'), '字段错误没有明确显示')
  await view.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/T24/core-invalid-no-http.png' })
  await view.locator('textarea').fill(original)
  const pending = page.waitForResponse(r => r.url().endsWith('/trajectory/simulate')); await view.locator('[data-test=simulation-compute]').click(); const response = await pending
  await view.locator('[data-test=fit-trajectory]').waitFor()
  assert(response.status() === 200 && calls.length === 1 && !errors.length && !pageErrors.length, '修正恢复失败')
  page.off('response', recorder)
  return { coreInvalidHttpCalls: 0, recoveryStatus: response.status(), request: response.request().postDataJSON(), errors, pageErrors }
}
