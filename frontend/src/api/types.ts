export interface Satellite {
  id: number
  name: string
  modelType: string
  type?: string
  scid?: number
  objectId?: string
  objectName?: string
  noradCatId?: number
  country?: string
  period?: number
  catalogInclination?: number
  apogee?: number
  perigee?: number
  rcsSize?: string
  rcsValue?: number
  launchDate?: string
  catalogComment?: string
  weight?: number
}

/** 供卫星选择器加载的轻量目录字段。 */
export interface SatelliteOption {
  id: number
  name: string
  modelType: string
  type?: string
  scid?: number
  noradCatId?: number
}

/** 我方卫星固定使用的对空观测载荷长期配置。 */
export interface SpacePayloadConfiguration {
  configured: boolean
  payloadType: 'SPACE'
  /** 新接口的业务输入单位；后端内部和兼容字段仍保留米。 */
  minDistanceKm?: number
  maxDistanceKm?: number
  /** @deprecated 仅兼容旧服务端/集成方，新增界面不应直接编辑该字段。 */
  minDistanceM?: number
  maxDistanceM?: number
  azimuthMinDeg?: number
  azimuthMaxDeg?: number
  elevationMinDeg?: number
  elevationMaxDeg?: number
  angleRateDegPerSec?: number
  sunAvoidHalfAngleDeg?: number
  startupAngleRateDegPerSec?: number | null
}

/** 保存对空观测载荷时需要填写的参数。 */
export interface SpacePayloadConfigurationRequest {
  /** 规划人员输入和界面展示统一使用公里，服务端在接口边界换算为米。 */
  minDistanceKm?: number
  maxDistanceKm?: number
  /** @deprecated 仅供尚未升级的外部调用方以米提交。 */
  minDistanceM?: number
  maxDistanceM?: number
  azimuthMinDeg?: number
  azimuthMaxDeg?: number
  elevationMinDeg?: number
  elevationMaxDeg?: number
  angleRateDegPerSec?: number
  sunAvoidHalfAngleDeg?: number
  startupAngleRateDegPerSec?: number | null
}

/** 当前规划系统支持的载荷类别；保存任一类别不会覆盖另一类的长期配置。 */
export type PayloadType = 'SPACE' | 'GROUND'

/**
 * 对空或对地的一份独立长期配置。两类载荷的距离、姿态和角速度字段完全一致，
 * 距离均按公里填写；旧锥角字段仅用于读取历史对地记录。
 */
export interface PayloadProfileConfiguration {
  payloadType: PayloadType
  /** true 时表示服务端读取到历史锥角记录，界面需提示用户按统一字段重新保存。 */
  legacyGroundProfile?: boolean
  minDistanceKm?: number
  maxDistanceKm?: number
  azimuthMinDeg?: number
  azimuthMaxDeg?: number
  elevationMinDeg?: number
  elevationMaxDeg?: number
  angleRateDegPerSec?: number
  sunAvoidHalfAngleDeg?: number
  startupAngleRateDegPerSec?: number | null
  sensorFullConeAngleDeg?: number
  maxDetectRangeKm?: number
  sunExclusionAngleDeg?: number
}

/** 卫星的所有持久化载荷及当前参与可见性计算的活动类型。 */
export interface PayloadProfilesResponse {
  activePayloadType: PayloadType | null
  profiles: PayloadProfileConfiguration[]
}

/** 通用分页响应结构。 */
export interface PageResponse<T> {
  total: number
  pageNumber: number
  pageSize: number
  records: T[]
}

export interface Station {
  id: string
  name: string
  longitude?: number
  latitude?: number
  height?: number
  minElevation?: number
  maxElevation?: number
}

export interface Target {
  id: number
  name: string
  modelType: string
  belong?: string
  type?: string
  scid?: number
  objectId?: string
  objectName?: string
  noradCatId?: number
  country?: string
  period?: number
  catalogInclination?: number
  apogee?: number
  perigee?: number
  rcsSize?: string
  rcsValue?: number
  launchDate?: string
  catalogComment?: string
  weight?: number
}

export interface PlanningDefaults {
  preGuardSec: number
  postGuardSec: number
  allowedGapSec: number
  /** 星历可围绕任务时间做近似传播的最大天数；缺失时代表连接的是旧版服务。 */
  orbitMaxApproximationDays?: number
  /** 目录历元下限的前端默认值，采用 YYDDD，例如 23340。 */
  targetMinTleEpochCode?: string
}

export interface WindowResponse {
  startTime: string
  endTime: string
  durationSec: number
}

export interface StationConstraintRequest {
  stationId: string
  minElevationDeg?: number
  maxElevationDeg?: number
}

/** 表示用户在本次下传规划中明确选择的一条卫星—地面站关系。 */
export interface SatelliteStationLinkRequest {
  satelliteId: number
  stationId: string
}

/** 表示同一目标的单星或多星共同观测时长约束。 */
export interface ObservationConstraintRequest {
  satelliteIds: number[]
  minDurationSec: number
}

export interface DownlinkPlanRequest {
  startTime: string
  endTime: string
  coarseStepSec: number
  fineStepSec: number
  satelliteIds: number[]
  preGuardSec?: number
  postGuardSec?: number
  allowedGapSec?: number
  satelliteIllumination?: 'ANY' | 'SUNLIT' | 'SHADOW'
  stations: StationConstraintRequest[]
  links: SatelliteStationLinkRequest[]
}

export interface DownlinkPlanResponse {
  satelliteStationWindows: Array<{
    satelliteId: number
    stationId: string
    windows: WindowResponse[]
  }>
  /**
   * 不存在时间断档的多链路覆盖并集。任务窗口选择应使用此列表，
   * satelliteStationWindows 仅用于追溯每条原始链路。
   */
  coverageWindows: Array<WindowResponse & {
    links: Array<{ satelliteId: number; stationId: string }>
  }>
  /** 未能完成计算的站星链路；与“正常但无窗口”明确区分。 */
  skippedLinks?: Array<{
    satelliteId: number
    satelliteName: string
    stationId: string
    stationName: string
    stage: 'SCAN'
    reason: string
  }>
  totalSkippedLinks?: number
}

export interface WindowValidationResponse {
  satisfied: boolean
  plan: DownlinkPlanResponse
}

export interface VisibilityArc extends WindowResponse {}

/** 观测卫星或目标在一个状态稳定观测弧段内的精确光照信息。 */
export interface IlluminationDetail {
  state: 'SUNLIT' | 'PENUMBRA' | 'UMBRA'
  stateName: string
}

/** 参与当前观测的单颗我方卫星的精确光照信息。 */
export interface ObserverIllumination extends IlluminationDetail {
  satelliteId: string
  satelliteName: string
}

/** 当前被观测目标的精确光照信息。 */
export interface TargetIllumination extends IlluminationDetail {
  targetId: string
  targetName: string
}

/** 组合观测弧段展开后展示的单颗卫星独立观测时段；单星弧段不返回该结构。 */
export interface SatelliteSegment extends IlluminationDetail {
  satelliteId: string
  satelliteName: string
  startTime: string
  endTime: string
  durationSec: number
  /** 该星覆盖本时段的观测统计（距离/方位双口径/俯仰/最大角速度）；旧数据为空。 */
  statistics?: SatelliteSegmentStatistics
}

/** 单星观测统计极值；方位同时给出镜像与 +180° 两种口径，俯仰为天底夹角口径。 */
export interface SatelliteSegmentStatistics {
  samples: number
  minDistanceKm?: number | null
  maxDistanceKm?: number | null
  minAzimuthDeg?: number | null
  maxAzimuthDeg?: number | null
  azimuthRelativeMinusXMinDeg?: number | null
  azimuthRelativeMinusXMaxDeg?: number | null
  minElevationDeg?: number | null
  maxElevationDeg?: number | null
  elevationZenithMinDeg?: number | null
  elevationZenithMaxDeg?: number | null
  maxAngleRateDegPerSec: number
}

/** “确认选择”的逐星提交明细。 */
export interface SelectionSatellite {
  satelliteId: string
  satelliteName: string
  startTime: string
  endTime: string
  durationSec: number
  distanceMinKm?: number | null
  distanceMaxKm?: number | null
  azimuthRelativeMinusXMinDeg?: number | null
  azimuthRelativeMinusXMaxDeg?: number | null
  elevationZenithMinDeg?: number | null
  elevationZenithMaxDeg?: number | null
  maxAngleRateDegPerSec?: number | null
}

/** “确认选择”的逐目标提交明细。 */
export interface SelectionTarget {
  targetId: string
  targetName: string
  noradCatId?: string | null
  weightKg?: number | null
  firstOrbitLine?: string | null
  secondOrbitLine?: string | null
  combination: string
  mergedWindows: Array<{ startTime: string; endTime: string; durationSec: number }>
  totalDurationSec: number
  satellites: SelectionSatellite[]
}

/** 前端按勾选目标自动合并出的完整时段。 */
export interface SelectionMergedTimeRange {
  segments: Array<{ startTime: string; endTime: string; durationSec: number }>
  totalDurationSec: number
}

/** 确认选择请求体。 */
export interface SelectionConfirmRequest {
  planningWindow?: { startTime: string; endTime: string }
  mergedTimeRange: SelectionMergedTimeRange
  selections: SelectionTarget[]
  remark?: string
}

/** 确认选择回执。 */
export interface SelectionReceipt {
  recordId: string
  received: boolean
  selectionCount: number
  mergedSegments: number
  message: string
}

/**
 * 已通过组合时长门槛的父窗口内部，因光照状态变化形成的稳定片段。
 * 片段只用于展开核查；主列表必须使用父窗口本身的时长，不能据此再次判断门槛。
 */
export interface IlluminationSegment {
  startTime: string
  endTime: string
  durationSec: number
  observerIlluminations: ObserverIllumination[]
  targetIllumination: TargetIllumination
}

/** 一次目标观测规划临时使用的观测星—目标光照组合。 */
export interface IlluminationConstraintRequest {
  mode: 'ANY' | 'TARGET_SUNLIT' | 'OBSERVER_SHADOW_TARGET_SUNLIT' | 'BOTH_SHADOW' | 'CUSTOM'
  observerStates: Array<'SUNLIT' | 'PENUMBRA' | 'UMBRA'>
  targetStates: Array<'SUNLIT' | 'PENUMBRA' | 'UMBRA'>
}

export interface VisibilityPlanRequest {
  observerSatelliteIds: number[]
  /** 由“目标筛选”接口返回的完整命中 ID；规划端据此直接读取，不再二次筛选。 */
  targetIds: number[]
  /** 旧版直调接口的可选筛选字段；工作台完成筛选后不再提交这些字段。 */
  belongs?: string[]
  minWeight?: number
  maxWeight?: number
  /** WinTool 的五位 YYDDD 目录历元下限。 */
  minTleEpochCode?: string
  /** 超出该时长仍会尝试计算，但响应会返回星历时效风险说明。 */
  maxApproximationDays?: number
  excludeNameRegex?: string
  startTime: string
  endTime: string
  coarseStepSec: number
  fineStepSec: number
  illuminationConstraint: IlluminationConstraintRequest
  observationConstraints: ObservationConstraintRequest[]
}

export interface VisibilityPlanResponse {
  results: Array<{
    targetId: string
    targetName: string
    observerSatelliteIds: string[]
    observerSatelliteNames: string[]
    observationType: 'SINGLE' | 'COOPERATIVE'
    observationTypeName: string
    /** WinTool 简约格式摘要：任务摘要（各组合共视时长）+ 各单星观测时段。 */
    conciseSummary?: string
    /** 目标目录元数据：供 WinTool 格式摘要块渲染；目录缺失时为 null。 */
    targetNorad?: string | null
    targetWeightKg?: number | null
    firstOrbitLine?: string | null
    secondOrbitLine?: string | null
    arcs: Array<VisibilityArc & {
      observerIlluminations: ObserverIllumination[]
      targetIllumination: TargetIllumination
      /** ≥2 颗星的组合弧段才返回各星独立观测时段；单星弧段省略，前端据此不显示展开。 */
      satelliteSegments?: SatelliteSegment[]
      /** 光照变化明细；父弧段已通过组合时长门槛，明细不能作为独立规划结果显示。 */
      illuminationSegments?: IlluminationSegment[]
    }>
  }>
  /** 同步响应最多展示 500 条；完整清单保存在异步任务结果记录中。 */
  skippedPairs?: Array<{
    observerSatelliteId: string
    observerSatelliteName: string
    targetId: string
    targetName: string
    stage: 'PRECHECK' | 'SCAN'
    reason: string
  }>
  totalSkippedPairs?: number
  /** 星历超出建议时效、但未被阻断的目标风险说明。 */
  ephemerisWarnings?: Array<{
    targetId: string
    targetName: string
    reason: string
  }>
  /** 任务时间筛选统计；任务开始后才发射的目标会在传播前排除，星历时效仅作风险提示。 */
  targetFilterBreakdown?: TargetFilterBreakdown
}

export interface TargetQueryResponse {
  total: number
  pageNumber: number
  pageSize: number
  targets: Target[]
  /** 未分页的完整命中 ID 列表；用于让规划严格复用当前筛选结果。 */
  targetIds: number[]
  /** 各硬筛选条件按国籍、质量、历元、发射时间、名称排除顺序叠加后的命中数；星历时效另作提示统计。 */
  filterBreakdown?: TargetFilterBreakdown
}

/** 用于说明目标筛选条件实际收敛效果的顺序命中统计。 */
export interface TargetFilterBreakdown {
  belongsHit: number
  weightHit: number
  epochHit: number
  /** 应用 YYDDD 历元约束后的目标数。 */
  epochConstraintHit?: number
  /** 超出建议近似传播时长、仍会参与计算的目标数。 */
  ephemerisWarningHit?: number
  excludeHit: number
  /** 服务端本次实际采用的星历近似时长；未按任务时间筛选的旧响应为 0 或缺失。 */
  maxApproximationDays?: number
}

/** 后端根据目标表 BELONG_ 实际值生成的筛选字典项。 */
export interface BelongOption {
  code: string
  count: number
  description: string
}

/** 异步可见性规划任务的运行状态。 */
export type PlanningTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/** 提交异步可见性规划任务后返回的任务标识。 */
export interface VisibilityPlanningTaskCreateResponse {
  taskId: string
}

/** 异步可见性规划任务的状态与进度信息。 */
export interface VisibilityPlanningTaskResponse {
  taskId: string
  status: PlanningTaskStatus
  totalPairs: number
  completedPairs: number
  /** 进度按筛选后的目标数量展示：配对数 = 目标数 × 观测星数。 */
  totalTargets?: number
  completedTargets?: number
  errorMessage?: string | null
  createdAt?: string
  completedAt?: string | null
}

/** 异步可见性规划任务的持久化结果记录（列表项，不含请求与结果 JSON 大字段）。 */
export interface VisibilityTaskResultSummary {
  /**
   * 雪花主键的十进制文本。不能使用 number：主键可能超过 JavaScript 安全整数上限，
   * 数值解析会改变末尾数字，使后续“查看明细”等请求命中错误的记录。
   */
  id: string
  taskId: string
  taskType: string
  status: PlanningTaskStatus
  totalPairs?: number | null
  completedPairs?: number | null
  totalTargets?: number | null
  completedTargets?: number | null
  errorMessage?: string | null
  remark?: string | null
  createTime?: string
  completeTime?: string | null
}

/** 异步可见性规划任务持久化结果记录的全字段明细，包含请求与结果 JSON 原文。 */
export interface VisibilityTaskResultDetail extends VisibilityTaskResultSummary {
  requestJson?: string | null
  resultJson?: string | null
  /** 任务成功时保存的未截断 skippedPairs JSON 原文。 */
  skippedPairsJson?: string | null
  /** “确认选择”提交的明细 JSON 原文；未做过确认的历史记录为 null。 */
  selectionJson?: string | null
}

/** 任务结果记录分页查询参数。 */
export interface VisibilityTaskResultPageQuery {
  pageNumber?: number
  pageSize?: number
  status?: PlanningTaskStatus
  keyword?: string
}
