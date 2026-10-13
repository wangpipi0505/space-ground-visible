import type { BallisticWindowRequest, SatelliteOrbitDraft } from '../api/ballisticWindowTypes'
import { validateStandardPayload } from './standardPayload'

/** 目录只有身份，不静默填入根数；用户必须编辑或显式采用已标注非生产的原基准。 */
export function emptySatelliteOrbit(): SatelliteOrbitDraft {
  return { semiMajorAxisM: null, eccentricity: null, inclinationDeg: null, raanDeg: null, argumentOfPerigeeDeg: null, meanAnomalyDeg: null, epochUtc: '' }
}
/** 原回归基准，由按钮明确采用；绝非该目录卫星真实根数。 */
export function applyReferenceOrbit(): SatelliteOrbitDraft {
  return { semiMajorAxisM: 7530612, eccentricity: 0.00085, inclinationDeg: 64.8, raanDeg: 120, argumentOfPerigeeDeg: 270, meanAnomalyDeg: 30, epochUtc: '2026/07/01 10:00:00.000' }
}
/** 原服务支持 HTTP/HTTPS/MinIO；仅导入远程地址，不把浏览器本机路径交给服务器。 */
export function isRemoteBallisticSource(value: string): boolean {
  return /^(https?:\/\/[^\s]+|minio:\/\/[^\s]+)$/i.test(value.trim())
}
/** 字面UTC校验：只用UTC构造核对日历，不转换/格式化输入，不受Asia/Shanghai影响。 */
export function isValidLiteralUtc(value: string): boolean {
  if (!value.trim()) return true
  const match = /^(\d{4})[/-](\d{2})[/-](\d{2}) (\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?$/.exec(value.trim())
  if (!match) return false
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number)
  const date = new Date(Date.UTC(year!, month! - 1, day!, hour!, minute!, second!))
  return date.getUTCFullYear() === year && date.getUTCMonth() + 1 === month && date.getUTCDate() === day && date.getUTCHours() === hour && date.getUTCMinutes() === minute && date.getUTCSeconds() === second
}

/** 提交边界沿T20：有限根数与近地点、原正步长/正次数、long安全整数、标准载荷定位。 */
export function validateWindowRequest(value: BallisticWindowRequest, uploadedServerPaths: ReadonlySet<string> = new Set()): string[] {
  const errors: string[] = []
  for (const [label, number] of [['偏移起点', value.startOffsetSec], ['偏移步长', value.stepSizeSec], ['计算次数', value.numCalculations]] as const) {
    if (!Number.isSafeInteger(number)) errors.push(`${label}必须为浏览器可精确表示的安全整数`)
  }
  if (value.stepSizeSec <= 0) errors.push('偏移步长必须大于0秒')
  if (value.numCalculations <= 0) errors.push('计算次数必须大于0')
  if ([value.startOffsetSec, value.stepSizeSec, value.numCalculations].every(Number.isSafeInteger)) {
    const last = BigInt(value.startOffsetSec) + BigInt(value.stepSizeSec) * (BigInt(value.numCalculations) - 1n)
    if (last > BigInt(Number.MAX_SAFE_INTEGER) || last < BigInt(Number.MIN_SAFE_INTEGER)) errors.push('末次偏移超过浏览器安全整数范围')
  }
  if (!Number.isFinite(value.targetSampleStepSec)) errors.push('目标采样步长必须为有限数（秒，<=0自动）')
  if (value.trajectoryPointOutput !== 0 && value.trajectoryPointOutput !== 1) errors.push('轨迹点输出必须为0或1')
  if (!['TWO_BODY', 'J2_SECULAR', 'J2_NUMERICAL'].includes(value.orbitModel)) errors.push('轨道模型无效')
  if (!value.ballistics.length) errors.push('请至少添加一个弹道目标')
  for (const target of value.ballistics) {
    if (!target.csvPath.trim()) errors.push(`目标${target.targetName}：请成功上传文件或导入网络地址`)
    else if (!isRemoteBallisticSource(target.csvPath) && !uploadedServerPaths.has(target.csvPath) && /^(?:[A-Za-z]:|\\|file:)/i.test(target.csvPath)) errors.push(`目标${target.targetName}：不能使用浏览器本机路径，请上传或导入网络地址`)
    if (!isValidLiteralUtc(target.baseLaunchUTC || '')) errors.push(`目标${target.targetName}：基准发射UTC格式或日期无效`)
  }
  if (!value.satellites.length) errors.push('请至少配置一颗卫星')
  for (const satellite of value.satellites) {
    const numeric = ['semiMajorAxisM', 'eccentricity', 'inclinationDeg', 'raanDeg', 'argumentOfPerigeeDeg', 'meanAnomalyDeg'] as const
    for (const field of numeric) if (satellite[field] === null || !Number.isFinite(satellite[field])) errors.push(`卫星${satellite.name}：${field}须明确填写有限根数`)
    const a = satellite.semiMajorAxisM
    const e = satellite.eccentricity
    if (a !== null && a <= 0) errors.push(`卫星${satellite.name}：半长轴必须大于0米`)
    if (e !== null && (e < 0 || e >= 1)) errors.push(`卫星${satellite.name}：偏心率必须满足0≤e<1`)
    if (a !== null && e !== null && a * (1 - e) <= 6371008.8) errors.push(`卫星${satellite.name}：近地点半径不能落入地球内部`)
    if (!isValidLiteralUtc(satellite.epochUtc)) errors.push(`卫星${satellite.name}：历元UTC格式或日期无效`)
    if (!satellite.payloads.length) errors.push(`卫星${satellite.name}：至少配置一个标准载荷，禁止旧载荷回退`)
    for (const payload of satellite.payloads) errors.push(...validateStandardPayload(payload).map((error) => `卫星${satellite.name}/载荷${payload.payloadName}：${error}`))
  }
  return errors
}
