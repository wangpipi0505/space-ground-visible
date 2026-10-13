import type { BallisticFitRequest, FitCsvPoint, Package102Record } from '../api/ballisticFitTypes'

/** JSON对象边界，后续逐字段校验避免用无根据类型断言跳过真实输入检查。 */
function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 102叶值原DTO均为字符串；允许null与原嵌套别名，不解码、不选优先字段、不抹未知原文本。 */
function isRecord(value: unknown): value is Package102Record {
  if (!isObject(value)) return false
  return Object.entries(value).every(([key, cell]) => {
    if (['coordinateInfo', 'coordinate', 'coords', 'targetInfo', 'target'].includes(key)) {
      return cell === null || isObject(cell) && Object.values(cell).every((field) => field === null || typeof field === 'string')
    }
    return cell === null || typeof cell === 'string'
  })
}

/**
 * 原完整请求JSON唯一输入；先扫描非字符串数字，阻止JSON.parse在17位时间码等处默默舍入。
 * 任务步长保留有限Double；其他102叶值须按原DTO字符串编码，全部别名/原顺序保留。
 * 唯一Web适配为显式省略outputDir；调用者必须显示omittedOutputDir提示与实际提交预览。
 */
export function parseFitInput(text: string): { request: BallisticFitRequest; omittedOutputDir: boolean } {
  const tokens = text.match(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g) || []
  for (const token of tokens) {
    if (token.startsWith('"')) continue
    const number = Number(token)
    if (!Number.isFinite(number) || Number.isInteger(number) && !Number.isSafeInteger(number)) throw new Error('JSON数字超出浏览器安全范围，请将时间码、ID和观测值按原DTO加引号编码为字符串')
  }
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error('JSON格式错误，请检查引号、逗号与完整请求结构') }
  if (!isObject(value) || !Array.isArray(value.package102) || !value.package102.length || !value.package102.every(isRecord)) throw new Error('请输入完整FitRequest与非空package102数组；原102字段值须为字符串（嵌套别名可保留），真正观测有效性由原服务判断')
  if (value.taskName !== undefined && value.taskName !== null && typeof value.taskName !== 'string') throw new Error('taskName须为字符串或null（原服务回退task）')
  for (const field of ['taskStartTime', 'taskEndTime']) {
    if (value[field] !== undefined && value[field] !== null && typeof value[field] !== 'string') throw new Error(`${field}须为原UTC字符串，不使用浏览器时间戳`)
  }
  if (value.interpolationStepSec !== undefined && (typeof value.interpolationStepSec !== 'number' || !Number.isFinite(value.interpolationStepSec) || value.interpolationStepSec < .001)) throw new Error('interpolationStepSec须为有限数且至少0.001秒，省略沿原默认1秒')
  const omittedOutputDir = Object.hasOwn(value, 'outputDir')
  const { outputDir: omittedDirectory, ...preserved } = value
  void omittedDirectory
  const request = { ...preserved, package102: value.package102 }
  // 前面已对原DTO边界逐字段验证；对象展开保留未转换的包字符串与任务字段。
  return { request, omittedOutputDir }
}

/** 冻结原导出表头，任何未知CSV均不猜测ECI/ECEF或单位。 */
export const fitCsvHeader = 'YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt,ECI_X,ECI_Y,ECI_Z,ECI_VX,ECI_VY,ECI_VZ,ECEF_Vx,ECEF_Vy,ECEF_Vz,isOriginal'.split(',')

/** 仅解析已知无引号数值CSV；不重采样/插值，不经Date改变UTC，不修改下载Blob。 */
export function parseFitCsv(text: string): FitCsvPoint[] {
  const lines = text.trim().split(/\r?\n/)
  if (lines.shift() !== fitCsvHeader.join(',')) throw new Error('未知CSV表头：无法按原21列ECI/ECEF单位展示轨迹')
  if (!lines.length) throw new Error('原CSV没有轨迹行')
  let originUtcMillis = 0
  return lines.map((line, index) => {
    const cells = line.split(',')
    if (cells.length !== 21 || cells.some((cell) => !/^-?\d+(?:\.\d+)?$/.test(cell) || !Number.isFinite(Number(cell))) || !['0', '1'].includes(cells[20] || '')) throw new Error(`原CSV第${index + 2}行格式或数值无效`)
    // Date.UTC只用于原分列UTC之间的差，明确UTC且不解析本地日期文本；显示和下载仍保持原字符串。
    // dt列是相邻导出步长，不是输出起点累计时间，不能直接作为时间曲线横轴。
    const utcMillis = Date.UTC(Number(cells[0]), Number(cells[1]) - 1, Number(cells[2]), Number(cells[3]), Number(cells[4])) + Number(cells[5]) * 1000
    if (!Number.isFinite(utcMillis)) throw new Error(`原CSV第${index + 2}行UTC范围无效`)
    if (index === 0) originUtcMillis = utcMillis
    return { utc: `${cells[0]}-${cells[1]}-${cells[2]} ${cells[3]}:${cells[4]}:${cells[5]}`, dtMs: Number(cells[10]), relativeTimeSec: (utcMillis - originUtcMillis) / 1000, eciX: Number(cells[11]), eciY: Number(cells[12]), eciZ: Number(cells[13]), original: cells[20] === '1', cells }
  })
}
