/** 同一生产静态页直接访问另一个 API Origin，实际 JSON 预检、multipart 和字节下载。 */
async (page) => {
  return await page.evaluate(async () => {
    const api = 'http://127.0.0.1:18130'
    const catalog = await fetch(api + '/api/v1/space/satellites')
    const stars = await catalog.json()
    const validation = await fetch(api + '/api/v1/space/targets/query', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    const source = await fetch('/api/v1/space/satellites')
    const bytes = await source.arrayBuffer()
    const form = new FormData()
    form.append('file', new Blob([bytes], { type: 'application/json' }), 'cors-catalog.json')
    const uploaded = await fetch(api + '/api/v1/ballistic/files', { method: 'POST', body: form })
    const reference = await uploaded.json()
    const downloaded = await fetch(api + '/api/v1/ballistic/files/' + reference.fileId + '/download')
    const actual = await downloaded.arrayBuffer()
    const sha = async value => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', value))).map(x => x.toString(16).padStart(2, '0')).join('')
    if (catalog.status !== 200 || !stars.length || validation.status !== 200 || uploaded.status !== 201 || downloaded.status !== 200 || !downloaded.headers.get('Content-Disposition') || await sha(actual) !== await sha(bytes)) throw new Error('实际跨域流失败')
    return { origin: location.origin, api, catalogStatus: catalog.status, satelliteCount: stars.length, jsonPreflightValidationStatus: validation.status, validationBody: await validation.text(), uploadStatus: uploaded.status, downloadStatus: downloaded.status, reference, disposition: downloaded.headers.get('Content-Disposition'), bytes: actual.byteLength, downloadSha256: await sha(actual), exactSourceSha256: await sha(bytes) }
  })
}
