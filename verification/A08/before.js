async page => {
  await page.getByRole('tab', { name: '可视窗口计算', exact: true }).click()
  await page.waitForTimeout(400)
  const scan = () => page.locator('.ballistic-workspace .ballistic-native-form > legend:visible').evaluateAll(nodes => nodes.map(node => {
    const parent = node.parentElement, a = node.getBoundingClientRect(), b = parent.getBoundingClientRect()
    return { title: node.textContent, topInset: a.top - b.top, parentPadding: getComputedStyle(parent).paddingTop, float: getComputedStyle(node).cssFloat }
  }))
  const before = await scan()
  await page.locator('[data-option=startOffsetSec]').scrollIntoViewIfNeeded()
  await page.screenshot({path:'G:/5-YG/space-ground-visible/verification/A08/before.png'})
  await page.addStyleTag({content:'.ballistic-workspace .ballistic-native-form > legend { float: left; width: 100%; padding: 0; margin-bottom: 12px; } .ballistic-workspace .ballistic-native-form > legend + * { clear: both; }'})
  const hypothesis = await scan()
  if (before.some(row => row.topInset >= 12) && before.every(row => row.topInset >= 12)) throw Error('未复现标题贴边')
  if (hypothesis.some(row => row.topInset < 12)) throw Error('标题仍未进入卡片内边距')
  return { before, hypothesis }
}
