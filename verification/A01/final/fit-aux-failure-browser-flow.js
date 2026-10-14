async (page) => { page.__a01_aux_failure = await (/** 管理运行CSV临时移走后的实际HTTP404；保持另一目标与已成功曲线，禁止将错误JSON保存为CSV。 */
async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message) }
  const view = page.locator('.ballistic-fit-view')
  const target = view.locator('[data-fit-target="TGT-001"]')
  const other = view.locator('[data-fit-target="TGT-002"]')
  let downloads = 0
  const observeDownload = () => { downloads++ }
  page.on('download', observeDownload)
  const previewPromise = page.waitForResponse(response => response.url().includes('/preview?page=3&limit=50'))
  await target.locator('[data-test=fit-preview-next]').click()
  const preview = await previewPromise
  assert(preview.status() === 404, '实际缺失文件预览非404')
  await target.getByRole('alert').waitFor()
  const downloadPromise = page.waitForResponse(response => response.url().includes('/download'))
  await target.locator('[data-test=fit-download]').click()
  const download = await downloadPromise
  assert(download.status() === 404, '实际缺失文件下载非404')
  await target.getByText('下载失败：', { exact: false }).waitFor()
  assert(downloads === 0, '错误JSON触发假CSV下载')
  assert(await view.locator('[data-test=fit-success]').count() === 1, '辅助失败把原计算成功误报失败')
  assert(await target.locator('[data-test=fit-trajectory]').count() === 1, '文件下载错误丢原成功曲线')
  assert(await other.getByRole('alert').count() === 0 && await other.locator('[data-test=fit-trajectory]').count() === 1, '目标错误串扰')
  await target.screenshot({ path: 'G:/5-YG/space-ground-visible/output/playwright/A01/final/fit/aux-real-404-isolated.png' })
  page.off('download', observeDownload)
  return { source: '精确本次管理CSV临时移走，真实HTTP404，未mock', preview: { url: preview.url(), status: preview.status(), rawText: await preview.text() }, download: { url: download.url(), status: download.status(), rawText: await download.text() }, browserDownloads: downloads, originalComputeStillSuccessful: true, otherTargetUnaffected: true }
}
)(page); return page.__a01_aux_failure; }