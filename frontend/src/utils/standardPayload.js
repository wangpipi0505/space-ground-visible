/** 两业务共用典型默认值；对地窗口不是旧天底圆锥的等价映射。 */
export function createDefaultPayload(payloadType = 'SPACE') {
  return {
    payloadType,
    minDistanceKm: 1000,
    maxDistanceKm: 4000,
    azimuthMinDeg: payloadType === 'SPACE' ? 90 : null,
    azimuthMaxDeg: payloadType === 'SPACE' ? 270 : null,
    elevationMinDeg: payloadType === 'SPACE' ? -4 : -91,
    elevationMaxDeg: payloadType === 'SPACE' ? 60 : 0,
    angleRateDegPerSec: payloadType === 'SPACE' ? 0.3 : 1,
    sunAvoidHalfAngleDeg: payloadType === 'SPACE' ? 90 : 45,
    startupAngleRateDegPerSec: payloadType === 'SPACE' ? 0.07 : null,
  }
}
/** 白名单提取十字段，移除身份、历史米/锥角/只读标志；不归一化 0/-1 sentinel。 */
export function standardPayloadFields(value) {
  return {
    payloadType: value.payloadType,
    minDistanceKm: value.minDistanceKm ?? null,
    maxDistanceKm: value.maxDistanceKm ?? null,
    azimuthMinDeg: value.azimuthMinDeg ?? null,
    azimuthMaxDeg: value.azimuthMaxDeg ?? null,
    elevationMinDeg: value.elevationMinDeg ?? null,
    elevationMaxDeg: value.elevationMaxDeg ?? null,
    angleRateDegPerSec: value.angleRateDegPerSec ?? null,
    sunAvoidHalfAngleDeg: value.sunAvoidHalfAngleDeg ?? null,
    startupAngleRateDegPerSec: value.startupAngleRateDegPerSec ?? null,
  }
}
/** 按 T20 接口规则返回中文问题列表，两业务提交和共用编辑器均调用此函数。 */
export function validateStandardPayload(value) {
  const errors = []
  const fields = standardPayloadFields(value)
  const finite = (number) => typeof number === 'number' && Number.isFinite(number)
  if (fields.payloadType !== 'SPACE' && fields.payloadType !== 'GROUND') errors.push('载荷类型必须为对空或对地')
  for (const [label, number] of [
    ['距离下限', fields.minDistanceKm],
    ['距离上限', fields.maxDistanceKm],
  ]) {
    if (!finite(number) || number < 0 || number > Number.MAX_VALUE / 1000)
      errors.push(`${label}必须为可换算为米的非负有限数（公里）`)
  }
  if (
    finite(fields.minDistanceKm) &&
    finite(fields.maxDistanceKm) &&
    fields.maxDistanceKm !== 0 &&
    fields.maxDistanceKm <= fields.minDistanceKm
  ) {
    errors.push('距离上限为 0 时不限，否则必须大于距离下限')
  }
  // 先判断成对空值，再判断有限与窗口范围；不能依赖数字控件的 min/max 或 JSON 归一化。
  const checkWindow = (label, min, max, lower, upper) => {
    if (min === null && max === null) return
    if (min === null || max === null) {
      errors.push(`${label}上下限必须同时填写或同时留空`)
      return
    }
    if (!finite(min) || !finite(max) || min < lower || max > upper || min >= max)
      errors.push(`${label}必须为有限数且满足 ${lower} ≤ 下限 < 上限 ≤ ${upper}（度）`)
  }
  checkWindow('方位角', fields.azimuthMinDeg, fields.azimuthMaxDeg, 0, 360)
  checkWindow('俯仰角', fields.elevationMinDeg, fields.elevationMaxDeg, fields.payloadType === 'GROUND' ? -91 : -90, 90)
  if (
    !finite(fields.angleRateDegPerSec) ||
    (fields.angleRateDegPerSec < 0 && !(fields.payloadType === 'SPACE' && fields.angleRateDegPerSec === -1))
  ) {
    errors.push(
      fields.payloadType === 'SPACE'
        ? '对空跟踪角速度必须为非负有限数或 -1；0/-1 表示不限'
        : '对地跟踪角速度必须为非负有限数；0 为真实零上限，不能使用 -1',
    )
  }
  if (!finite(fields.sunAvoidHalfAngleDeg) || fields.sunAvoidHalfAngleDeg < 0 || fields.sunAvoidHalfAngleDeg > 180)
    errors.push('太阳回避半角必须为 0..180 的有限数（度）')
  if (
    fields.startupAngleRateDegPerSec !== null &&
    (!finite(fields.startupAngleRateDegPerSec) ||
      (fields.startupAngleRateDegPerSec < 0 && fields.startupAngleRateDegPerSec !== -1))
  )
    errors.push('启动角速度必须留空、-1 或非负有限数（度/秒）')
  return errors
}

/**
 * 十个标准业务字段；历史 profile 响应可缺失字段，空控件显式使用 null。身份由业务包装保留。
 * @typedef {Object} StandardPayloadConfiguration
 * @property {PayloadType} payloadType
 * @property {number | null} [minDistanceKm] 公里；下限非负，上限 0 不限，其他上限必须大于下限。
 * @property {number | null} [maxDistanceKm]
 * @property {number | null} [azimuthMinDeg] LVLH 方位度；双 null 不限，0 ≤ 下限 < 上限 ≤ 360。
 * @property {number | null} [azimuthMaxDeg]
 * @property {number | null} [elevationMinDeg] LVLH 俯仰度；正向背地球，SPACE 下限 -90、GROUND 下限 -91，双 null 不限。
 * @property {number | null} [elevationMaxDeg]
 * @property {number | null} [angleRateDegPerSec] 度/秒；SPACE 0/-1 不限，GROUND 0 是真实零上限且不允许 -1。
 * @property {number | null} [sunAvoidHalfAngleDeg] 度；0..180，0 关闭太阳回避。
 * @property {number | null} [startupAngleRateDegPerSec] 度/秒；null/-1 禁用，其余非负有限；当前仅元数据，不参与计算。
 */

/** 提交结构必须明确包含十字段；缺失值变为 null 以触发必填或成对校验，不推测为零。
 * @typedef {Required<StandardPayloadConfiguration>} StandardPayloadRequest
 */
