import { ApiRequestError } from './client'

/** 辅助根保留 T21 含完整 /api/v1/ballistic 的既有含义，不拼在空间根后。 */
export const ballisticAuxBaseUrl = (import.meta.env.VITE_BALLISTIC_API_BASE_URL?.trim() || '/api/v1/ballistic').replace(/\/$/, '')

/** 成功二进制不消费；失败读取文本/中文 JSON 后抛出，防止错误 JSON 当作 CSV 保存。 */
export async function checkBallisticResponse(response: Response, action: string): Promise<void> {
  if (response.ok) return
  const raw = await response.text()
  let value: unknown
  try { value = JSON.parse(raw) } catch { value = raw }
  const message = typeof value === 'object' && value !== null && 'error' in value && typeof value.error === 'string'
    ? value.error : `${action}失败（HTTP ${response.status}）${typeof value === 'string' && value ? `：${value}` : ''}`
  throw new ApiRequestError(response.status, message)
}

/** 浏览器原生下载；每次创建独立 URL，点击后撤销，数据从真实 HTTP 获取。 */
export function saveBallisticBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  // 延迟到浏览器启动下载后释放；不将 URL 留在长期页面状态。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
