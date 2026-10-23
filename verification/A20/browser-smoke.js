async (page) => {
  const errors = [];
  const responses = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => { if (!response.url().includes('/api/')) responses.push({ url: response.url(), status: response.status() }); });
  // 这里只验收解压后构建的前端页面，服务不可用的路径单独模拟，不借此声明计算接口已重测。
  await page.route('**/api/**', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"静态交付检查：未连接后端"}' }));
  await page.goto('http://127.0.0.1:5178/');
  const pages = [];
  for (const id of ['workbench', 'ballistic-payloads', 'ballistic-fit']) {
    await page.locator(`#tab-${id}`).click();
    await page.waitForTimeout(150);
    pages.push(id);
  }
  for (const id of ['simulation', 'retime']) {
    await page.locator(`#tab-${id}`).click();
    await page.waitForTimeout(150);
    pages.push(id);
  }
  if (errors.length) throw new Error(JSON.stringify(errors));
  if (responses.some(response => response.status >= 400)) throw new Error(JSON.stringify(responses));
  return { pages, pageErrors: errors, assets: responses, backendMode: '模拟503，仅检查静态页面和导航' };
}
