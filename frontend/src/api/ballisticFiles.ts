import { ballisticAuxBaseUrl, checkBallisticResponse } from './ballisticHttp'

/** 成功上传后的服务引用；本机 File.name/fakepath 永远不能代替 serverPath。 */
export interface BallisticFileReference {
  fileId: string
  originalName: string
  sizeBytes: number
  contentType: string
  serverPath: string
}
/** 原表头/单元格文本，metadata 只转呈服务已识别的单位，不猜测未知坐标。 */
export interface BallisticFilePreview {
  fileId: string
  header: string[]
  rows: string[][]
  totalRows: number
  page: number
  limit: number
  metadata: Record<string, string>
}
/** 只登记实际计算响应的服务csvPath；重复登记保持服务ID，不修改原FitRunResult。 */
export async function registerBallisticResult(csvPath: string, taskName: string, targetId: string): Promise<BallisticFileReference> {
  const response = await fetch(`${ballisticAuxBaseUrl}/files/register-result`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ csvPath, taskName, targetId }),
  })
  await checkBallisticResponse(response, '结果文件登记')
  // 合同只有201创建/复用登记才授予真实FileReference；异常2xx不能开放窗口来源或辅助下载。
  if (response.status !== 201) throw new Error(`结果文件登记须返回HTTP 201，实际HTTP ${response.status}，未获得有效引用`)
  return await response.json() as BallisticFileReference
}
/** 原生 multipart，浏览器自动写 boundary，不能强行设置 JSON Content-Type。 */
export async function uploadBallisticFile(file: File): Promise<BallisticFileReference> {
  const body = new FormData()
  body.append('file', file)
  body.append('purpose', 'visibility')
  const response = await fetch(`${ballisticAuxBaseUrl}/files`, { method: 'POST', body })
  await checkBallisticResponse(response, '弹道上传')
  return await response.json() as BallisticFileReference
}
/** 从1开始分页，固定50行不超过服务500上限；不改变原文件或计算采样。 */
export async function previewBallisticFile(id: string, page = 1): Promise<BallisticFilePreview> {
  const response = await fetch(`${ballisticAuxBaseUrl}/files/${encodeURIComponent(id)}/preview?page=${page}&limit=50`)
  await checkBallisticResponse(response, '文件预览')
  return await response.json() as BallisticFilePreview
}
/** 请求真实二进制并解码 RFC5987 中文名；失败不创建可下载 Blob。 */
export async function downloadBallisticFile(id: string): Promise<{ blob: Blob; filename: string }> {
  const response = await fetch(`${ballisticAuxBaseUrl}/files/${encodeURIComponent(id)}/download`)
  await checkBallisticResponse(response, '原文件下载')
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  const filename = encoded ? decodeURIComponent(encoded) : /filename="?([^";]+)/i.exec(disposition)?.[1] || '弹道原文件.csv'
  return { blob: await response.blob(), filename }
}
