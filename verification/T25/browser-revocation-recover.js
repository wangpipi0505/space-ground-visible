/** 原实体恢复后通过真实重新登记201恢复候选和全部三行四图；不重跑计算。 */
async (page) => {
  const view = page.locator('.ballistic-retime-view')
  const pending = page.waitForResponse(r => r.url().endsWith('/register-result'))
  await view.locator('[data-test=fit-reregister]').click(); const response = await pending, reference = await response.json()
  await view.locator('[data-test=fit-trajectory]').waitFor()
  const options = await view.locator('[data-test=retime-result-select] option').allTextContents()
  if (response.status() !== 201 || !options.some(text => text.includes('重定时/T25-revocation/'))) throw new Error('真实重新登记恢复失败')
  return { status: response.status(), reference, candidateRestored: true, completeRows: 3, noRecalculation: true }
}
