import { ballisticComputeBaseUrl, checkBallisticResponse } from './ballisticHttp'
/** 原请求保持包字段/单位/UTC，Web已在输入边界明确省略outputDir；响应原字节用于下载。 */
export async function fitBallisticPackage102(value) {
  const response = await fetch(`${ballisticComputeBaseUrl}/trajectory/fit/package102`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  })
  await checkBallisticResponse(response, '102包拟合')
  const rawBytes = await response.arrayBuffer()
  const rawText = new TextDecoder('utf-8').decode(rawBytes)
  return { response: JSON.parse(rawText), rawText, rawBytes }
}

/**
 * 原HTTP字节与typed响应并存，下载不重新序列化。
 * @typedef {Object} BallisticFitHttpResult
 * @property {BallisticFitResult} response
 * @property {string} rawText
 * @property {ArrayBuffer} rawBytes
 */
