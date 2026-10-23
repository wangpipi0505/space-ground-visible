async (page) => {
  const stage = '__STAGE__';
  const errors = [];
  page.on('pageerror', failure => errors.push(failure.message));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('__URL__');
  await page.waitForLoadState('networkidle');
  const requests = [];
  const layouts = [];
  async function captureLayout(name) {
    await page.screenshot({ path: `verification/A19/${stage}-${name}.png`, fullPage: true });
    layouts.push({ name, columns: await page.locator('.workbench-column').evaluateAll(nodes => nodes.map(node => ({ x: node.offsetLeft, width: node.offsetWidth }))),
      headings: await page.locator('h1:visible,h2:visible,h3:visible,legend:visible,summary:visible').allTextContents(),
      inputs: await page.locator('input:visible,textarea:visible').evaluateAll(nodes => nodes.map(node => ({ placeholder: node.placeholder, value: node.value, height: node.getBoundingClientRect().height }))) });
  }
  async function submit(name, selector, route, success) {
    const pending = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith(route), { timeout: 180000 });
    await page.locator(selector).click();
    const response = await pending;
    const raw = await response.text();
    requests.push({ name, route, request: response.request().postDataJSON(), status: response.status(), raw, response: JSON.parse(raw) });
    if (response.status() !== 200) throw new Error(name + ': ' + raw);
    await page.locator(success).waitFor();
    await page.waitForTimeout(250);
  }
  await captureLayout('space');
  await page.locator('#tab-ballistic-payloads').click();
  await captureLayout('window');
  const preview = page.waitForResponse(response => response.url().includes('/preview?') && response.request().method() === 'GET');
  await page.locator('[data-test=upload-default-window]').click();
  await preview;
  await page.locator('[data-test=compute-window]').waitFor({ state: 'visible' });
  await submit('window', '[data-test=compute-window]', '/api/orbit/visibility/search', '[data-test=window-success]');
  await page.locator('#tab-ballistic-fit').click();
  await captureLayout('fit');
  await submit('fit', '[data-test=fit-compute]', '/api/trajectory/fit/package102', '[data-test=fit-success]');
  await page.locator('[data-test=fit-trajectory]:visible').first().waitFor({ timeout: 30000 });
  await page.locator('#tab-simulation').click();
  await captureLayout('simulation');
  await submit('simulation', '[data-test=simulation-compute]', '/api/trajectory/simulate', '[data-test=simulation-success]');
  await page.locator('#tab-retime').click();
  await captureLayout('retime');
  await page.locator('[data-test=retime-source-mode]').click();
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: /^已生成结果$/ }).click();
  await page.locator('[data-test=retime-result-select]').click();
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: /^拟合\// }).first().click();
  await page.locator('[data-test=retime-source-reference]').waitFor();
  await submit('retime', '[data-test=retime-compute]', '/api/trajectory/retime', '[data-test=retime-success]');
  const fileResponses = [];
  for (const item of requests.filter(item => item.name !== 'window')) {
    for (const file of item.response.files || []) {
      const registered = await page.request.post('__URL__/api/v1/ballistic/files/register-result', { data: { csvPath: file.csvPath, taskName: item.response.taskName, targetId: file.targetId } });
      if (registered.status() !== 201) throw new Error('登记失败: ' + await registered.text());
      const reference = await registered.json();
      const download = await page.request.get('__URL__/api/v1/ballistic/files/' + reference.fileId + '/download');
      if (download.status() !== 200) throw new Error('下载失败');
      fileResponses.push({ name: item.name, targetId: file.targetId, csv: (await download.body()).toString('utf8') });
    }
  }
  return { stage, errors, requests, fileResponses, layouts };
}
