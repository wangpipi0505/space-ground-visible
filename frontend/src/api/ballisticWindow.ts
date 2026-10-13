import { checkBallisticResponse } from './ballisticHttp'
import type { BallisticWindowRequest, BallisticWindowResponse } from './ballisticWindowTypes'

/** 原计算根独立于空间/辅助根；默认 /api，对应冻结的 /orbit/visibility/search。 */
const computeBaseUrl = (import.meta.env.VITE_BALLISTIC_COMPUTE_BASE_URL?.trim() || '/api').replace(/\/$/, '')
/** typed 结果用于展示；原 UTF8 文本及字节单独保留用于无损导出，不重新 JSON.stringify。 */
export interface BallisticWindowHttpResult {
  response: BallisticWindowResponse
  rawText: string
  rawBytes: ArrayBuffer
}
/** 一次请求一次结果：既不改 best，也不重新采样/归并原层级与累计计数。 */
export async function searchBallisticWindows(value: BallisticWindowRequest): Promise<BallisticWindowHttpResult> {
  const response = await fetch(`${computeBaseUrl}/orbit/visibility/search`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value),
  })
  await checkBallisticResponse(response, '窗口计算')
  const rawBytes = await response.arrayBuffer()
  const rawText = new TextDecoder('utf-8').decode(rawBytes)
  return { response: JSON.parse(rawText) as BallisticWindowResponse, rawText, rawBytes }
}
