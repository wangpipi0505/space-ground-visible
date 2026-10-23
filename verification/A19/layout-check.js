async (page) => {
  await page.goto('http://127.0.0.1:5176/');
  await page.waitForLoadState('networkidle');
  const results = [];
  for (const width of [1440, 1000]) {
    await page.setViewportSize({ width, height: 1000 });
    for (const id of ['workbench', 'ballistic-payloads', 'ballistic-fit']) {
      await page.locator(`#tab-${id}`).click();
      await page.waitForTimeout(150);
      const measurements = await page.locator('.el-input-number:visible').evaluateAll(nodes => nodes.map(node => {
        const control = node.getBoundingClientRect(), parent = node.parentElement.getBoundingClientRect();
        return { width: control.width, parentWidth: parent.width, overflow: control.right > parent.right + 1 };
      }));
      const overflow = measurements.filter(item => item.overflow);
      if (overflow.length) throw new Error(JSON.stringify({ width, id, overflow }));
      const documentOverflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
      if (documentOverflow) throw new Error(width + '/' + id + ' 页面横向溢出');
      await page.screenshot({ path: `verification/A19/final-layout-${width}-${id}.png`, fullPage: true });
      results.push({ width, id, numericControls: measurements.length, overflow: false });
    }
  }
  return { results };
}
