/** 原CSV临时移走后实际辅助404；原计算成功/错误保持，App候选已撤销。 */
async (page) => {
  const view = page.locator('.ballistic-retime-view')
  const missing = page.waitForResponse(r => r.url().endsWith('/download'))
  await view.locator('[data-test=fit-download]').click(); const response = await missing, raw = await response.text()
  if (response.status() !== 404) throw new Error(`预期真实404：${raw}`)
  await view.getByRole('alert').filter({ hasText: '下载失败' }).waitFor()
  if (await view.locator('[data-test=retime-success]').count() !== 1) throw new Error('辅助404误清计算成功')
  await view.locator('[data-test=retime-source-mode]').selectOption('generated')
  const options = await view.locator('[data-test=retime-result-select] option').allTextContents()
  if (options.some(text => text.includes('重定时/T25-revocation/'))) throw new Error('已注销引用仍在App候选')
  return { status: response.status(), raw, originalCalculationStillVisible: true, revokedCandidateRemoved: true, options }
}
