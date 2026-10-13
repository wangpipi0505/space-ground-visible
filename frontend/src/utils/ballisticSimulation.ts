import type { BallisticSimulationRequest } from '../api/ballisticSimulation'

const stringFields = ['taskName', 'targetId', 'taskStartTime', 'taskEndTime', 'launchTimeUtc', 'impactTimeUtc', 'impactTime', 'launchPointCoordFrame', 'launchPointUnit', 'impactPointCoordFrame', 'impactPointUnit', 'targetTypeCode', 'targetShapeCode', 'flightPhaseCode']
const numberFields = ['launchPointX', 'launchPointY', 'launchPointZ', 'impactPointX', 'impactPointY', 'impactPointZ', 'outputPointDurationSec', 'launchLatDeg', 'launchLonDeg', 'launchHeightM', 'initialSpeedMs', 'azimuthDeg', 'elevationDeg', 'durationSec', 'integrationStepSec', 'outputStepSec', 'boostDurationSec', 'boostAccelerationMs2', 'dragCdAreaOverMass']
const eventStrings = ['phaseType', 'eventType', 'startTime', 'endTime', 'eventTime', 'dynamics', 'directionMode']
const eventNumbers = ['accelerationMs2', 'deltaVms', 'liftDragRatio', 'dragCdAreaOverMass']
function isObject(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value) }
/** 比较十进制有效数字与指数，不误拒1e20等可表达double，检测parse/serialize造成的静默舍入或下溢。 */
function decimalIdentity(token: string): string {
  const [mantissa = '', exponent = '0'] = token.toLowerCase().split('e')
  const fractionLength = mantissa.split('.')[1]?.length || 0
  const negative = mantissa.startsWith('-')
  const digits = mantissa.replace(/[-.]/g, '').replace(/^0+/, '')
  if (!digits) return '0'
  const significant = digits.replace(/0+$/, '')
  return `${negative ? '-' : ''}${significant}e${BigInt(exponent) - BigInt(fractionLength) + BigInt(digits.length - significant.length)}`
}
/** 原nullable类型边界；不复制后端UTC/坐标/优先级/firstPositive物理验证，保留原错误先后。 */
function validateFields(value: Record<string, unknown>, strings: string[], numbers: string[]): void {
  for (const field of strings) if (value[field] !== undefined && value[field] !== null && typeof value[field] !== 'string') throw new Error(`${field}须为原字符串或null`)
  for (const field of numbers) if (value[field] !== undefined && value[field] !== null && (typeof value[field] !== 'number' || !Number.isFinite(value[field]))) throw new Error(`${field}须为有限数或null`)
}
/** 完整原JSON为唯一草稿：逐字段保留别名/null/事件，只省略outputDir；危险数字在JSON.parse前拦截。 */
export function parseSimulationInput(text: string): { request: BallisticSimulationRequest; omittedOutputDir: boolean } {
  for (const token of text.match(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g) || []) {
    if (token.startsWith('"')) continue
    const number = Number(token)
    if (!Number.isFinite(number) || decimalIdentity(token) !== decimalIdentity(String(number))) throw new Error('JSON数字非有限或会发生精度舍入，请修改为可精确往返的原double值')
  }
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error('JSON格式错误，请检查完整SimulationRequest结构') }
  if (!isObject(value)) throw new Error('请输入原SimulationRequest JSON对象')
  validateFields(value, stringFields, numberFields)
  if (value.phaseEvents !== undefined && value.phaseEvents !== null) {
    if (!Array.isArray(value.phaseEvents) || !value.phaseEvents.every(isObject)) throw new Error('phaseEvents须为原事件对象数组或null')
    for (const event of value.phaseEvents) validateFields(event, eventStrings, eventNumbers)
  }
  const omittedOutputDir = Object.hasOwn(value, 'outputDir')
  const { outputDir, ...request } = value
  void outputDir
  // 已核对全部34个Web字段与11个事件字段类型；未知原JSON字段原值保留，由原Jackson处理。
  return { request, omittedOutputDir }
}
