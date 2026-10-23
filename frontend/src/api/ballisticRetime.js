import { ballisticComputeBaseUrl, checkBallisticResponse } from './ballisticHttp'
/** 调原路由与原FitRunResult，保留400/500错误全文及HTTP响应原字节，不加fileId包装。 */
export async function retimeBallisticTrajectory(request) {
  const response = await fetch(`${ballisticComputeBaseUrl}/trajectory/retime`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  await checkBallisticResponse(response, '弹道重定时')
  const rawBytes = await response.arrayBuffer()
  const rawText = new TextDecoder().decode(rawBytes)
  return { response: JSON.parse(rawText), rawText, rawBytes }
}

/**
 * 原RetimeTrajectoryRequest四个Web字段；第五字段outputDir始终省略。
taskName默认retimeTrajectory、targetId默认RETIMED-TGT，null/空串回退由原Java处理。
trajectoryCsvPath只能来自上传/登记成功的serverPath；launchTimeUtc字面UTC由原算法解析。
 * @typedef {Object} BallisticRetimeRequest
 * @property {string | null} [taskName]
 * @property {string | null} [targetId]
 * @property {string} trajectoryCsvPath
 * @property {string | null} launchTimeUtc
 */
