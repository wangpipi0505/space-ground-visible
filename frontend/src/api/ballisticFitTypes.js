export {}

/**
 * 原102字符串字段保持编码单位；时间/ID/字节代码不转为浏览器数值。
 * @typedef {Object} FitCoordinate
 * @property {string | null} [tack3d_time_code]
 * @property {string | null} [track3d_time_code]
 * @property {string | null} [tack3d_tgt_pos_x]
 * @property {string | null} [tack3d_tgt_pos_y]
 * @property {string | null} [tack3d_tgt_pos_z]
 * @property {string | null} [tack3d_tgt_vel_x]
 * @property {string | null} [tack3d_tgt_vel_y]
 * @property {string | null} [tack3d_tgt_vel_z]
 * @property {string | null} [tack3d_tgt_acc_x]
 * @property {string | null} [tack3d_tgt_acc_y]
 * @property {string | null} [tack3d_tgt_acc_z]
 */

/**
 * 原目标属性影响模型和事件边界；别名优先级完全交给后端。
 * @typedef {Object} FitTarget
 * @property {string | null} [track_tgt_id]
 * @property {string | null} [prop_tgt_type_8]
 * @property {string | null} [prop_tgt_shape_8]
 * @property {string | null} [prop_tgt_flight_phase_8]
 * @property {string | null} [prob_tgt_critical_incident]
 * @property {string | null} [prob_tgt_critical_incident_time_code]
 */

/**
 * DTO同时兼容扁平和嵌套写法，不删除任何原别名或显式事件。
 * @typedef {Object} Package102Record
 * 继承字段：FitCoordinate、FitTarget。
 * @property {FitCoordinate | null} [coordinateInfo]
 * @property {FitCoordinate | null} [coordinate]
 * @property {FitCoordinate | null} [coords]
 * @property {FitTarget | null} [targetInfo]
 * @property {FitTarget | null} [target]
 * @property {string | null} [launch_time_code]
 * @property {string | null} [launch_coord_frame]
 * @property {string | null} [launch_pos_x]
 * @property {string | null} [launch_pos_y]
 * @property {string | null} [launch_pos_z]
 * @property {string | null} [launch_pos_unit]
 * @property {string | null} [impact_time_code]
 * @property {string | null} [impact_coord_frame]
 * @property {string | null} [impact_pos_x]
 * @property {string | null} [impact_pos_y]
 * @property {string | null} [impact_pos_z]
 * @property {string | null} [impact_pos_unit]
 */

/**
 * 原任务参数，时间为UTC字面文本，任务区间不等于物理起落；步长默认1秒且>=.001。Web省略outputDir。
 * @typedef {Object} BallisticFitRequest
 * @property {Package102Record[]} package102
 * @property {string | null} [taskName]
 * @property {string | null} [taskStartTime]
 * @property {string | null} [taskEndTime]
 * @property {number} [interpolationStepSec]
 */

/**
 * 原结果只提供csvPath；fileId来自后续辅助登记，不能混入原响应。
 * @typedef {Object} BallisticFitFile
 * @property {string} targetId
 * @property {string} model
 * @property {string} csvPath
 * @property {number} pointCount
 * @property {string[]} warnings
 */

/**
 * BallisticFitResult
 * @typedef {Object} BallisticFitResult
 * @property {string} taskName
 * @property {number} targetCount
 * @property {BallisticFitFile[]} files
 */

/**
 * CSV导出精度的实际点；dt拟合/模拟为相邻步长毫秒、重定时为累计毫秒且缺列时null，relativeTimeSec由分列UTC相对首行计算；original严格来自isOriginal。
 * @typedef {Object} FitCsvPoint
 * @property {string} utc
 * @property {number | null} dtMs
 * @property {number} relativeTimeSec
 * @property {number} eciX
 * @property {number} eciY
 * @property {number} eciZ
 * @property {boolean} original
 * @property {string[]} cells
 * @property {Record<string, string>} fields 命名列对应原单元格；支持重定时列重排/未知列/缺dt，不修改下载原字节。
 */
