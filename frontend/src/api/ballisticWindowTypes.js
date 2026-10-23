export {}

/**
 * 原 DTO 根数草稿：米/度，无目录根数时留空；epochUtc 为字面 UTC，空值沿原服务缺省历元。
 * @typedef {Object} SatelliteOrbitDraft
 * @property {number | null} semiMajorAxisM
 * @property {number | null} eccentricity
 * @property {number | null} inclinationDeg
 * @property {number | null} raanDeg
 * @property {number | null} argumentOfPerigeeDeg
 * @property {number | null} meanAnomalyDeg
 * @property {string} epochUtc
 */

/**
 * T21 单一卫星草稿，直接供 T22 提交，不复制另一份载荷配置。
 * @typedef {Object} BallisticSatelliteDraft
 * 继承字段：SatelliteOrbitDraft。
 * @property {string} satelliteId
 * @property {string} name
 * @property {BallisticPayloadDefinition[]} payloads
 */

/**
 * csvPath 仅来自成功上传引用或受支持远程地址；目标身份在增删后不复用。
 * @typedef {Object} BallisticTargetInput
 * @property {string} targetId
 * @property {string} targetName
 * @property {string} csvPath
 * @property {string} [baseLaunchUTC]
 */

/**
 * 原 VisibilityRequest 白名单；秒、long 安全整数、原模型，无浏览器计算或重采样。
 * @typedef {Object} BallisticWindowRequest
 * @property {BallisticTargetInput[]} ballistics
 * @property {BallisticSatelliteDraft[]} satellites
 * @property {number} startOffsetSec
 * @property {number} stepSizeSec
 * @property {number} numCalculations
 * @property {number} targetSampleStepSec
 * @property {0 | 1} trajectoryPointOutput
 * @property {'TWO_BODY' | 'J2_SECULAR' | 'J2_NUMERICAL'} orbitModel
 */

/**
 * 原窗口摘要与原格式器字段，不从四锥角摘要逆推出标准矩形载荷。
 * @typedef {Object} VisibilityConstraintSummary
 * @property {string} payloadType
 * @property {number} maxDetectRangeKm
 * @property {number} sunExclusionAngleDeg
 * @property {number | null} simpleConeHalfAngleDeg
 * @property {number | null} halfConeAngleDeg
 * @property {number | null} innerConeAngleDeg
 * @property {number | null} outerConeAngleDeg
 */

/**
 * BallisticTimeWindow
 * @typedef {Object} BallisticTimeWindow
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} startJ2000Sec
 * @property {number} endJ2000Sec
 * @property {number} durationSeconds
 */

/**
 * 窗口内真实轨迹点；经纬度度、高度米、两类秒及字面 UTC，不能用原文件点冒充。
 * @typedef {Object} BallisticTrajectoryPoint
 * @property {number} longitudeDeg
 * @property {number} latitudeDeg
 * @property {number} heightM
 * @property {number} absoluteTimeJ2000Sec
 * @property {string} absoluteTimeUtc
 * @property {number} relativeTimeSec
 * @property {string} sourceTrajectoryUtc
 */

/**
 * BallisticVisibleArc
 * @typedef {Object} BallisticVisibleArc
 * 继承字段：BallisticTimeWindow。
 * @property {number} arcIndex
 * @property {string} payloadId
 * @property {string} payloadName
 * @property {string} payloadType
 * @property {number} pointCount
 * @property {BallisticTrajectoryPoint[]} trajectoryPoints
 */

/**
 * BallisticTargetArcs
 * @typedef {Object} BallisticTargetArcs
 * @property {string} targetId
 * @property {string} targetName
 * @property {string} payloadId
 * @property {string} payloadName
 * @property {string} payloadType
 * @property {BallisticVisibleArc[]} visibleArcs
 */

/**
 * BallisticPayloadResult
 * @typedef {Object} BallisticPayloadResult
 * @property {string} payloadId
 * @property {string} payloadName
 * @property {string} payloadType
 * @property {VisibilityConstraintSummary} visibilityConstraint
 * @property {BallisticTimeWindow | null} finalVisibleWindow
 * @property {BallisticTargetArcs[]} targets
 */

/**
 * BallisticSatelliteResult
 * @typedef {Object} BallisticSatelliteResult
 * @property {string} satelliteId
 * @property {string} satelliteName
 * @property {number} satelliteIndex
 * @property {BallisticTimeWindow | null} finalVisibleWindow
 * @property {BallisticPayloadResult[]} payloadResults
 */

/**
 * BallisticWindowSummary
 * @typedef {Object} BallisticWindowSummary
 * 继承字段：BallisticTimeWindow。
 * @property {string} satelliteId
 * @property {string} satelliteName
 * @property {number} satelliteIndex
 * @property {number} arcIndex
 * @property {string} payloadId
 * @property {string} payloadName
 * @property {string} payloadType
 * @property {number} pointCount
 */

/**
 * BallisticTargetResult
 * @typedef {Object} BallisticTargetResult
 * @property {string} targetId
 * @property {string} targetName
 * @property {string} csvPath
 * @property {string} baseLaunchUTC
 * @property {string} launchUTC
 * @property {string} payloadType
 * @property {number} launchTimeJ2000Sec
 * @property {BallisticTimeWindow | null} finalVisibleWindow
 * @property {BallisticWindowSummary[]} visibleWindows
 */

/**
 * 服务逐星逐载荷累计值直接显示，不对时间或点数去重重算。
 * @typedef {Object} BallisticOffsetResult
 * @property {number} offsetSecond
 * @property {string} launchTimeUtc
 * @property {number} launchTimeJ2000Sec
 * @property {string} payloadType
 * @property {VisibilityConstraintSummary} visibilityConstraint
 * @property {number} visibleArcCount
 * @property {number} visiblePointCount
 * @property {number} visibleDurationSec
 * @property {BallisticTimeWindow | null} finalVisibleWindow
 * @property {BallisticTargetResult[]} targets
 * @property {BallisticSatelliteResult[]} visibleSatellites
 */

/**
 * BallisticWindowResponse
 * @typedef {Object} BallisticWindowResponse
 * @property {string} referenceUtc
 * @property {string} payloadType
 * @property {VisibilityConstraintSummary} visibilityConstraint
 * @property {BallisticOffsetResult | null} best
 * @property {BallisticOffsetResult[]} allResults
 */
