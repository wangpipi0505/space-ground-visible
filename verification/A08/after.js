async page => {
  await page.reload()
  const results = []
  for (const [width, height, zoom] of [[1440,1000,1],[1920,1080,1],[390,844,1],[1440,1000,1.5]]) {
    await page.setViewportSize({width,height})
    await page.evaluate(value => { document.documentElement.style.zoom = String(value) }, zoom)
    await page.getByRole('tab',{name:'可视窗口计算',exact:true}).click()
    const pages = [['window',null],['fit','102包轨迹拟合'],['simulation','模拟弹道'],['retime','弹道重定时']]
    for (const [id,tab] of pages) {
      if (tab) {
        await page.getByRole('tab',{name:'目标弹道拟合',exact:true}).click()
        await page.getByRole('tab',{name:tab,exact:true}).click()
      }
      await page.waitForTimeout(400)
      const state = await page.locator('.ballistic-workspace:visible').evaluate(element => ({
        documentWidth: document.documentElement.scrollWidth,
        titles: [...element.querySelectorAll('.ballistic-native-form > legend')].map(node => {
          const card=node.parentElement.closest('.ballistic-panel'), a=node.getBoundingClientRect(), b=card.getBoundingClientRect(), next=node.nextElementSibling.getBoundingClientRect()
          return {title:node.textContent, inset:a.top-b.top, leftInset:a.left-b.left, rightInset:b.right-a.right, contentGap:next.top-a.bottom}
        })
      }))
      if (state.documentWidth > width || state.titles.some(row => row.inset < 12*zoom || row.leftInset < 0 || row.rightInset < -1 || row.contentGap < -1)) throw Error(JSON.stringify({id,width,zoom,...state}))
      results.push({id,width,height,zoom,...state})
      if (id==='window') {
        await page.locator('[data-option=startOffsetSec]').scrollIntoViewIfNeeded()
        await page.screenshot({path:`G:/5-YG/space-ground-visible/verification/A08/window-${width}-${zoom}.png`})
      } else if (width===1440 && zoom===1) await page.screenshot({path:`G:/5-YG/space-ground-visible/verification/A08/${id}-1440.png`})
    }
  }
  return results
}
