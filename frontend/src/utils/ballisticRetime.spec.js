/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { flushPromises, mount } from '../test/vueTestUtils'
import { describe, expect, it } from 'vitest'
import { fitCsvHeader, parseFitCsv } from './ballisticFit'
import BallisticFitTrajectory from '../components/BallisticFitTrajectory.vue'
import '../test/installElementUI'
/** 原黄金22列；变体只改输入排布/未知文本以验证显示，不作为任何算法数值基准。 */
describe('T25 重定时完整CSV命名列', () => {
  const golden = readFileSync('../test-data/baseline/ballistic/retime/trajectory-0.csv', 'utf8')
  it('22列2576行全部解析，横轴由UTC差，dt累计且interval相邻，拟合模式仍拒绝22列', () => {
    const points = parseFitCsv(golden, 'retime')
    expect(points).toHaveLength(2576)
    expect(points.filter((point) => point.original)).toHaveLength(0)
    expect(points[2]?.relativeTimeSec).toBe(2)
    expect(points[2]?.dtMs).toBe(2000)
    expect(points[2]?.fields.timeIntervalMs).toBe('1000.000')
    expect(() => parseFitCsv(golden)).toThrow('21列')
  })
  it('未知文本/列重排/缺dt/负标记保留，跨日非整秒UTC及原点表按列名显示', async () => {
    const header = [...fitCsvHeader.filter((name) => name !== 'dt').reverse(), 'note', 'timeIntervalMs']
    const rows = golden
      .trim()
      .split('\n')
      .slice(1, 4)
      .map((line, index) => {
        const fields = Object.fromEntries(
          [...fitCsvHeader, 'timeIntervalMs'].map((name, cell) => [name, line.split(',')[cell]]),
        )
        Object.assign(fields, {
          YYYY: '2026',
          MM: '07',
          dd: index ? '03' : '02',
          HH: index ? '00' : '23',
          mm: index ? '00' : '59',
          'ss.SSS': index === 0 ? '59.375' : index === 1 ? '00.625' : '02.250',
          isOriginal: ['1', '-1', '0'][index],
          note: `原文${index}`,
          timeIntervalMs: ['0.000', '1250.000', '1625.000'][index],
        })
        return header.map((name) => fields[name]).join(',')
      })
    const points = parseFitCsv([header.join(','), ...rows].join('\n'), 'retime')
    expect(points.map((point) => point.relativeTimeSec)).toEqual([0, 1.25, 2.875])
    expect(points.map((point) => point.original)).toEqual([true, false, false])
    expect(points[0]?.fields.note).toBe('原文0')
    expect(points[0]?.fields.dt).toBeUndefined()
    const wrapper = mount(BallisticFitTrajectory, { props: { points, identity: '实际重定时', retimed: true } })
    await flushPromises()
    expect(wrapper.findAll('[data-test=fit-original-marker]')).toHaveLength(4)
    expect(wrapper.get('.el-table__body-wrapper tbody tr').text()).toContain(points[0]?.fields.ECI_VX)
    expect(wrapper.get('.el-table__body-wrapper tbody tr').text()).not.toContain('原文0')
    expect(wrapper.text()).toContain('dt 为累计')
    const spacedCsv = [header.join(',') + ',tail', ...rows.map((row) => row + ', 保留原文本 ')].join('\n')
    expect(parseFitCsv(spacedCsv, 'retime').at(-1)?.fields.tail).toBe(' 保留原文本 ')
  })
  it('缺isOriginal不造原点，必要ECI缺失/重复列明确独立不能绘图', () => {
    const text = golden
      .replace(/,isOriginal/g, '')
      .replace(/,0,1000\.000/g, ',1000.000')
      .replace(/,0,0\.000/g, ',0.000')
    expect(parseFitCsv(text, 'retime').every((point) => !point.original)).toBe(true)
    expect(() => parseFitCsv(golden.replace('ECI_X,', 'unknown,'), 'retime')).toThrow()
    expect(() => parseFitCsv(golden.replace('energy,', 'ECI_X,'), 'retime')).toThrow()
  })
})
