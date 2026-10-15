import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import BallisticWindowResults from './BallisticWindowResults.vue'
import type { BallisticOffsetResult, BallisticWindowResponse } from '../api/ballisticWindowTypes'
import { setElementSelect } from '../test/elementPlusTestHelpers'
import '../test/installElementPlus'

/** 固定人工HTTP结构夹具仅验证呈现，不当作数值黄金；真实数值另由Chrome接原Java验证。 */
describe('T22 原结果层级与真实点展示', () => {
  const constraint = { payloadType: 'SPACE', maxDetectRangeKm: 0, sunExclusionAngleDeg: 0, simpleConeHalfAngleDeg: null, halfConeAngleDeg: null, innerConeAngleDeg: null, outerConeAngleDeg: null }
  const window = { startTime: '2026/07/01 00:00:00.000', endTime: '2026/07/01 00:00:02.000', startJ2000Sec: 1, endJ2000Sec: 3, durationSeconds: 2 }
  const points = [179, -179].map((longitudeDeg, index) => ({ longitudeDeg, latitudeDeg: 1, heightM: 12000, absoluteTimeJ2000Sec: index + 1, absoluteTimeUtc: window.startTime, relativeTimeSec: index, sourceTrajectoryUtc: window.startTime }))
  function offset(n: number, arcs = true): BallisticOffsetResult {
    return { offsetSecond: n, launchTimeUtc: window.startTime, launchTimeJ2000Sec: 1, payloadType: 'SPACE', visibilityConstraint: constraint, visibleArcCount: arcs ? 2 : 0, visiblePointCount: arcs ? 4 : 0, visibleDurationSec: arcs ? 4 : 0, finalVisibleWindow: arcs ? window : null, targets: [], visibleSatellites: arcs ? [{ satelliteId: 'S1', satelliteName: '星甲', satelliteIndex: 0, finalVisibleWindow: window, payloadResults: [{ payloadId: 'P1', payloadName: '独立载荷', payloadType: 'SPACE', visibilityConstraint: constraint, finalVisibleWindow: window, targets: [{ targetId: 'T1', targetName: '目标甲', payloadId: 'P1', payloadName: '独立载荷', payloadType: 'SPACE', visibleArcs: [1, 2].map((arcIndex) => ({ ...window, arcIndex, payloadId: 'P1', payloadName: '独立载荷', payloadType: 'SPACE', pointCount: 2, trajectoryPoints: points })) }] }] }] : [] }
  }
  it('全部偏移可选、两弧层级及点表保持服务累计，跨180度不画贯穿假线', async () => {
    const allResults = [offset(10), offset(20, false), offset(30)]
    const result: BallisticWindowResponse = { referenceUtc: 'UTC', payloadType: 'SPACE', visibilityConstraint: constraint, best: allResults[2]!, allResults }
    const wrapper = mount(BallisticWindowResults, { props: { result, pointOutput: 1 } })
    await flushPromises()
    expect(wrapper.findAllComponents({ name: 'ElOption' })).toHaveLength(3)
    expect(wrapper.text()).toContain('星甲')
    expect(wrapper.text()).toContain('独立载荷')
    expect(wrapper.text()).toContain('目标甲')
    expect(wrapper.findAll('[data-test=show-trajectory]')).toHaveLength(2)
    await wrapper.findAll('[data-test=show-trajectory]')[0]!.trigger('click')
    expect(wrapper.text()).toContain('12000')
    expect(wrapper.get('[data-test=longitude-path]').attributes('d')?.match(/M/g)).toHaveLength(2)
    expect(wrapper.text()).toContain('4 秒')
    await setElementSelect(wrapper, '[data-test=offset-select]', 1)
    expect(wrapper.text()).toContain('该偏移没有可见卫星或弧段')
    expect(wrapper.find('[data-test=trajectory-view]').exists()).toBe(false)
    await wrapper.get('[data-test=edit-payloads]').trigger('click')
    expect(wrapper.emitted('edit')?.at(-1)).toEqual(['payloads'])
    await wrapper.get('[data-test=edit-search]').trigger('click')
    expect(wrapper.emitted('edit')?.at(-1)).toEqual(['search'])
  })
  it('point0和全空如实说明，不生成人工轨迹', async () => {
    const value = offset(10)
    value.visibleSatellites[0]!.payloadResults[0]!.targets[0]!.visibleArcs.forEach((arc) => { arc.trajectoryPoints = [] })
    const wrapper = mount(BallisticWindowResults, { props: { result: { referenceUtc: 'UTC', payloadType: 'SPACE', visibilityConstraint: constraint, best: value, allResults: [value] }, pointOutput: 0 } })
    await flushPromises()
    await wrapper.findAll('[data-test=show-trajectory]')[0]!.trigger('click')
    expect(wrapper.text()).toContain('本次轨迹点输出为0')
    expect(wrapper.find('svg[role=img]').exists()).toBe(false)
    await wrapper.setProps({ result: { referenceUtc: 'UTC', payloadType: 'SPACE', visibilityConstraint: constraint, best: null, allResults: [] } })
    expect(wrapper.text()).toContain('服务未返回候选偏移')
  })
})
