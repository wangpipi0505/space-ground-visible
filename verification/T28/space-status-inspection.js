/** 实际生产任务记录状态的文本、尺寸和颜色证据，排除截图瞬时状态猜测。 */
async (page) => {
  await page.getByRole('tab', { name: '任务结果记录', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: page.__rootT28Space.confirmedTaskId })
  await row.waitFor()
  const tags = await page.locator('.el-tag').evaluateAll(elements => elements.map(element => {
    const style = getComputedStyle(element), bounds = element.getBoundingClientRect()
    return { text: element.textContent, html: element.outerHTML, width: bounds.width, height: bounds.height, color: style.color, background: style.backgroundColor, opacity: style.opacity, display: style.display, visibility: style.visibility, fontSize: style.fontSize }
  }))
  await page.screenshot({ path: 'G:/5-YG/space-ground-visible/verification/T28/space-status-inspection.png' })
  return { row: await row.textContent(), tags, space: page.__rootT28Space }
}
