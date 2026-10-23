import { ballisticAuxBaseUrl, checkBallisticResponse } from './ballisticHttp'
/** 只登记实际计算响应的服务csvPath；重复登记保持服务ID，不修改原FitRunResult。 */
export async function registerBallisticResult(csvPath, taskName, targetId) {
  const response = await fetch(`${ballisticAuxBaseUrl}/files/register-result`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ csvPath, taskName, targetId }),
  })
  await checkBallisticResponse(response, '结果文件登记')
  // 合同只有201创建/复用登记才授予真实FileReference；异常2xx不能开放窗口来源或辅助下载。
  if (response.status !== 201)
    throw new Error(`结果文件登记须返回HTTP 201，实际HTTP ${response.status}，未获得有效引用`)
  return await response.json()
}
/** 原生 multipart，浏览器自动写 boundary，不能强行设置 JSON Content-Type。 */
export async function uploadBallisticFile(file) {
  const body = new FormData()
  body.append('file', file)
  body.append('purpose', 'visibility')
  const response = await fetch(`${ballisticAuxBaseUrl}/files`, { method: 'POST', body })
  await checkBallisticResponse(response, '弹道上传')
  if (response.status !== 201) throw new Error(`弹道上传须返回HTTP 201，实际HTTP ${response.status}，未获得有效引用`)
  return await response.json()
}
/** 从1开始分页，固定50行不超过服务500上限；不改变原文件或计算采样。 */
export async function previewBallisticFile(id, page = 1) {
  const response = await fetch(`${ballisticAuxBaseUrl}/files/${encodeURIComponent(id)}/preview?page=${page}&limit=50`)
  await checkBallisticResponse(response, '文件预览')
  return await response.json()
}
/** 请求真实二进制并解码 RFC5987 中文名；失败不创建可下载 Blob。 */
export async function downloadBallisticFile(id) {
  const response = await fetch(`${ballisticAuxBaseUrl}/files/${encodeURIComponent(id)}/download`)
  await checkBallisticResponse(response, '原文件下载')
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  const filename = encoded
    ? decodeURIComponent(encoded)
    : /filename="?([^";]+)/i.exec(disposition)?.[1] || '弹道原文件.csv'
  return { blob: await response.blob(), filename }
}

/**
 * 成功上传后的服务引用；本机 File.name/fakepath 永远不能代替 serverPath。
 * @typedef {Object} BallisticFileReference
 * @property {string} fileId
 * @property {string} originalName
 * @property {number} sizeBytes
 * @property {string} contentType
 * @property {string} serverPath
 */

/**
 * App按来源维护的当前会话实际201结果引用；刷新不持久化，不扫描服务目录。
 * @typedef {Object} GeneratedBallisticResult
 * @property {string} targetId
 * @property {string} targetName
 * @property {BallisticFileReference} reference
 */

/**
 * 原表头/单元格文本，metadata 只转呈服务已识别的单位，不猜测未知坐标。
 * @typedef {Object} BallisticFilePreview
 * @property {string} fileId
 * @property {string[]} header
 * @property {string[][]} rows
 * @property {number} totalRows
 * @property {number} page
 * @property {number} limit
 * @property {Record<string, string>} metadata
 */
