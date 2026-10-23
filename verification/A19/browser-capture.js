async (page) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const states = [];
  for (const [id, key] of [['workbench', 'space'], ['ballistic-payloads', 'window'], ['ballistic-fit', 'fit']]) {
    await page.locator(`#tab-${id}`).click();
    await page.waitForTimeout(350);
    const selectors = ['h1', 'h2', 'h3', 'legend', 'summary', '.el-form-item__label', '.el-button', '[role=tab]'];
    states.push({ page: key, content: await page.locator(selectors.join(',')).allTextContents(),
      inputs: await page.locator('input:visible, textarea:visible').evaluateAll(nodes => nodes.map(node => ({ placeholder: node.placeholder, value: node.value, type: node.type }))),
      columns: await page.locator('.workbench-column').evaluateAll(nodes => nodes.map(node => ({ x: node.offsetLeft, width: node.offsetWidth }))) });
    await page.screenshot({ path: `verification/A19/baseline-${key}.png`, fullPage: true });
  }
  for (const [id, key] of [['simulation', 'simulation'], ['retime', 'retime']]) {
    await page.locator(`#tab-${id}`).click();
    await page.waitForTimeout(300);
    states.push({ page: key, text: await page.locator('.ballistic-fit-tabs').innerText(), inputs: await page.locator('input:visible, textarea:visible').evaluateAll(nodes => nodes.map(node => ({ placeholder: node.placeholder, value: node.value, type: node.type }))) });
    await page.screenshot({ path: `verification/A19/baseline-${key}.png`, fullPage: true });
  }
  return { states, errors };
}
