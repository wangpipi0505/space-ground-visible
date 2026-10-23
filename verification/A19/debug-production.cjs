/** 使用全新无头浏览器定位生产页面加载问题，保存网络与CPU采样，结束时只关闭本脚本创建的浏览器。 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('C:/Users/Administrator/AppData/Local/npm-cache/_npx/31e32ef8478fbf80/node_modules/playwright')
;(async () => {
  console.log('launch')
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  console.log('newPage')
  const page = await browser.newPage()
  console.log('newCDPSession')
  const events = []
  page.on('console', event => events.push({ console: event.type(), text: event.text() }))
  page.on('pageerror', error => events.push({ error: error.message }))
  page.on('requestfinished', request => events.push({ finished: request.url() }))
  const session = await page.context().newCDPSession(page)
  console.log('profiler')
  try {
    await session.send('Profiler.enable')
    await session.send('Profiler.start')
    console.log('navigate')
    const navigation = page.goto('http://127.0.0.1:5176/', { waitUntil: 'domcontentloaded', timeout: 10000 }).then(() => 'loaded', error => error.message)
    await new Promise(resolve => setTimeout(resolve, 3000))
    console.log('stopProfiler')
    const profile = await session.send('Profiler.stop')
    fs.writeFileSync(path.join(__dirname, 'production-cpu-profile.json'), JSON.stringify(profile))
    await session.send('Runtime.terminateExecution')
    events.push({ navigation: await navigation })
    fs.writeFileSync(path.join(__dirname, 'production-debug.json'), JSON.stringify(events, null, 2))
    console.log(JSON.stringify(events))
  } finally {
    await browser.close()
  }
})().catch(error => { console.error(error); process.exitCode = 1 })
