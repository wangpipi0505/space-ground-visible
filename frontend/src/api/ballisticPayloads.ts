import { ApiRequestError } from './client'
import type { StandardPayloadRequest } from '../utils/standardPayload'

/** 预览完整保持 payloadId/payloadName 与十字段的 null/0/-1；身份不进入共用编辑器。 */
export interface BallisticPayloadDefinition extends StandardPayloadRequest {
  payloadId: string
  payloadName: string
}

// 此变量表示含 /api/v1/ballistic 的完整接口根；不能在空间 VITE_API_BASE_URL 后拼接弹道路径。
const ballisticBaseUrl = (import.meta.env.VITE_BALLISTIC_API_BASE_URL?.trim() || '/api/v1/ballistic').replace(/\/$/, '')

/** 调用 T20 辅助校验/回显，不持久化且不启动窗口计算；HTTP 400 保留中文后端错误。 */
export async function previewBallisticPayload(payload: BallisticPayloadDefinition): Promise<BallisticPayloadDefinition> {
  const response = await fetch(`${ballisticBaseUrl}/payloads/preview`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  })
  const responseText = await response.text()
  let body: unknown
  try { body = JSON.parse(responseText) } catch { body = responseText }
  if (!response.ok) {
    const message = typeof body === 'object' && body !== null && 'error' in body && typeof body.error === 'string'
      ? body.error : `载荷预览失败（HTTP ${response.status}）`
    throw new ApiRequestError(response.status, message)
  }
  // 与空间 typed client 相同，在 HTTP 边界按明确后端契约读取，不扩展旧计算响应。
  return body as BallisticPayloadDefinition
}
