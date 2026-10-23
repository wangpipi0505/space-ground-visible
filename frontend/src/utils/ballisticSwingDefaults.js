/**
 * 原Main.addRow先以Java Formatter十进制半值远离零舍入，再由parseSatellites读成double。
 * 仅生成落表调用；Intl默认halfExpand避免toFixed对1.15/64.85的二进制半值偏差。
 * 固定英文数字且关闭千分位，返回真正进入请求的数值，不截断任意手工编辑。
 */
export function swingTableNumber(value, digits) {
  return Number(
    new Intl.NumberFormat('en-US', {
      useGrouping: false,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(value),
  )
}
/** 原Main.java:1407—1458的非生产根数示例；身份由页面独立分配，不能套用目录卫星。 */
export function swingConstellation(index) {
  const base = {
    semiMajorAxisM: 7530612,
    eccentricity: 0.00085,
    inclinationDeg: 64.8,
    raanDeg: 120,
    argumentOfPerigeeDeg: 270,
    meanAnomalyDeg: 0,
    epochUtc: '2026/07/01 10:00:00.000',
  }
  if (index === 1)
    return Array.from({ length: 4 }, (_, i) => ({
      ...base,
      name: `SSO-${i + 1}`,
      semiMajorAxisM: 7078000,
      eccentricity: 0.001,
      inclinationDeg: 98,
      raanDeg: i * 90,
      argumentOfPerigeeDeg: 0,
      meanAnomalyDeg: i * 90,
    }))
  if (index === 2)
    return Array.from({ length: 6 }, (_, i) => ({
      ...base,
      name: `MEO-${i + 1}`,
      semiMajorAxisM: 26560000,
      eccentricity: 0.005,
      inclinationDeg: 55,
      raanDeg: i * 60,
      argumentOfPerigeeDeg: 0,
      meanAnomalyDeg: i * 60,
    }))
  if (index === 3)
    return Array.from({ length: 3 }, (_, i) => ({
      ...base,
      name: `MOL-${i + 1}`,
      semiMajorAxisM: 26600000,
      eccentricity: 0.72,
      inclinationDeg: 63.4,
      raanDeg: i * 120,
      meanAnomalyDeg: i * 120,
    }))
  return [
    { ...base, name: 'SAT-A', meanAnomalyDeg: 30 },
    { ...base, name: 'SAT-B' },
  ]
}
/** 原Swing摊平28列；时间码/ID/0.1m位置/0.0001m/s速度/0.01m/s²加速度均是字符串。 */
export const swing102Columns = [
  'track_tgt_id',
  'tack3d_time_code',
  'tack3d_tgt_pos_x',
  'tack3d_tgt_pos_y',
  'tack3d_tgt_pos_z',
  'tack3d_tgt_vel_x',
  'tack3d_tgt_vel_y',
  'tack3d_tgt_vel_z',
  'tack3d_tgt_acc_x',
  'tack3d_tgt_acc_y',
  'tack3d_tgt_acc_z',
  'prop_tgt_type_8',
  'prop_tgt_shape_8',
  'prop_tgt_flight_phase_8',
  'prob_tgt_critical_incident',
  'prob_tgt_critical_incident_time_code',
  'launch_time_code',
  'launch_coord_frame',
  'launch_pos_x',
  'launch_pos_y',
  'launch_pos_z',
  'launch_pos_unit',
  'impact_time_code',
  'impact_coord_frame',
  'impact_pos_x',
  'impact_pos_y',
  'impact_pos_z',
  'impact_pos_unit',
]
/** 原FitPanel:225/904—906：index为当前行数+1；全部28列保持字符串，秒数不自行进位。 */
export function swingNew102Row(index) {
  const second = String(Math.max(0, index - 1)).padStart(2, '0')
  const row = [
    'TGT-NEW',
    `2026-07-01 07:57:${second}.000`,
    '',
    '',
    '',
    '',
    '',
    '',
    '0',
    '0',
    '0',
    '0x01',
    '0x02',
    '0x01',
    '0xFF',
    '',
    '',
    'J2000',
    '',
    '',
    '',
    '0.1m',
    '',
    'J2000',
    '',
    '',
    '',
    '0.1m',
  ]
  return Object.fromEntries(swing102Columns.map((key, i) => [key, row[i]]))
}
/** 按原服务coordinate()/target()的对象优先级定位一个单元格，保留未编辑的全部别名及未知字段。 */
export function swing102CellOwner(row, key) {
  const coordinate = key.startsWith('tack3d_')
  const target = key === 'track_tgt_id' || key.startsWith('prop_') || key.startsWith('prob_')
  const owner = coordinate
    ? row.coordinateInfo ?? row.coordinate ?? row.coords
    : target
    ? row.targetInfo ?? row.target
    : undefined
  return owner ?? row
}
/** 时间别名只有非空tack优先；编辑当前生效别名，不另造字段覆盖已有track编码。 */
export function swing102CellKey(owner, key) {
  return key === 'tack3d_time_code' && !String(owner[key] ?? '').trim() && owner.track3d_time_code !== undefined
    ? 'track3d_time_code'
    : key
}
/** 原TargetTrajectoryFitPanel:885—900的八行样例，不含客户端输出目录。 */
export function swingFitDefaults() {
  const package102 = [0, 1].flatMap((target) =>
    [0, 1, 2, 3].map((second) => {
      const row =
        target === 0
          ? [
              'TGT-001',
              `2026-07-01 07:57:0${second}.000`,
              String(70000000 + second * 10000),
              String(second * 1000),
              '0',
              '10000000',
              '1000000',
              '0',
              '0',
              '0',
              '0',
              '0x01',
              '0x02',
              '0x01',
              '0xFF',
              '',
              '2026-07-01 07:56:55.000',
              'J2000',
              '69950000',
              '-5000',
              '0',
              '0.1m',
              '2026-07-01 07:57:10.000',
              'J2000',
              '70100000',
              '10000',
              '0',
              '0.1m',
            ]
          : [
              'TGT-002',
              `2026-07-01 07:57:0${second}.000`,
              '68000000',
              String(100000 + second * 76000),
              String(50000 + second * 100),
              '0',
              '76000000',
              '100000',
              '0',
              '0',
              '0',
              '0x02',
              '0xFF',
              '0xFF',
              '0xFF',
              '',
              '2026-07-01 07:56:55.000',
              'J2000',
              '68000000',
              '-280000',
              '49500',
              '0.1m',
              '2026-07-01 07:57:10.000',
              'J2000',
              '68000000',
              '860000',
              '51000',
              '0.1m',
            ]
      return Object.fromEntries(swing102Columns.map((key, i) => [key, row[i]]))
    }),
  )
  return {
    taskName: 'defaultFitTask',
    taskStartTime: '2026-07-01 07:56:55.000',
    taskEndTime: '2026-07-01 07:57:10.000',
    interpolationStepSec: 0.5,
    package102,
  }
}
