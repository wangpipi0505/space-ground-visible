/** 新浏览器逐项隔离CSS和真实布局影响；诊断不修改任何产品文件。 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('C:/Users/Administrator/AppData/Local/npm-cache/_npx/31e32ef8478fbf80/node_modules/playwright')
;(async () => {
  const mode = process.argv[2] || 'normal'
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage()
  const events = []
  function record(event) {
    events.push(event)
    fs.writeFileSync(path.join(__dirname, 'layout-' + mode + '.json'), JSON.stringify(events, null, 2))
  }
  page.on('console', event => record({ console: event.type(), text: event.text() }))
  page.on('pageerror', error => record({ error: error.message }))
  page.on('requestfinished', request => record({ finished: request.url() }))
  if (mode === 'no-css') await page.route('**/*.css', route => route.fulfill({ body: '', contentType: 'text/css' }))
  if (mode === 'no-containment') await page.route('**/*.css', async route => {
    const response = await route.fetch()
    await route.fulfill({ response, body: (await response.text()).replace(/container-type:inline-size/g, 'container-type:normal') })
  })
  try {
    await page.goto('http://127.0.0.1:5176/', { timeout: 10000 })
    await page.waitForTimeout(500)
    record({ loaded: true, title: await page.title(), textLength: (await page.locator('body').innerText()).length })
  } catch (error) {
    record({ loaded: false, message: error.message })
  }
  console.log(JSON.stringify(events))
  await browser.close()
})().catch(error => { console.error(error); process.exitCode = 1 })
