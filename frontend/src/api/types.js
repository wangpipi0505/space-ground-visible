export {}

/**
 * 空间观测星目录显示字段；不含弹道手工根数，身份类型沿用旧API。未知目录量纲不在客户端猜测。
 * @typedef {Object} Satellite
 * @property {number} id
 * @property {string} name
 * @property {string} modelType
 * @property {string} [type]
 * @property {number} [scid]
 * @property {string} [objectId]
 * @property {string} [objectName]
 * @property {number} [noradCatId]
 * @property {string} [country]
 * @property {number} [period]
 * @property {number} [catalogInclination]
 * @property {number} [apogee]
 * @property {number} [perigee]
 * @property {string} [rcsSize]
 * @property {number} [rcsValue]
 * @property {string} [launchDate]
 * @property {string} [catalogComment]
 * @property {number} [weight]
 */

/**
 * 供卫星选择器加载的轻量目录字段。
 * @typedef {Object} SatelliteOption
 * @property {number} id
 * @property {string} name
 * @property {string} modelType
 * @property {string} [type]
 * @property {number} [scid]
 * @property {number} [noradCatId]
 */

/**
 * 我方卫星固定使用的对空观测载荷长期配置。
 * @typedef {Object} SpacePayloadConfiguration
 * @property {boolean} configured
 * @property {'SPACE'} payloadType
 * @property {number} [minDistanceKm] 新接口的业务输入单位；后端内部和兼容字段仍保留米。
 * @property {number} [maxDistanceKm]
 * @property {number} [minDistanceM]
 * @property {number} [maxDistanceM]
 * @property {number} [azimuthMinDeg]
 * @property {number} [azimuthMaxDeg]
 * @property {number} [elevationMinDeg]
 * @property {number} [elevationMaxDeg]
 * @property {number} [angleRateDegPerSec]
 * @property {number} [sunAvoidHalfAngleDeg]
 * @property {number | null} [startupAngleRateDegPerSec]
 */

/**
 * 保存对空观测载荷时需要填写的参数。
 * @typedef {Object} SpacePayloadConfigurationRequest
 * @property {number} [minDistanceKm] 规划人员输入和界面展示统一使用公里，服务端在接口边界换算为米。
 * @property {number} [maxDistanceKm]
 * @property {number} [minDistanceM]
 * @property {number} [maxDistanceM]
 * @property {number} [azimuthMinDeg]
 * @property {number} [azimuthMaxDeg]
 * @property {number} [elevationMinDeg]
 * @property {number} [elevationMaxDeg]
 * @property {number} [angleRateDegPerSec]
 * @property {number} [sunAvoidHalfAngleDeg]
 * @property {number | null} [startupAngleRateDegPerSec]
 */

/** 当前规划系统支持的载荷类别；保存任一类别不会覆盖另一类的长期配置。
 * @typedef {'SPACE' | 'GROUND'} PayloadType
 */

/**
 * 对空或对地的一份独立长期配置。两类载荷的距离、姿态和角速度字段完全一致，
距离均按公里填写；旧锥角字段仅用于读取历史对地记录。
 * @typedef {Object} PayloadProfileConfiguration
 * 继承字段：StandardPayloadConfiguration。
 * @property {boolean} [legacyGroundProfile] true 时表示服务端读取到历史锥角记录，界面需提示用户按统一字段重新保存。
 * @property {number} [minDistanceM] 仅读取旧集成方的米字段；编辑时换算公里，标准保存不混发米/公里。
 * @property {number} [maxDistanceM]
 * @property {number} [sensorFullConeAngleDeg]
 * @property {number} [maxDetectRangeKm]
 * @property {number} [sunExclusionAngleDeg]
 */

/**
 * 卫星的所有持久化载荷及当前参与可见性计算的活动类型。
 * @typedef {Object} PayloadProfilesResponse
 * @property {PayloadType | null} activePayloadType
 * @property {PayloadProfileConfiguration[]} profiles
 */

/**
 * 通用分页响应结构。
 * @template T
 * @typedef {Object} PageResponse
 * @property {number} total
 * @property {number} pageNumber
 * @property {number} pageSize
 * @property {T[]} records
 */

/**
 * 持久化站点基础信息：字符串ID，经纬度/仰角为度，高度米；每次任务临时约束另行提交。
 * @typedef {Object} Station
 * @property {string} id
 * @property {string} name
 * @property {number} [longitude]
 * @property {number} [latitude]
 * @property {number} [height]
 * @property {number} [minElevation]
 * @property {number} [maxElevation]
 */

/**
 * 空间目标目录显示字段与旧API一致；空可选值保持缺失，不把目录摘要当作真实弹道根数。
 * @typedef {Object} Target
 * @property {number} id
 * @property {string} name
 * @property {string} modelType
 * @property {string} [belong]
 * @property {string} [type]
 * @property {number} [scid]
 * @property {string} [objectId]
 * @property {string} [objectName]
 * @property {number} [noradCatId]
 * @property {string} [country]
 * @property {number} [period]
 * @property {number} [catalogInclination]
 * @property {number} [apogee]
 * @property {number} [perigee]
 * @property {string} [rcsSize]
 * @property {number} [rcsValue]
 * @property {string} [launchDate]
 * @property {string} [catalogComment]
 * @property {number} [weight]
 */

/**
 * 服务提供的默认前后保护/允许断链秒数，表单可覆盖；轨道时效与历元筛选另有明确单位。
 * @typedef {Object} PlanningDefaults
 * @property {number} preGuardSec
 * @property {number} postGuardSec
 * @property {number} allowedGapSec
 * @property {number} [orbitMaxApproximationDays] 星历可围绕任务时间做近似传播的最大天数；缺失时代表连接的是旧版服务。
 * @property {string} [targetMinTleEpochCode] 目录历元下限的前端默认值，采用 YYDDD，例如 23340。
 */

/**
 * UTC窗口端点与持续秒数；ISO字符串由服务直接返回，界面显示转换不改变用于再次提交的时刻。
 * @typedef {Object} WindowResponse
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} durationSec
 */

/**
 * 每次下传任务的临时仰角范围（度）；缺失值由服务按站点/默认配置补齐，不改长期站点。
 * @typedef {Object} StationConstraintRequest
 * @property {string} stationId
 * @property {number} [minElevationDeg]
 * @property {number} [maxElevationDeg]
 */

/**
 * 表示用户在本次下传规划中明确选择的一条卫星—地面站关系。
 * @typedef {Object} SatelliteStationLinkRequest
 * @property {number} satelliteId
 * @property {string} stationId
 */

/**
 * 表示同一目标的单星或多星共同观测时长约束。
 * @typedef {Object} ObservationConstraintRequest
 * @property {number[]} satelliteIds
 * @property {number} minDurationSec
 */

/**
 * UTC Instant字符串、秒制扫描/保护/断链参数及显式站星互选关系；不由GROUND载荷替代下传规则。
 * @typedef {Object} DownlinkPlanRequest
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} coarseStepSec
 * @property {number} fineStepSec
 * @property {number[]} satelliteIds
 * @property {number} [preGuardSec]
 * @property {number} [postGuardSec]
 * @property {number} [allowedGapSec]
 * @property {'ANY' | 'SUNLIT' | 'SHADOW'} [satelliteIllumination]
 * @property {StationConstraintRequest[]} stations
 * @property {SatelliteStationLinkRequest[]} links
 */

/**
 * 原独立站星弧段与连续覆盖并集；已失败链路另列，不能把失败计作正常无窗。
 * @typedef {Object} DownlinkPlanResponse
 * @property {Array<{ satelliteId: number; stationId: string; windows: WindowResponse[]; }>} satelliteStationWindows
 * @property {Array<WindowResponse & { links: Array<{ satelliteId: number; stationId: string; }>; }>} coverageWindows 不存在时间断档的多链路覆盖并集。任务窗口选择应使用此列表，
satelliteStationWindows 仅用于追溯每条原始链路。
 * @property {Array<{ satelliteId: number; satelliteName: string; stationId: string; stationName: string; stage: 'SCAN'; reason: string; }>} [skippedLinks] 未能完成计算的站星链路；与“正常但无窗口”明确区分。
 * @property {number} [totalSkippedLinks]
 */

/**
 * 指定完整UTC时段是否被下传覆盖；返回实际plan用于核对，布尔值不表示观测可见。
 * @typedef {Object} WindowValidationResponse
 * @property {boolean} satisfied
 * @property {DownlinkPlanResponse} plan
 */

/**
 * VisibilityArc
 * @typedef {Object} VisibilityArc
 * 继承字段：WindowResponse。
 */

/**
 * 观测卫星或目标在一个状态稳定观测弧段内的精确光照信息。
 * @typedef {Object} IlluminationDetail
 * @property {'SUNLIT' | 'PENUMBRA' | 'UMBRA'} state
 * @property {string} stateName
 */

/**
 * 参与当前观测的单颗我方卫星的精确光照信息。
 * @typedef {Object} ObserverIllumination
 * 继承字段：IlluminationDetail。
 * @property {string} satelliteId
 * @property {string} satelliteName
 */

/**
 * 当前被观测目标的精确光照信息。
 * @typedef {Object} TargetIllumination
 * 继承字段：IlluminationDetail。
 * @property {string} targetId
 * @property {string} targetName
 */

/**
 * 组合观测弧段展开后展示的单颗卫星独立观测时段；单星弧段不返回该结构。
 * @typedef {Object} SatelliteSegment
 * 继承字段：IlluminationDetail。
 * @property {string} satelliteId
 * @property {string} satelliteName
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} durationSec
 * @property {SatelliteSegmentStatistics} [statistics] 该星覆盖本时段的观测统计（距离/方位双口径/俯仰/最大角速度）；旧数据为空。
 */

/**
 * 单星观测统计极值；方位同时给出镜像与 +180° 两种口径，俯仰为天底夹角口径。
 * @typedef {Object} SatelliteSegmentStatistics
 * @property {number} samples
 * @property {number | null} [minDistanceKm]
 * @property {number | null} [maxDistanceKm]
 * @property {number | null} [minAzimuthDeg]
 * @property {number | null} [maxAzimuthDeg]
 * @property {number | null} [azimuthRelativeMinusXMinDeg]
 * @property {number | null} [azimuthRelativeMinusXMaxDeg]
 * @property {number | null} [minElevationDeg]
 * @property {number | null} [maxElevationDeg]
 * @property {number | null} [elevationZenithMinDeg]
 * @property {number | null} [elevationZenithMaxDeg]
 * @property {number} maxAngleRateDegPerSec
 */

/**
 * “确认选择”的逐星提交明细。
 * @typedef {Object} SelectionSatellite
 * @property {string} satelliteId
 * @property {string} satelliteName
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} durationSec
 * @property {number | null} [distanceMinKm]
 * @property {number | null} [distanceMaxKm]
 * @property {number | null} [azimuthRelativeMinusXMinDeg]
 * @property {number | null} [azimuthRelativeMinusXMaxDeg]
 * @property {number | null} [elevationZenithMinDeg]
 * @property {number | null} [elevationZenithMaxDeg]
 * @property {number | null} [maxAngleRateDegPerSec]
 */

/**
 * “确认选择”的逐目标提交明细。
 * @typedef {Object} SelectionTarget
 * @property {string} targetId
 * @property {string} targetName
 * @property {string | null} [noradCatId]
 * @property {number | null} [weightKg]
 * @property {string | null} [firstOrbitLine]
 * @property {string | null} [secondOrbitLine]
 * @property {string} combination
 * @property {Array<{ startTime: string; endTime: string; durationSec: number; }>} mergedWindows
 * @property {number} totalDurationSec
 * @property {SelectionSatellite[]} satellites
 */

/**
 * 前端按勾选目标自动合并出的完整时段。
 * @typedef {Object} SelectionMergedTimeRange
 * @property {Array<{ startTime: string; endTime: string; durationSec: number; }>} segments
 * @property {number} totalDurationSec
 */

/**
 * 确认选择请求体。
 * @typedef {Object} SelectionConfirmRequest
 * @property {{ startTime: string; endTime: string; }} [planningWindow]
 * @property {SelectionMergedTimeRange} mergedTimeRange
 * @property {SelectionTarget[]} selections
 * @property {string} [remark]
 */

/**
 * 确认选择回执。
 * @typedef {Object} SelectionReceipt
 * @property {string} recordId
 * @property {boolean} received
 * @property {number} selectionCount
 * @property {number} mergedSegments
 * @property {string} message
 */

/**
 * 已通过组合时长门槛的父窗口内部，因光照状态变化形成的稳定片段。
片段只用于展开核查；主列表必须使用父窗口本身的时长，不能据此再次判断门槛。
 * @typedef {Object} IlluminationSegment
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} durationSec
 * @property {ObserverIllumination[]} observerIlluminations
 * @property {TargetIllumination} targetIllumination
 */

/**
 * 一次目标观测规划临时使用的观测星—目标光照组合。
 * @typedef {Object} IlluminationConstraintRequest
 * @property {'ANY' | 'TARGET_SUNLIT' | 'OBSERVER_SHADOW_TARGET_SUNLIT' | 'BOTH_SHADOW' | 'CUSTOM'} mode
 * @property {Array<'SUNLIT' | 'PENUMBRA' | 'UMBRA'>} observerStates
 * @property {Array<'SUNLIT' | 'PENUMBRA' | 'UMBRA'>} targetStates
 */

/**
 * 同步与异步空间观测共用请求；浏览器日期控件先toISOString，目标ID空数组按服务筛选全量。
 * @typedef {Object} VisibilityPlanRequest
 * @property {number[]} observerSatelliteIds
 * @property {number[]} targetIds 由“目标筛选”接口返回的完整命中 ID；规划端据此直接读取，不再二次筛选。
 * @property {string[]} [belongs] 旧版直调接口的可选筛选字段；工作台完成筛选后不再提交这些字段。
 * @property {number} [minWeight]
 * @property {number} [maxWeight]
 * @property {string} [minTleEpochCode] WinTool 的五位 YYDDD 目录历元下限。
 * @property {number} [maxApproximationDays] 超出该时长仍会尝试计算，但响应会返回星历时效风险说明。
 * @property {string} [excludeNameRegex]
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} coarseStepSec
 * @property {number} fineStepSec
 * @property {IlluminationConstraintRequest} illuminationConstraint
 * @property {ObservationConstraintRequest[]} observationConstraints
 */

/**
 * 原观测目标/组合窗口结果；ID与时长由服务产生，前端不重采样或把包络替代累计/共视时长。
 * @typedef {Object} VisibilityPlanResponse
 * @property {Array<{ targetId: string; targetName: string; observerSatelliteIds: string[]; observerSatelliteNames: string[]; observationType: 'SINGLE' | 'COOPERATIVE'; observationTypeName: string; conciseSummary?: string; targetNorad?: string | null; targetWeightKg?: number | null; firstOrbitLine?: string | null; secondOrbitLine?: string | null; arcs: Array<VisibilityArc & { observerIlluminations: ObserverIllumination[]; targetIllumination: TargetIllumination; satelliteSegments?: SatelliteSegment[]; illuminationSegments?: IlluminationSegment[]; }>; }>} results
 * @property {Array<{ observerSatelliteId: string; observerSatelliteName: string; targetId: string; targetName: string; stage: 'PRECHECK' | 'SCAN'; reason: string; }>} [skippedPairs] 同步响应最多展示 500 条；完整清单保存在异步任务结果记录中。
 * @property {number} [totalSkippedPairs]
 * @property {Array<{ targetId: string; targetName: string; reason: string; }>} [ephemerisWarnings] 星历超出建议时效、但未被阻断的目标风险说明。
 * @property {TargetFilterBreakdown} [targetFilterBreakdown] 任务时间筛选统计；任务开始后才发射的目标会在传播前排除，星历时效仅作风险提示。
 */

/**
 * 目录分页只影响展示；targetIds是全筛选集合，不能只用当前页目标代替实际全量规划。
 * @typedef {Object} TargetQueryResponse
 * @property {number} total
 * @property {number} pageNumber
 * @property {number} pageSize
 * @property {Target[]} targets
 * @property {number[]} targetIds 未分页的完整命中 ID 列表；用于让规划严格复用当前筛选结果。
 * @property {TargetFilterBreakdown} [filterBreakdown] 各硬筛选条件按国籍、质量、历元、发射时间、名称排除顺序叠加后的命中数；星历时效另作提示统计。
 */

/**
 * 用于说明目标筛选条件实际收敛效果的顺序命中统计。
 * @typedef {Object} TargetFilterBreakdown
 * @property {number} belongsHit
 * @property {number} weightHit
 * @property {number} epochHit
 * @property {number} [epochConstraintHit] 应用 YYDDD 历元约束后的目标数。
 * @property {number} [ephemerisWarningHit] 超出建议近似传播时长、仍会参与计算的目标数。
 * @property {number} excludeHit
 * @property {number} [maxApproximationDays] 服务端本次实际采用的星历近似时长；未按任务时间筛选的旧响应为 0 或缺失。
 */

/**
 * 后端根据目标表 BELONG_ 实际值生成的筛选字典项。
 * @typedef {Object} BelongOption
 * @property {string} code
 * @property {number} count
 * @property {string} description
 */

/** 异步可见性规划任务的运行状态。
 * @typedef {'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'} PlanningTaskStatus
 */

/**
 * 提交异步可见性规划任务后返回的任务标识。
 * @typedef {Object} VisibilityPlanningTaskCreateResponse
 * @property {string} taskId
 */

/**
 * 异步可见性规划任务的状态与进度信息。
 * @typedef {Object} VisibilityPlanningTaskResponse
 * @property {string} taskId
 * @property {PlanningTaskStatus} status
 * @property {number} totalPairs
 * @property {number} completedPairs
 * @property {number} [totalTargets] 进度按筛选后的目标数量展示：配对数 = 目标数 × 观测星数。
 * @property {number} [completedTargets]
 * @property {string | null} [errorMessage]
 * @property {string} [createdAt]
 * @property {string | null} [completedAt]
 */

/**
 * 异步可见性规划任务的持久化结果记录（列表项，不含请求与结果 JSON 大字段）。
 * @typedef {Object} VisibilityTaskResultSummary
 * @property {string} id 雪花主键的十进制文本。不能使用 number：主键可能超过 JavaScript 安全整数上限，
数值解析会改变末尾数字，使后续“查看明细”等请求命中错误的记录。
 * @property {string} taskId
 * @property {string} taskType
 * @property {PlanningTaskStatus} status
 * @property {number | null} [totalPairs]
 * @property {number | null} [completedPairs]
 * @property {number | null} [totalTargets]
 * @property {number | null} [completedTargets]
 * @property {string | null} [errorMessage]
 * @property {string | null} [remark]
 * @property {string} [createTime]
 * @property {string | null} [completeTime]
 */

/**
 * 异步可见性规划任务持久化结果记录的全字段明细，包含请求与结果 JSON 原文。
 * @typedef {Object} VisibilityTaskResultDetail
 * 继承字段：VisibilityTaskResultSummary。
 * @property {string | null} [requestJson]
 * @property {string | null} [resultJson]
 * @property {string | null} [skippedPairsJson] 任务成功时保存的未截断 skippedPairs JSON 原文。
 * @property {string | null} [selectionJson] “确认选择”提交的明细 JSON 原文；未做过确认的历史记录为 null。
 */

/**
 * 任务结果记录分页查询参数。
 * @typedef {Object} VisibilityTaskResultPageQuery
 * @property {number} [pageNumber]
 * @property {number} [pageSize]
 * @property {PlanningTaskStatus} [status]
 * @property {string} [keyword]
 */
