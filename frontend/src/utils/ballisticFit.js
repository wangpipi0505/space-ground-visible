import { swing102Columns } from './ballisticSwingDefaults'
/** JSON对象边界，后续逐字段校验避免用无根据类型断言跳过真实输入检查。 */
function isObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
/** 已知102编码叶值沿原DTO字符串/null；未知兼容扩展保持安全JSON，不解码、不改别名优先级。 */
function isRecord(value) {
  if (!isObject(value)) return false
  return Object.entries(value).every(([key, cell]) => {
    if (['coordinateInfo', 'coordinate', 'coords', 'targetInfo', 'target'].includes(key)) {
      // 已知编码字段仍严格为string/null；Jackson忽略的未知兼容扩展可为任意安全JSON，不能在表格编辑时丢弃。
      return (
        cell === null ||
        (isObject(cell) &&
          Object.entries(cell).every(
            ([field, content]) =>
              (!swing102Columns.includes(field) && field !== 'track3d_time_code') ||
              content === null ||
              typeof content === 'string',
          ))
      )
    }
    return (!swing102Columns.includes(key) && key !== 'track3d_time_code') || cell === null || typeof cell === 'string'
  })
}
/**
 * 原完整请求JSON唯一输入；先扫描非字符串数字，阻止JSON.parse在17位时间码等处默默舍入。
 * 任务步长保留有限Double；其他102叶值须按原DTO字符串编码，全部别名/原顺序保留。
 * 唯一Web适配为显式省略outputDir；调用者必须显示omittedOutputDir提示与实际提交预览。
 */
export function parseFitInput(text) {
  const tokens = text.match(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g) || []
  for (const token of tokens) {
    if (token.startsWith('"')) continue
    const number = Number(token)
    if (!Number.isFinite(number) || (Number.isInteger(number) && !Number.isSafeInteger(number)))
      throw new Error('JSON数字超出浏览器安全范围，请将时间码、ID和观测值按原DTO加引号编码为字符串')
  }
  let value
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error('JSON格式错误，请检查引号、逗号与完整请求结构')
  }
  if (
    !isObject(value) ||
    !Array.isArray(value.package102) ||
    !value.package102.length ||
    !value.package102.every(isRecord)
  )
    throw new Error(
      '请输入完整FitRequest与非空package102数组；原102字段值须为字符串（嵌套别名可保留），真正观测有效性由原服务判断',
    )
  if (value.taskName !== undefined && value.taskName !== null && typeof value.taskName !== 'string')
    throw new Error('taskName须为字符串或null（原服务回退task）')
  for (const field of ['taskStartTime', 'taskEndTime']) {
    if (value[field] !== undefined && value[field] !== null && typeof value[field] !== 'string')
      throw new Error(`${field}须为原UTC字符串，不使用浏览器时间戳`)
  }
  if (
    value.interpolationStepSec !== undefined &&
    (typeof value.interpolationStepSec !== 'number' ||
      !Number.isFinite(value.interpolationStepSec) ||
      value.interpolationStepSec < 0.001)
  )
    throw new Error('interpolationStepSec须为有限数且至少0.001秒，省略沿原默认1秒')
  const omittedOutputDir = Object.hasOwn(value, 'outputDir')
  const { outputDir: omittedDirectory, ...preserved } = value
  void omittedDirectory
  const request = { ...preserved, package102: value.package102 }
  // 前面已对原DTO边界逐字段验证；对象展开保留未转换的包字符串与任务字段。
  return { request, omittedOutputDir }
}
/** 冻结原导出表头，任何未知CSV均不猜测ECI/ECEF或单位。 */
export const fitCsvHeader =
  'YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt,ECI_X,ECI_Y,ECI_Z,ECI_VX,ECI_VY,ECI_VZ,ECEF_Vx,ECEF_Vy,ECEF_Vz,isOriginal'.split(
    ',',
  )
/** 仅解析已知无引号数值CSV；不重采样/插值，不经Date改变UTC，不修改下载Blob。 */
export function parseFitCsv(text, mode = 'fit') {
  // 原重定时逐单元格保留未知文本，不能trim整个CSV抹掉末行末列空格；只移除导出末尾空行。
  const lines = (mode === 'fit' ? text.trim() : text).split(/\r?\n/)
  while (lines.at(-1) === '') lines.pop()
  const rawHeader = lines.shift() || ''
  const header = rawHeader.split(',').map((name) => (mode === 'retime' ? name.trim() : name))
  if (mode === 'fit' && rawHeader !== fitCsvHeader.join(','))
    throw new Error('未知CSV表头：无法按原21列ECI/ECEF单位展示轨迹')
  // 重定时原算法允许未知文本列、列重排和缺dt/isOriginal；仅图形必要UTC/ECI列要求明确数值。
  // ECEF不作客户端坐标转换，原文本和额外列全部保留，非法图形不能阻止服务原文件下载。
  const required = ['YYYY', 'MM', 'dd', 'HH', 'mm', 'ss.SSS', 'ECI_X', 'ECI_Y', 'ECI_Z', 'ECI_VX', 'ECI_VY', 'ECI_VZ']
  if (new Set(header).size !== header.length || required.some((name) => !header.includes(name)))
    throw new Error('未知或重复CSV命名列：无法安全展示原UTC/ECI轨迹')
  if (!lines.length) throw new Error('原CSV没有轨迹行')
  let originUtcMillis = 0
  return lines.map((line, index) => {
    const cells = line.split(',')
    const fields = Object.fromEntries(header.map((name, cell) => [name, cells[cell] || '']))
    const numeric = mode === 'fit' ? cells : required.map((name) => fields[name] || '')
    if (
      cells.length !== header.length ||
      numeric.some((cell) => !/^-?\d+(?:\.\d+)?$/.test(cell) || !Number.isFinite(Number(cell))) ||
      (mode === 'fit' && !['0', '1'].includes(fields.isOriginal || ''))
    )
      throw new Error(`原CSV第${index + 2}行格式或数值无效`)
    // Date.UTC只用于原分列UTC之间的差，明确UTC且不解析本地日期文本；显示和下载仍保持原字符串。
    // 拟合/模拟dt是相邻毫秒，重定时dt是累计毫秒且可缺省；横轴统一使用实际UTC差。
    const utcMillis =
      Date.UTC(Number(fields.YYYY), Number(fields.MM) - 1, Number(fields.dd), Number(fields.HH), Number(fields.mm)) +
      Number(fields['ss.SSS']) * 1000
    if (!Number.isFinite(utcMillis)) throw new Error(`原CSV第${index + 2}行UTC范围无效`)
    if (index === 0) originUtcMillis = utcMillis
    return {
      utc: `${fields.YYYY}-${fields.MM}-${fields.dd} ${fields.HH}:${fields.mm}:${fields['ss.SSS']}`,
      dtMs: fields.dt === undefined ? null : Number(fields.dt),
      relativeTimeSec: (utcMillis - originUtcMillis) / 1000,
      eciX: Number(fields.ECI_X),
      eciY: Number(fields.ECI_Y),
      eciZ: Number(fields.ECI_Z),
      original: fields.isOriginal === '1',
      cells,
      fields,
    }
  })
}
