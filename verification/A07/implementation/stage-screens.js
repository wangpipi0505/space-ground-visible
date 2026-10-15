async page => {
  await page.getByRole('tab', { name: '可视窗口计算', exact: true }).click()
  await page.screenshot({ path: 'G:/5-YG/space-ground-visible/verification/A07/implementation/stage2-window-1440.png' })
  const window = await page.locator('.ballistic-payload-view').evaluate(el => ({ width: el.clientWidth, scrollWidth: el.scrollWidth }))
  await page.getByRole('tab', { name: '目标弹道拟合', exact: true }).click()
  await page.screenshot({ path: 'G:/5-YG/space-ground-visible/verification/A07/implementation/stage2-fit-1440.png' })
  return { window, bodyWidth: await page.evaluate(() => document.body.scrollWidth) }
}
