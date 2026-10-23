/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '../test/vueTestUtils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticSimulationView from './BallisticSimulationView.vue'
import { setElementInput } from '../test/elementTestHelpers'
import '../test/installElementUI'
/** 页面真实原生输入→typed HTTP→共享文件流→引用事件；服务仍决定UTC/端点物理有效性。 */
describe('T24 模拟页面与实际结果引用', () => {
  /** A07：未计算时结果位置可发现，主动作与高级输入分离，初始挂载不发送请求。 */
  it('A07 初始结果区说明下一步且主动作可直接使用', () => {
    const network = vi.fn()
    vi.stubGlobal('fetch', network)
    const wrapper = mount(BallisticSimulationView)
    const panel = wrapper.get('[aria-label="模拟结果"]')
    expect(panel.find('.ballistic-empty-state strong').exists()).toBe(false)
    expect(panel.text()).toContain('完成左侧')
    expect(wrapper.get('[data-test=simulation-compute]').text()).toBe('开始模拟')
    expect(wrapper.findAll('details[open]')).toHaveLength(0)
    expect(network).not.toHaveBeenCalled()
  })
  afterEach(() => vi.unstubAllGlobals())
  const original = readFileSync(
    resolve(__dirname, '../../../test-data/reference/ballistic/target_trajectory_simulate_default.json'),
    'utf8',
  )
  const csv = readFileSync(
    resolve(__dirname, '../../../test-data/baseline/ballistic/simulate/trajectory-0.csv'),
    'utf8',
  )
  it('原JSON导入、核心编辑保留高级字段、原结果全文、201引用、完整四曲线、快照与失败恢复', async () => {
    let failure = false,
      registerFailure = true
    const fetchMock = vi.fn().mockImplementation((url) => {
      if (url.endsWith('/simulate'))
        return Promise.resolve(
          failure
            ? new Response('{"error":"IllegalArgumentException: 原UTC无效"}', { status: 400 })
            : new Response(
                '{"taskName":"defaultSimTask","targetCount":1,"files":[{"targetId":"SIM-001","model":"SYSTEM_SIMULATED_LAMBERT","csvPath":"G:\\\\运行\\\\原.csv","pointCount":2576,"warnings":["第一条","第二条"]}]}',
              ),
        )
      if (url.endsWith('/register-result'))
        return Promise.resolve(
          registerFailure
            ? new Response('{"error":"登记失败"}', { status: 500 })
            : new Response(
                '{"fileId":"真实f1","originalName":"原.csv","serverPath":"G:\\\\运行\\\\原.csv","sizeBytes":561736}',
                { status: 201 },
              ),
        )
      if (url.includes('/preview'))
        return Promise.resolve(
          new Response('{"header":["原头"],"rows":[["原值"]],"page":1,"limit":50,"totalRows":2576,"metadata":{}}'),
        )
      return Promise.resolve(new Response(csv))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticSimulationView)
    const input = wrapper.get('input[type=file]')
    const full = { ...JSON.parse(original), initialSpeedMs: 1800, phaseEvents: [{ deltaVms: 6, phaseType: '预留' }] }
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [{ name: '原.json', text: async () => JSON.stringify(full) }],
    })
    await input.trigger('change')
    await flushPromises()
    await setElementInput(wrapper, '[data-simulation-field=targetId]', 'SIM-001')
    expect(JSON.parse(String(wrapper.get('textarea').element.value))).toEqual(full)
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    await flushPromises()
    const expected = { ...full }
    delete expected.outputDir
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual(expected)
    expect(wrapper.text()).toContain('登记失败')
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toEqual([])
    registerFailure = false
    await wrapper.get('[data-test=fit-register]').trigger('click')
    await flushPromises()
    const references = wrapper.emitted('references')?.at(-1)?.[0]
    expect(references[0]?.targetId).toBe('SIM-001')
    expect(references[0]?.reference.fileId).toBe('真实f1')
    expect(wrapper.get('[data-test=fit-trajectory]').text()).toContain('2576 点')
    expect(wrapper.findAll('svg[role=img]')).toHaveLength(4)
    expect(wrapper.findAll('ol li').map((node) => node.text())).toEqual(['第一条', '第二条'])
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/simulate'))).toHaveLength(1)
    await setElementInput(wrapper, '[data-simulation-field=launchTimeUtc]', '非法UTC')
    expect(wrapper.text()).toContain('输入已修改')
    expect(wrapper.get('[data-test=simulation-submitted]').text()).toContain('2026-06-04 04:00:00.000')
    failure = true
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('IllegalArgumentException: 原UTC无效')
    expect(wrapper.find('[data-test=simulation-success]').exists()).toBe(false)
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toEqual([])
  })
  it('本地非法JSON不发送HTTP，忙时防重复', async () => {
    let finish
    const fetchMock = vi.fn(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticSimulationView)
    await wrapper.get('textarea').setValue('{')
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    await wrapper.get('textarea').setValue(original)
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    finish?.(new Response('{"error":"原失败"}', { status: 400 }))
    await flushPromises()
    expect(wrapper.text()).toContain('原失败')
  })
  it('旧计算的慢登记返回不能污染新计算同索引结果候选', async () => {
    let finishOld
    let computations = 0
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url, options) => {
        if (url.endsWith('/simulate')) {
          computations += 1
          return Promise.resolve(
            new Response(
              JSON.stringify({
                taskName: 'task',
                targetCount: 1,
                files: [
                  {
                    targetId: `SIM-${computations}`,
                    model: 'SYSTEM_SIMULATED_LAMBERT',
                    csvPath: `server/${computations}.csv`,
                    pointCount: 2576,
                    warnings: [],
                  },
                ],
              }),
            ),
          )
        }
        if (url.endsWith('/register-result') && JSON.parse(String(options?.body)).targetId === 'SIM-1')
          return new Promise((resolve) => {
            finishOld = resolve
          })
        if (url.endsWith('/register-result'))
          return Promise.resolve(new Response('{"error":"新登记失败"}', { status: 500 }))
        return Promise.resolve(new Response('{"error":"旧文件辅助失败"}', { status: 500 }))
      }),
    )
    const wrapper = mount(BallisticSimulationView)
    await wrapper.get('textarea').setValue(original)
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    await flushPromises()
    finishOld?.(new Response('{"fileId":"旧引用","serverPath":"server/1.csv"}', { status: 201 }))
    await flushPromises()
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toEqual([])
  })
  it('核心数值框非法/舍入文本不能默默沿用旧合法JSON发起计算', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"error":"不应发送"}', { status: 400 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticSimulationView)
    await wrapper.get('textarea').setValue(original)
    await setElementInput(wrapper, '[data-simulation-field=launchPointX]', '9007199254740993')
    await wrapper.get('[data-test=simulation-compute]').trigger('click')
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('launchPointX')
  })
})
