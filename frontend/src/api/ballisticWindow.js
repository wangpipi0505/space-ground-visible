import { ballisticComputeBaseUrl, checkBallisticResponse } from './ballisticHttp'
/** 一次请求一次结果：既不改 best，也不重新采样/归并原层级与累计计数。 */
export async function searchBallisticWindows(value) {
  const response = await fetch(`${ballisticComputeBaseUrl}/orbit/visibility/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  })
  await checkBallisticResponse(response, '窗口计算')
  const rawBytes = await response.arrayBuffer()
  const rawText = new TextDecoder('utf-8').decode(rawBytes)
  return { response: JSON.parse(rawText), rawText, rawBytes }
}

/**
 * 原计算根独立于空间/辅助根；默认 /api，对应冻结的 /orbit/visibility/search。
typed 结果用于展示；原 UTF8 文本及字节单独保留用于无损导出，不重新 JSON.stringify。
 * @typedef {Object} BallisticWindowHttpResult
 * @property {BallisticWindowResponse} response
 * @property {string} rawText
 * @property {ArrayBuffer} rawBytes
 */
