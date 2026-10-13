import type { BallisticFitHttpResult } from './ballisticFit'
import { ballisticComputeBaseUrl, checkBallisticResponse } from './ballisticHttp'
import type { BallisticFileReference } from './ballisticFiles'

/** 原PhaseEvent全部11字段；字符串/null保持原值，数值为原Java double SI口径。当前模型只返回预留告警。 */
export interface SimulationPhaseEvent {
  phaseType?: string | null; eventType?: string | null; startTime?: string | null; endTime?: string | null
  eventTime?: string | null; dynamics?: string | null; directionMode?: string | null
  accelerationMs2?: number | null; deltaVms?: number | null; liftDragRatio?: number | null; dragCdAreaOverMass?: number | null
}
/**
 * 原SimulationRequest的34个Web字段（原第35字段outputDir唯一省略）。
 * 所有字段可省略/null，缺省与Java primitive null绑定交给原服务；时间不经本地Date。
 * XYZ为对应端点坐标：笛卡尔默认J2000/m，WGS84为经度度/纬度度/高度米（忽略unit）。
 * outputPointDurationSec首个有限正数优先outputStepSec，否则1秒；原normalizeStep至少.001。
 * 旧动力学/属性保留字段当前不参与端点Lambert/二体RK4，不扩展模型选择。
 */
export interface BallisticSimulationRequest {
  taskName?: string | null; targetId?: string | null; taskStartTime?: string | null; taskEndTime?: string | null
  launchTimeUtc?: string | null; impactTimeUtc?: string | null; impactTime?: string | null
  launchPointX?: number | null; launchPointY?: number | null; launchPointZ?: number | null
  launchPointCoordFrame?: string | null; launchPointUnit?: string | null
  impactPointX?: number | null; impactPointY?: number | null; impactPointZ?: number | null
  impactPointCoordFrame?: string | null; impactPointUnit?: string | null
  outputPointDurationSec?: number | null; phaseEvents?: SimulationPhaseEvent[] | null
  targetTypeCode?: string | null; targetShapeCode?: string | null; flightPhaseCode?: string | null
  launchLatDeg?: number | null; launchLonDeg?: number | null; launchHeightM?: number | null
  initialSpeedMs?: number | null; azimuthDeg?: number | null; elevationDeg?: number | null
  durationSec?: number | null; integrationStepSec?: number | null; outputStepSec?: number | null
  boostDurationSec?: number | null; boostAccelerationMs2?: number | null; dragCdAreaOverMass?: number | null
}
/** App维护的真正已登记结果选择：原目标身份与201引用；不携带假上传或由csvPath拼出的fileId。 */
export interface GeneratedBallisticResult { targetId: string; targetName: string; reference: BallisticFileReference }

/** 原路由与原响应字节保留；不新增model字段/包装，不改后端错误中文。 */
export async function simulateBallisticTrajectory(request: BallisticSimulationRequest): Promise<BallisticFitHttpResult> {
  const response = await fetch(`${ballisticComputeBaseUrl}/trajectory/simulate`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) })
  await checkBallisticResponse(response, '模拟弹道')
  const rawBytes = await response.arrayBuffer()
  const rawText = new TextDecoder().decode(rawBytes)
  return { response: JSON.parse(rawText), rawText, rawBytes }
}
