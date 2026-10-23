import { ballisticComputeBaseUrl, checkBallisticResponse } from './ballisticHttp'
/** 原路由与原响应字节保留；不新增model字段/包装，不改后端错误中文。 */
export async function simulateBallisticTrajectory(request) {
  const response = await fetch(`${ballisticComputeBaseUrl}/trajectory/simulate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  await checkBallisticResponse(response, '模拟弹道')
  const rawBytes = await response.arrayBuffer()
  const rawText = new TextDecoder().decode(rawBytes)
  return { response: JSON.parse(rawText), rawText, rawBytes }
}

/**
 * 原PhaseEvent全部11字段；字符串/null保持原值，数值为原Java double SI口径。当前模型只返回预留告警。
 * @typedef {Object} SimulationPhaseEvent
 * @property {string | null} [phaseType]
 * @property {string | null} [eventType]
 * @property {string | null} [startTime]
 * @property {string | null} [endTime]
 * @property {string | null} [eventTime]
 * @property {string | null} [dynamics]
 * @property {string | null} [directionMode]
 * @property {number | null} [accelerationMs2]
 * @property {number | null} [deltaVms]
 * @property {number | null} [liftDragRatio]
 * @property {number | null} [dragCdAreaOverMass]
 */

/**
 * 原SimulationRequest的34个Web字段（原第35字段outputDir唯一省略）。
所有字段可省略/null，缺省与Java primitive null绑定交给原服务；时间不经本地Date。
XYZ为对应端点坐标：笛卡尔默认J2000/m，WGS84为经度度/纬度度/高度米（忽略unit）。
outputPointDurationSec首个有限正数优先outputStepSec，否则1秒；原normalizeStep至少.001。
旧动力学/属性保留字段当前不参与端点Lambert/二体RK4，不扩展模型选择。
 * @typedef {Object} BallisticSimulationRequest
 * @property {string | null} [taskName]
 * @property {string | null} [targetId]
 * @property {string | null} [taskStartTime]
 * @property {string | null} [taskEndTime]
 * @property {string | null} [launchTimeUtc]
 * @property {string | null} [impactTimeUtc]
 * @property {string | null} [impactTime]
 * @property {number | null} [launchPointX]
 * @property {number | null} [launchPointY]
 * @property {number | null} [launchPointZ]
 * @property {string | null} [launchPointCoordFrame]
 * @property {string | null} [launchPointUnit]
 * @property {number | null} [impactPointX]
 * @property {number | null} [impactPointY]
 * @property {number | null} [impactPointZ]
 * @property {string | null} [impactPointCoordFrame]
 * @property {string | null} [impactPointUnit]
 * @property {number | null} [outputPointDurationSec]
 * @property {SimulationPhaseEvent[] | null} [phaseEvents]
 * @property {string | null} [targetTypeCode]
 * @property {string | null} [targetShapeCode]
 * @property {string | null} [flightPhaseCode]
 * @property {number | null} [launchLatDeg]
 * @property {number | null} [launchLonDeg]
 * @property {number | null} [launchHeightM]
 * @property {number | null} [initialSpeedMs]
 * @property {number | null} [azimuthDeg]
 * @property {number | null} [elevationDeg]
 * @property {number | null} [durationSec]
 * @property {number | null} [integrationStepSec]
 * @property {number | null} [outputStepSec]
 * @property {number | null} [boostDurationSec]
 * @property {number | null} [boostAccelerationMs2]
 * @property {number | null} [dragCdAreaOverMass]
 */
