import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticPayloadConfigurationView from './BallisticPayloadConfigurationView.vue'
import BallisticFitView from './BallisticFitView.vue'
import BallisticSimulationView from './BallisticSimulationView.vue'
import BallisticRetimeView from './BallisticRetimeView.vue'
import BallisticWindowControls from '../components/BallisticWindowControls.vue'

/** A06 通过原生input事件验证同一最终请求，特别覆盖未失焦的最后一格与102原字符串。 */
describe('A06 Swing输入恢复', () => {
  afterEach(() => vi.unstubAllGlobals())
  function transport() {
    const fetcher = vi.fn().mockImplementation((url: string) => Promise.resolve(new Response(JSON.stringify(url.includes('/search') ? { best: null, allResults: [] } : { taskName: 'default', targetCount: 0, files: [] }))))
    vi.stubGlobal('fetch', fetcher)
    return fetcher
  }
  it('双星初始可编辑，末格input直接进入361扫描请求且目标UTC优先于默认', async () => {
    const fetcher = transport()
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites: [] }, global: { plugins: [ElementPlus] } })
    expect(wrapper.findAll('[data-satellite-id]')).toHaveLength(2)
    const input = wrapper.findAll('[data-orbit-field=meanAnomalyDeg]')[0]!
    await input.setValue('42')
    const controls = wrapper.findComponent(BallisticWindowControls)
    await controls.get('[data-test=default-base-utc]').setValue('2026/07/01 08:24:00.000')
    await controls.get('[data-test=target-base-utc]').setValue('2026/07/01 08:25:00.000')
    await controls.get('[data-test=window-source]').setValue('remote')
    await controls.get('input[placeholder="https://host/path/trajectory.csv"]').setValue('https://host/path/trajectory.csv')
    await controls.findAll('button').find(b => b.text() === '导入网络地址')!.trigger('click')
    await controls.get('[data-test=compute-window]').trigger('click'); await flushPromises()
    const body = JSON.parse(String(fetcher.mock.calls.find(([url]) => url.endsWith('/search'))![1].body))
    expect(body.satellites[0].meanAnomalyDeg).toBe(42)
    expect(body.numCalculations).toBe(361)
    expect(body.ballistics[0].baseLaunchUTC).toBe('2026/07/01 08:25:00.000')
    await controls.get('[data-test=target-base-utc]').setValue('')
    await controls.get('[data-test=compute-window]').trigger('click'); await flushPromises()
    expect(JSON.parse(String(fetcher.mock.calls.at(-1)![1].body)).ballistics[0].baseLaunchUTC).toBe('2026/07/01 08:24:00.000')
  })
  it('四示例星座及种子生成遵循原平面相位，替换不复用身份', async () => {
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites: [] }, global: { plugins: [ElementPlus] } })
    const old = wrapper.findAll('[data-payload-id]').map(p => p.attributes('data-payload-id'))
    for (const [example,count] of [['1',4],['2',6],['3',3],['0',2]] as const) {
      await wrapper.get('[data-test=constellation-example]').setValue(example)
      await wrapper.get('[data-test=load-constellation]').trigger('click')
      expect(wrapper.findAll('[data-satellite-id]')).toHaveLength(count)
    }
    expect(wrapper.findAll('[data-payload-id]').map(p => p.attributes('data-payload-id')).every(p => !old.includes(p))).toBe(true)
    await wrapper.get('[data-generator-field=planeCount]').setValue('2')
    await wrapper.get('[data-generator-field=satellitesPerPlane]').setValue('2')
    await wrapper.get('[data-test=generate-constellation]').trigger('click')
    const rows = wrapper.findAll('[data-satellite-id]')
    expect(rows).toHaveLength(4)
    expect(rows[2]!.get('[data-orbit-field=raanDeg]').element).toHaveProperty('value','300')
    expect(rows[2]!.get('[data-orbit-field=meanAnomalyDeg]').element).toHaveProperty('value','120')
  })
  it('102默认表可直接提交；导入别名/未知字段后编辑末格仍保留编码和同一草稿', async () => {
    const fetcher = transport(), wrapper = mount(BallisticFitView)
    expect(wrapper.findAll('[data-fit-row]')).toHaveLength(8)
    const imported = { taskName: '兼容', extra: 'keep', package102: [{ coordinateInfo: { tack3d_time_code: '20260701075700000', tack3d_tgt_pos_x: '70000000' }, coords: { tack3d_time_code: '备用' }, target: { track_tgt_id: '90071992547409931' }, unknown: '保留' }] }
    await wrapper.get('[data-test=fit-input]').setValue(JSON.stringify(imported))
    await wrapper.get('[data-fit-cell="0/tack3d_tgt_pos_x"]').setValue('70000001')
    await wrapper.get('[data-test=fit-compute]').trigger('click'); await flushPromises()
    const sent = JSON.parse(String(fetcher.mock.calls[0]![1].body))
    expect(sent).toEqual({ ...imported, package102: [{ ...imported.package102[0], coordinateInfo: { ...imported.package102[0]!.coordinateInfo, tack3d_tgt_pos_x: '70000001' } }] })
    await wrapper.get('[data-test=load-default-fit]').trigger('click')
    await wrapper.get('[data-test=fit-compute]').trigger('click'); await flushPromises()
    expect(JSON.parse(String(fetcher.mock.calls.at(-1)![1].body))).toMatchObject({ taskName: 'defaultFitTask', interpolationStepSec: .5 })
  })
  it('模拟恢复Swing默认且最后input未失焦直接提交，兼容字段保留', async () => {
    const fetcher = transport(), wrapper = mount(BallisticSimulationView)
    await wrapper.get('[data-simulation-field=impactPointZ]').setValue('3919735.592')
    await wrapper.get('[data-test=simulation-compute]').trigger('click'); await flushPromises()
    expect(JSON.parse(String(fetcher.mock.calls[0]![1].body))).toMatchObject({ taskName: 'defaultSimTask', targetId: 'SIM-001', launchTimeUtc: '2026-06-04 04:00:00.000', impactTimeUtc: '2026-06-04 04:42:55.000', impactPointZ: 3919735.592 })
  })
  it('重定时默认沿Swing而非HTTP缺省', () => {
    const wrapper = mount(BallisticRetimeView)
    expect(wrapper.get('[data-retime-field=targetId]').element).toHaveProperty('value','RET-001')
    expect(wrapper.get('[data-retime-field=launchTimeUtc]').element).toHaveProperty('value','2026-07-01 09:00:00.000')
  })
  it('102修改已知叶值保留未知JSON兼容字段且非法步长能在原表单纠正', async () => {
    const fetcher = transport(), wrapper = mount(BallisticFitView)
    const input = { taskName: '兼容', package102: [{ track_tgt_id: 'TGT-1', tack3d_time_code: '20260701075700000', future: { enabled: true, precision: '90071992547409931' }, coords: { tack3d_tgt_pos_x: '70000000', extension: { version: 2 } } }] }
    await wrapper.get('[data-test=fit-input]').setValue(JSON.stringify(input))
    await wrapper.get('[data-fit-cell="0/tack3d_tgt_pos_x"]').setValue('70000001')
    await wrapper.get('[data-fit-field=interpolationStepSec]').setValue('0')
    await wrapper.get('[data-fit-field=interpolationStepSec]').setValue('.5')
    await wrapper.get('[data-test=fit-compute]').trigger('click'); await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    const sent = JSON.parse(String(fetcher.mock.calls[0]![1].body))
    expect(sent.package102[0].future).toEqual(input.package102[0]!.future)
    expect(sent.package102[0].coords.extension).toEqual({ version: 2 })
    expect(sent.package102[0].coords.tack3d_tgt_pos_x).toBe('70000001')
    expect(sent.interpolationStepSec).toBe(.5)
  })
  it('受控未完成窗口HTTP冻结六根数与目标扫描，跨页忙态由原父级busy维持', async () => {
    let finish!: (value: Response) => void
    const fetcher = vi.fn((url: string) => url.endsWith('/search') ? new Promise<Response>(resolve => { finish = resolve }) : Promise.resolve(new Response('{"header":[],"rows":[],"metadata":{},"page":1,"limit":50,"totalRows":0}')))
    vi.stubGlobal('fetch', fetcher)
    const reference = { fileId: '真实测试引用', serverPath: 'server/trajectory.csv', originalName: '测试.csv', sizeBytes: 10, contentType: 'text/csv' }
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites: [], generatedResults: [{ targetId: 'TGT-1', targetName: '目标1', reference }] }, global: { plugins: [ElementPlus] } })
    await wrapper.get('[data-test=window-source]').setValue('generated')
    await wrapper.get('[data-test=generated-result-select]').setValue(reference.fileId); await flushPromises()
    await wrapper.get('[data-test=compute-window]').trigger('click')
    expect(wrapper.findAll('fieldset.ballistic-native-form').every(field => field.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.get('[data-test=add-manual-satellite]').attributes('disabled')).toBeDefined()
    expect(fetcher.mock.calls.filter(([url]) => url.endsWith('/search'))).toHaveLength(1)
    finish(new Response('{"best":null,"allResults":[]}')); await flushPromises()
    expect(wrapper.get('[data-test=add-manual-satellite]').attributes('disabled')).toBeUndefined()
  })
})
