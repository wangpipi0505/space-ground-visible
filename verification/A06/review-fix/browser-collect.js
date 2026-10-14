async page => {
  await page.waitForLoadState('networkidle')
  page.__a06fix.finishedUtc = new Date().toISOString()
  page.__a06fix.browser = await page.context().browser().version()
  page.__a06fix.timezone = await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone)
  const pending = page.waitForEvent('download')
  await page.evaluate(text => {
    const link = document.createElement('a')
    link.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }))
    link.download = 'A06-review-fix-browser-network.json'
    document.body.append(link); link.click(); link.remove()
  }, JSON.stringify(page.__a06fix, null, 2))
  await (await pending).saveAs('G:/5-YG/space-ground-visible/verification/runtime/A06/review-fix/browser-network.json')
  return { browser: page.__a06fix.browser, timezone: page.__a06fix.timezone, checks: page.__a06fix.checks, networkCount: page.__a06fix.network.length, statuses: page.__a06fix.network.map(row => ({ url: row.url, status: row.status })), errors: page.__a06fix.errors, completedUtc: page.__a06fix.finishedUtc }
}
