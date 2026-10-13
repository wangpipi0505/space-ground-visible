import { ballisticAuxBaseUrl, checkBallisticResponse } from './ballisticHttp'
import type { StandardPayloadRequest } from '../utils/standardPayload'

/** 预览完整保持 payloadId/payloadName 与十字段的 null/0/-1；身份不进入共用编辑器。 */
export interface BallisticPayloadDefinition extends StandardPayloadRequest {
  payloadId: string
  payloadName: string
}

/** 调用 T20 辅助校验/回显，不持久化且不启动窗口计算；HTTP 400 保留中文后端错误。 */
export async function previewBallisticPayload(payload: BallisticPayloadDefinition): Promise<BallisticPayloadDefinition> {
  const response = await fetch(`${ballisticAuxBaseUrl}/payloads/preview`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
  })
  await checkBallisticResponse(response, '载荷预览')
  // 与空间 typed client 相同，在 HTTP 边界按明确后端契约读取，不扩展旧计算响应。
  return await response.json() as BallisticPayloadDefinition
}
