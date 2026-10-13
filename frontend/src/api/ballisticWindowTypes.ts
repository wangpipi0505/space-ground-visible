import type { BallisticPayloadDefinition } from './ballisticPayloads'

/** 原 DTO 根数草稿：米/度，无目录根数时留空；epochUtc 为字面 UTC，空值沿原服务缺省历元。 */
export interface SatelliteOrbitDraft {
  semiMajorAxisM: number | null
  eccentricity: number | null
  inclinationDeg: number | null
  raanDeg: number | null
  argumentOfPerigeeDeg: number | null
  meanAnomalyDeg: number | null
  epochUtc: string
}
/** T21 单一卫星草稿，直接供 T22 提交，不复制另一份载荷配置。 */
export interface BallisticSatelliteDraft extends SatelliteOrbitDraft {
  satelliteId: string
  name: string
  payloads: BallisticPayloadDefinition[]
}
/** csvPath 仅来自成功上传引用或受支持远程地址；目标身份在增删后不复用。 */
export interface BallisticTargetInput {
  targetId: string
  targetName: string
  csvPath: string
  baseLaunchUTC?: string
}
/** 原 VisibilityRequest 白名单；秒、long 安全整数、原模型，无浏览器计算或重采样。 */
export interface BallisticWindowRequest {
  ballistics: BallisticTargetInput[]
  satellites: BallisticSatelliteDraft[]
  startOffsetSec: number
  stepSizeSec: number
  numCalculations: number
  targetSampleStepSec: number
  trajectoryPointOutput: 0 | 1
  orbitModel: 'TWO_BODY' | 'J2_SECULAR' | 'J2_NUMERICAL'
}
/** 原窗口摘要与原格式器字段，不从四锥角摘要逆推出标准矩形载荷。 */
export interface VisibilityConstraintSummary {
  payloadType: string
  maxDetectRangeKm: number
  sunExclusionAngleDeg: number
  simpleConeHalfAngleDeg: number | null
  halfConeAngleDeg: number | null
  innerConeAngleDeg: number | null
  outerConeAngleDeg: number | null
}
export interface BallisticTimeWindow {
  startTime: string
  endTime: string
  startJ2000Sec: number
  endJ2000Sec: number
  durationSeconds: number
}
/** 窗口内真实轨迹点；经纬度度、高度米、两类秒及字面 UTC，不能用原文件点冒充。 */
export interface BallisticTrajectoryPoint {
  longitudeDeg: number
  latitudeDeg: number
  heightM: number
  absoluteTimeJ2000Sec: number
  absoluteTimeUtc: string
  relativeTimeSec: number
  sourceTrajectoryUtc: string
}
export interface BallisticVisibleArc extends BallisticTimeWindow {
  arcIndex: number
  payloadId: string
  payloadName: string
  payloadType: string
  pointCount: number
  trajectoryPoints: BallisticTrajectoryPoint[]
}
export interface BallisticTargetArcs {
  targetId: string
  targetName: string
  payloadId: string
  payloadName: string
  payloadType: string
  visibleArcs: BallisticVisibleArc[]
}
export interface BallisticPayloadResult {
  payloadId: string
  payloadName: string
  payloadType: string
  visibilityConstraint: VisibilityConstraintSummary
  finalVisibleWindow: BallisticTimeWindow | null
  targets: BallisticTargetArcs[]
}
export interface BallisticSatelliteResult {
  satelliteId: string
  satelliteName: string
  satelliteIndex: number
  finalVisibleWindow: BallisticTimeWindow | null
  payloadResults: BallisticPayloadResult[]
}
export interface BallisticWindowSummary extends BallisticTimeWindow {
  satelliteId: string
  satelliteName: string
  satelliteIndex: number
  arcIndex: number
  payloadId: string
  payloadName: string
  payloadType: string
  pointCount: number
}
export interface BallisticTargetResult {
  targetId: string
  targetName: string
  csvPath: string
  baseLaunchUTC: string
  launchUTC: string
  payloadType: string
  launchTimeJ2000Sec: number
  finalVisibleWindow: BallisticTimeWindow | null
  visibleWindows: BallisticWindowSummary[]
}
/** 服务逐星逐载荷累计值直接显示，不对时间或点数去重重算。 */
export interface BallisticOffsetResult {
  offsetSecond: number
  launchTimeUtc: string
  launchTimeJ2000Sec: number
  payloadType: string
  visibilityConstraint: VisibilityConstraintSummary
  visibleArcCount: number
  visiblePointCount: number
  visibleDurationSec: number
  finalVisibleWindow: BallisticTimeWindow | null
  targets: BallisticTargetResult[]
  visibleSatellites: BallisticSatelliteResult[]
}
export interface BallisticWindowResponse {
  referenceUtc: string
  payloadType: string
  visibilityConstraint: VisibilityConstraintSummary
  best: BallisticOffsetResult | null
  allResults: BallisticOffsetResult[]
}
