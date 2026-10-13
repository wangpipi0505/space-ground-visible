/** 真实浏览器HTTP原上传文件下载；ID取本次已记录201，不从客户端路径拼造。 */
async (page) => {
  const references = [
    { name: 'default-upload', fileId: 'edceaa9b-4bb8-4cb3-a25a-ebeeaf80cc5d', expectedBytes: 561736, expectedSha256: '3dbfc8c5bc76cbe4f1e06b81fcba2ccc710c29acc84a011608150932c6f404a4' },
  ]
  const results = []
  for (const reference of references) {
    const actual = await page.evaluate(async id => {
      const response = await fetch(`/api/v1/ballistic/files/${encodeURIComponent(id)}/download`)
      const bytes = await response.arrayBuffer(), hash = await crypto.subtle.digest('SHA-256', bytes)
      return { status: response.status, bytes: bytes.byteLength, sha256: Array.from(new Uint8Array(hash)).map(value => value.toString(16).padStart(2, '0')).join('') }
    }, reference.fileId)
    if (actual.status !== 200 || actual.bytes !== reference.expectedBytes || actual.sha256 !== reference.expectedSha256) throw new Error(`原上传HTTP下载字节改变${JSON.stringify(actual)}`)
    results.push({ ...reference, ...actual })
  }
  return { results, allOriginalUploadHttpByteEqual: true }
}
