/** 原102字符串字段保持编码单位；时间/ID/字节代码不转为浏览器数值。 */
export interface FitCoordinate {
  tack3d_time_code?: string | null
  track3d_time_code?: string | null
  tack3d_tgt_pos_x?: string | null
  tack3d_tgt_pos_y?: string | null
  tack3d_tgt_pos_z?: string | null
  tack3d_tgt_vel_x?: string | null
  tack3d_tgt_vel_y?: string | null
  tack3d_tgt_vel_z?: string | null
  tack3d_tgt_acc_x?: string | null
  tack3d_tgt_acc_y?: string | null
  tack3d_tgt_acc_z?: string | null
}
/** 原目标属性影响模型和事件边界；别名优先级完全交给后端。 */
export interface FitTarget {
  track_tgt_id?: string | null
  prop_tgt_type_8?: string | null
  prop_tgt_shape_8?: string | null
  prop_tgt_flight_phase_8?: string | null
  prob_tgt_critical_incident?: string | null
  prob_tgt_critical_incident_time_code?: string | null
}
/** DTO同时兼容扁平和嵌套写法，不删除任何原别名或显式事件。 */
export interface Package102Record extends FitCoordinate, FitTarget {
  coordinateInfo?: FitCoordinate | null
  coordinate?: FitCoordinate | null
  coords?: FitCoordinate | null
  targetInfo?: FitTarget | null
  target?: FitTarget | null
  launch_time_code?: string | null
  launch_coord_frame?: string | null
  launch_pos_x?: string | null
  launch_pos_y?: string | null
  launch_pos_z?: string | null
  launch_pos_unit?: string | null
  impact_time_code?: string | null
  impact_coord_frame?: string | null
  impact_pos_x?: string | null
  impact_pos_y?: string | null
  impact_pos_z?: string | null
  impact_pos_unit?: string | null
}
/** 原任务参数，时间为UTC字面文本，任务区间不等于物理起落；步长默认1秒且>=.001。Web省略outputDir。 */
export interface BallisticFitRequest {
  package102: Package102Record[]
  taskName?: string | null
  taskStartTime?: string | null
  taskEndTime?: string | null
  interpolationStepSec?: number
}
/** 原结果只提供csvPath；fileId来自后续辅助登记，不能混入原响应。 */
export interface BallisticFitFile {
  targetId: string
  model: string
  csvPath: string
  pointCount: number
  warnings: string[]
}
export interface BallisticFitResult {
  taskName: string
  targetCount: number
  files: BallisticFitFile[]
}
/** CSV导出精度的实际点；dt为相邻步长毫秒，relativeTimeSec由分列UTC相对首行计算；original严格来自isOriginal。 */
export interface FitCsvPoint {
  utc: string
  dtMs: number
  relativeTimeSec: number
  eciX: number
  eciY: number
  eciZ: number
  original: boolean
  cells: string[]
}
