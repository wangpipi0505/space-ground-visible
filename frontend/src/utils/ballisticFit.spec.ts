/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parseFitInput, parseFitCsv } from './ballisticFit'

/** 原输入和原黄金来自冻结回归数据；不使用当前算法输出生成测试期望。 */
describe('T23 原102输入与CSV', () => {
  const original = readFileSync('../test-data/reference/ballistic/target_trajectory_fit_package102_default.json', 'utf8')
  const golden0 = readFileSync('../test-data/baseline/ballistic/fit/trajectory-0.csv', 'utf8')
  const golden1 = readFileSync('../test-data/baseline/ballistic/fit/trajectory-1.csv', 'utf8')
  it('包字符串、UTC、全部别名保持同值，仅显式省略旧输出目录', () => {
    const parsed = parseFitInput(original)
    const source = JSON.parse(original)
    delete source.outputDir
    expect(parsed.request).toEqual(source)
    expect(parsed.omittedOutputDir).toBe(true)
    expect(parsed.request.package102).toHaveLength(8)
    expect(parsed.request.taskStartTime).toBe('2026-07-01 07:56:55.000')
    const aliases = '{"package102":[{"coordinateInfo":{"track3d_time_code":"20260701075700000"},"coordinate":{"tack3d_tgt_pos_x":"001"},"coords":{},"targetInfo":{"track_tgt_id":"0xFF"},"target":{}}]}'
    expect(parseFitInput(aliases).request).toEqual(JSON.parse(aliases))
  })
  it('一条记录允许进入原服务，不在浏览器擅自限制为三点', () => {
    expect(parseFitInput('{"package102":[{"track_tgt_id":"单点"}]}').request.package102).toHaveLength(1)
  })
  it('taskName:null按原DTO同值提交，原缺省任务名由后端回退', () => {
    const source = JSON.parse(original)
    source.taskName = null
    delete source.outputDir
    expect(parseFitInput(JSON.stringify(source)).request).toEqual(source)
  })
  it.each(['{', '{"package102":[]}', '{"package102":[{}],"interpolationStepSec":0.0009}', '{"package102":[{}],"interpolationStepSec":1e400}', '{"package102":[{"tack3d_time_code":20260701075700001}]}', '{"package102":[{"tack3d_tgt_pos_x":9007199254740993}]}'])('拒绝格式、空包、非法步长或危险数值：%s', (text) => {
    expect(() => parseFitInput(text)).toThrow()
  })
  it('完整解析1633/31行原21列CSV，原点各4个，ECI米/dt毫秒且不混入ECEF', () => {
    for (const [text, count] of [[golden0, 1633], [golden1, 31]] as const) {
      const points = parseFitCsv(text)
      expect(points).toHaveLength(count)
      expect(points.filter((point) => point.original)).toHaveLength(4)
      expect(points[0]?.utc).toMatch(/^2026-07-01 /)
      expect(points[1]?.dtMs).toBe(500)
      expect(points[2]?.dtMs).toBe(500)
      expect(points[2]?.relativeTimeSec).toBe(1)
      expect(points[0]?.eciX).toBe(Number(text.split('\n')[1]!.split(',')[11]))
    }
  })
  it.each(['longitude,latitude\n1,2', 'YYYY,MM', golden1.replace(',6800000.000,', ',NaN,')])('未知或非有限CSV拒绝猜测坐标', (text) => {
    expect(() => parseFitCsv(text)).toThrow()
  })
})
