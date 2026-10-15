/// <reference types="node" />
import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticFitView from './BallisticFitView.vue'
import BallisticFitFileResult from '../components/BallisticFitFileResult.vue'

/** 组件真实输入事件与HTTP封装；一次失败仅影响所属目标，重新计算清除旧结果。 */
describe('T23 拟合页面状态', () => {
  const original = readFileSync(resolve(__dirname, '../../../test-data/reference/ballistic/target_trajectory_fit_package102_default.json'), 'utf8')
  const csv = readFileSync(resolve(__dirname, '../../../test-data/baseline/ballistic/fit/trajectory-1.csv'), 'utf8')
  /** A07：未计算时结果位置可发现，主动作与高级输入分离，初始挂载不发送请求。 */
  it('A07 初始结果区说明下一步且主动作可直接使用', () => {
    const network = vi.fn()
    vi.stubGlobal('fetch', network)
    const wrapper = mount(BallisticFitView)
    const panel = wrapper.get('[aria-label="拟合结果"]')
    expect(panel.find('.ballistic-empty-state strong').exists()).toBe(false)
    expect(panel.text()).toContain('完成左侧')
    expect(wrapper.get('[data-test=fit-compute]').text()).toBe('开始拟合')
    expect(wrapper.findAll('details[open]')).toHaveLength(0)
    expect(network).not.toHaveBeenCalled()
  })
  afterEach(() => vi.unstubAllGlobals())
  function setup() {
    let failedCompute = false
    let failedRegister = true
    let failedPreview = false
    let failedDownload = false
    const requests = vi.fn().mockImplementation((url: string, options?: RequestInit) => {
      if (url.endsWith('/package102')) return Promise.resolve(failedCompute ? new Response('{"error":"原非法观测"}', { status: 400 }) : new Response(JSON.stringify({ taskName: 'task', targetCount: 2, files: ['甲', '乙'].map((targetId) => ({ targetId, model: 'ORBITAL_TWO_BODY', csvPath: `server/${targetId}.csv`, pointCount: 31, warnings: ['警告第一条', '警告第二条'] })) })))
      if (url.endsWith('/register-result')) {
        const body = JSON.parse(String(options?.body))
        return Promise.resolve(body.targetId === '甲' && failedRegister ? new Response('{"error":"甲登记暂时失败"}', { status: 500 }) : new Response(JSON.stringify({ fileId: body.targetId, originalName: `${body.targetId}.csv`, serverPath: body.csvPath }), { status: 201 }))
      }
      if (url.includes('/preview')) return Promise.resolve(failedPreview ? new Response('{"error":"预览暂时失败"}', { status: 500 }) : new Response(JSON.stringify({ fileId: '乙', header: ['原头'], rows: [['原值']], totalRows: 100, page: url.includes('page=2') ? 2 : 1, limit: 50, metadata: {} })))
      return Promise.resolve(failedDownload ? new Response('{"error":"下载暂时失败"}', { status: 500 }) : new Response(csv, { headers: { 'Content-Disposition': "attachment; filename*=UTF-8''%E5%8E%9F.csv" } }))
    })
    vi.stubGlobal('fetch', requests)
    return { requests, failCompute: () => { failedCompute = true }, restoreRegister: () => { failedRegister = false }, preview: (failed: boolean) => { failedPreview = failed }, download: (failed: boolean) => { failedDownload = failed } }
  }
  /** A07审查修复：长告警默认收起，数量可发现；原文、顺序及独立下载/完整轨迹入口保留。 */
  it('A07 计算告警默认折叠且可展开全部原文，下载和轨迹位于折叠区之外', async () => {
    setup()
    const warnings = Array.from({ length: 8 }, (_, index) => `原告警第${index + 1}条：保留服务返回的完整说明与标点。`)
    const wrapper = mount(BallisticFitFileResult, { props: { taskName: 'task', file: { targetId: '乙', model: 'ORBITAL_TWO_BODY', csvPath: 'server/乙.csv', pointCount: 31, warnings } } })
    await flushPromises()
    const warningList = wrapper.get('ol[aria-label="计算告警"]')
    const disclosure = warningList.element.closest('details')
    expect(disclosure).not.toBeNull()
    expect(disclosure!.open).toBe(false)
    expect(disclosure!.querySelector('summary')?.textContent).toBe('计算告警（8条）')
    expect(warningList.findAll('li').map((item) => item.text())).toEqual(warnings)
    disclosure!.open = true
    await wrapper.vm.$nextTick()
    expect(warningList.findAll('li').map((item) => item.text())).toEqual(warnings)
    expect(disclosure!.contains(wrapper.get('[data-test=fit-download]').element)).toBe(false)
    expect(disclosure!.contains(wrapper.get('[data-test=fit-trajectory]').element)).toBe(false)
  })
  it('A07 无计算告警时不显示告警折叠入口', async () => {
    setup()
    const wrapper = mount(BallisticFitFileResult, { props: { taskName: 'task', file: { targetId: '乙', model: 'ORBITAL_TWO_BODY', csvPath: 'server/乙.csv', pointCount: 31, warnings: [] } } })
    await flushPromises()
    expect(wrapper.find('ol[aria-label="计算告警"]').exists()).toBe(false)
    expect(wrapper.findAll('summary').some((summary) => summary.text().includes('计算告警'))).toBe(false)
  })
  it('A07 观测表以中文和单位呈现，协议列名可在提示查看且时间不截短', () => {
    const wrapper = mount(BallisticFitView)
    expect(wrapper.get('thead').text()).toContain('位置 X（0.1米）')
    expect(wrapper.get('th[title="tack3d_time_code"]').text()).toBe('观测时间码')
    expect(wrapper.findAll('thead th[title]')).toHaveLength(28)
    expect(wrapper.get('[data-fit-cell="0/tack3d_time_code"]').attributes('style')).toContain('min-width: 220px')
    expect(wrapper.get('.ballistic-workspace-header').text()).toBe('102包轨迹拟合')
  })
  it('原生JSON导入只省略outputDir，双目标登记隔离、重试不重算、全点曲线原点、分页与失败恢复', async () => {
    const state = setup()
    const wrapper = mount(BallisticFitView)
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [{ name: '原.json', text: async () => original }] })
    await input.trigger('change'); await flushPromises()
    expect(wrapper.text()).toContain('outputDir')
    await wrapper.get('[data-test=fit-compute]').trigger('click'); await flushPromises()
    const submitted = JSON.parse(String(state.requests.mock.calls.find(([url]) => String(url).endsWith('/package102'))?.[1]?.body))
    const expected = JSON.parse(original); delete expected.outputDir
    expect(submitted).toEqual(expected)
    expect(wrapper.get('[aria-label=拟合结果]').find('[data-test=fit-success]').exists()).toBe(true)
    expect(wrapper.get('[aria-label=拟合结果]').find('.ballistic-empty-state').exists()).toBe(false)
    const results = wrapper.findAllComponents(BallisticFitFileResult)
    expect(results).toHaveLength(2)
    expect(results[0]!.text()).toContain('甲登记暂时失败')
    expect(results[0]!.find('[data-test=fit-download]').exists()).toBe(false)
    expect(results[1]!.findAll('details[open]')).toHaveLength(0)
    expect(results[1]!.text()).toContain('警告第一条')
    expect(results[1]!.text()).toContain('警告第二条')
    expect(results[1]!.findAll('[data-test=fit-original-marker]')).toHaveLength(16)
    expect(results[1]!.get('[data-test=fit-trajectory]').text()).toContain('31 点')
    state.restoreRegister()
    await results[0]!.get('[data-test=fit-register]').trigger('click'); await flushPromises()
    expect(results[0]!.text()).not.toContain('甲登记暂时失败')
    expect(state.requests.mock.calls.filter(([url]) => String(url).endsWith('/package102'))).toHaveLength(1)
    state.preview(true)
    await results[1]!.get('[data-test=fit-preview-next]').trigger('click'); await flushPromises()
    expect(results[1]!.text()).toContain('预览暂时失败')
    expect(results[0]!.text()).not.toContain('预览暂时失败')
    state.preview(false)
    await results[1]!.get('[data-test=fit-preview-retry]').trigger('click'); await flushPromises()
    expect(results[1]!.text()).toContain('第 2 页')
    state.download(true)
    await results[1]!.get('[data-test=fit-download]').trigger('click'); await flushPromises()
    expect(results[1]!.text()).toContain('下载暂时失败')
    expect(results[1]!.find('[data-test=fit-trajectory]').exists()).toBe(true)
    await wrapper.get('textarea').setValue(original.replace('07:56:55.000', '07:56:56.000'))
    expect(wrapper.text()).toContain('输入已修改')
    expect(wrapper.get('[data-test=fit-submitted]').text()).toContain('07:56:55.000')
    state.failCompute()
    await wrapper.get('[data-test=fit-compute]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('原非法观测')
    expect(wrapper.find('[data-test=fit-success]').exists()).toBe(false)
  })
  it('非法JSON与危险数字无HTTP，计算忙时避免重复提交', async () => {
    const state = setup()
    const wrapper = mount(BallisticFitView)
    for (const bad of ['{', '{"package102":[{"tack3d_time_code":20260701075700001}]}']) {
      await wrapper.get('textarea').setValue(bad)
      await wrapper.get('[data-test=fit-compute]').trigger('click')
      expect(state.requests).not.toHaveBeenCalled()
    }
    let finish: ((value: Response) => void) | undefined
    state.requests.mockImplementationOnce(() => new Promise<Response>((resolve) => { finish = resolve }))
    await wrapper.get('textarea').setValue(original)
    await wrapper.get('[data-test=fit-compute]').trigger('click')
    expect(wrapper.get('[data-test=fit-compute]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[aria-label=拟合结果]').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[aria-label=拟合结果]').text()).toContain('正在拟合')
    await wrapper.get('[data-test=fit-compute]').trigger('click')
    expect(state.requests).toHaveBeenCalledTimes(1)
    finish?.(new Response('{"error":"原失败"}', { status: 400 }))
    await flushPromises()
    expect(wrapper.text()).toContain('原失败')
    expect(wrapper.get('[aria-label=拟合结果]').attributes('aria-busy')).toBe('false')
    expect(wrapper.get('[aria-label=拟合结果]').text()).toContain('完成左侧')
  })
  it('缺文件后原服务注销旧引用，恢复只重新登记并用新ID读文件', async () => {
    let expired = false
    let registrations = 0
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.endsWith('/register-result')) {
        registrations++
        return Promise.resolve(new Response(JSON.stringify({ fileId: `f${registrations}`, originalName: '原.csv' }), { status: 201 }))
      }
      if (expired && url.includes('/f1/')) return Promise.resolve(new Response('{"error":"文件引用不存在或已清理"}', { status: 404 }))
      if (url.includes('/preview')) return Promise.resolve(new Response(JSON.stringify({ header: ['原表头'], rows: [['原值']], totalRows: 100, page: 1, limit: 50, metadata: {} })))
      return Promise.resolve(new Response(csv))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticFitFileResult, { props: { taskName: 'task', file: { targetId: '甲', model: 'ORBITAL_TWO_BODY', csvPath: 'server/甲.csv', pointCount: 31, warnings: [] } } })
    await flushPromises()
    expired = true
    await wrapper.get('[data-test=fit-preview-next]').trigger('click'); await flushPromises()
    await wrapper.get('[data-test=fit-reregister]').trigger('click'); await flushPromises()
    expect(registrations).toBe(2)
    expect(wrapper.text()).toContain('引用 f2')
    expect(wrapper.text()).not.toContain('文件引用不存在或已清理')
    expect(wrapper.find('[data-test=fit-trajectory]').exists()).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/f2/preview'))).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/package102'))).toBe(false)
  })
})
