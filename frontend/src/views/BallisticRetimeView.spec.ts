/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticRetimeView from './BallisticRetimeView.vue'

/** 完整页面输入与生命周期；原后端才验证UTC和CSV，组件测试HTTP mock仅隔离网络。 */
describe('T25 重定时页面真实引用', () => {
  /** A07：未计算时结果位置可发现，主动作与高级输入分离，初始挂载不发送请求。 */
  it('A07 初始结果区说明下一步且主动作可直接使用', () => {
    const network = vi.fn()
    vi.stubGlobal('fetch', network)
    const wrapper = mount(BallisticRetimeView)
    const panel = wrapper.get('[aria-label="重定时结果"]')
    expect(panel.find('.ballistic-empty-state strong').exists()).toBe(false)
    expect(panel.text()).toContain('完成左侧')
    expect(wrapper.get('[data-test=retime-compute]').text()).toBe('开始重定时')
    expect(wrapper.findAll('details[open]')).toHaveLength(0)
    expect(network).not.toHaveBeenCalled()
  })
  afterEach(() => vi.unstubAllGlobals())
  const csv = readFileSync(resolve(__dirname, '../../../test-data/baseline/ballistic/retime/trajectory-0.csv'), 'utf8')
  const reference = { fileId: '源1', serverPath: 'server/source.csv', originalName: '源.csv', sizeBytes: 561736, contentType: 'text/csv' }
  const candidate = { targetId: 'SIM-001', targetName: '模拟/default/SIM-001', reference }
  const response = { taskName: 'T04-retime', targetCount: 1, files: [{ targetId: 'RETIMED-TGT', model: 'RETIMED_TRAJECTORY', csvPath: 'server/output.csv', pointCount: 2576, warnings: [] }] }
  function mockNetwork() {
    return vi.fn().mockImplementation((url: string) => {
      if (url.endsWith('/retime')) return Promise.resolve(new Response(JSON.stringify(response)))
      if (url.endsWith('/register-result')) return Promise.resolve(new Response(JSON.stringify({ ...reference, fileId: '输出1', serverPath: 'server/output.csv' }), { status: 201 }))
      if (url.includes('/preview')) return Promise.resolve(new Response('{"header":["原头"],"rows":[["原文"]],"totalRows":2576,"page":1,"limit":50,"metadata":{}}'))
      if (url.endsWith('/files')) return Promise.resolve(new Response(JSON.stringify(reference), { status: 201 }))
      return Promise.resolve(new Response(csv))
    })
  }
  it('辅助404注销仅撤本次候选仍保留计算成功与错误，原文件恢复可重新201登记', async () => {
    let missing = false
    const network = mockNetwork()
    vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/download') && missing ? Promise.resolve(new Response('{"error":"原文件已注销"}', { status: 404 })) : network(url)))
    const wrapper = mount(BallisticRetimeView, { props: { generatedResults: [candidate] } })
    await wrapper.get('[data-test=retime-source-mode]').setValue('generated'); await wrapper.get('[data-test=retime-result-select]').setValue('源1'); await flushPromises()
    await wrapper.get('[data-test=retime-compute]').trigger('click'); await flushPromises()
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toHaveLength(1)
    missing = true; await wrapper.get('[data-test=fit-download]').trigger('click'); await flushPromises()
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toEqual([])
    expect(wrapper.find('[data-test=retime-success]').exists()).toBe(true); expect(wrapper.text()).toContain('原文件已注销')
    missing = false; await wrapper.get('[data-test=fit-reregister]').trigger('click'); await flushPromises()
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toHaveLength(1)
  })
  it('旧慢预览在来源切换或候选撤销后撤销busy，且旧finally不能清新来源busy', async () => {
    const finishes: Array<(response: Response) => void> = []
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => finishes.push(resolve))))
    const wrapper = mount(BallisticRetimeView, { props: { generatedResults: [candidate] } })
    await wrapper.get('[data-test=retime-source-mode]').setValue('generated')
    await wrapper.get('[data-test=retime-result-select]').setValue('源1')
    expect(wrapper.text()).toContain('正在读取来源预览')
    await wrapper.get('[data-test=retime-source-mode]').setValue('upload')
    expect(wrapper.text()).not.toContain('正在读取来源预览')
    await wrapper.get('[data-test=retime-source-mode]').setValue('generated')
    await wrapper.get('[data-test=retime-result-select]').setValue('源1')
    finishes[0]?.(new Response('{"header":["旧头"],"rows":[],"totalRows":0}')); await flushPromises()
    expect(wrapper.text()).toContain('正在读取来源预览'); expect(wrapper.text()).not.toContain('旧头')
    await wrapper.setProps({ generatedResults: [] })
    expect(wrapper.text()).not.toContain('正在读取来源预览')
    finishes[1]?.(new Response('{"header":["失效头"],"rows":[],"totalRows":0}')); await flushPromises()
    expect(wrapper.text()).not.toContain('失效头')
  })
  it('来源预览失败与计算独立，保留201引用可执行原计算且忙时防重复', async () => {
    let finish: ((response: Response) => void) | undefined
    const network = mockNetwork(), fetchMock = vi.fn((url: string) => {
      if (url.includes('/preview')) return Promise.resolve(new Response('{"error":"预览独立失败"}', { status: 400 }))
      if (url.endsWith('/retime')) return new Promise<Response>(resolve => { finish = resolve })
      return network(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticRetimeView, { props: { generatedResults: [candidate] } })
    await wrapper.get('[data-test=retime-source-mode]').setValue('generated'); await wrapper.get('[data-test=retime-result-select]').setValue('源1'); await flushPromises()
    expect(wrapper.text()).toContain('预览独立失败')
    await wrapper.get('[data-test=retime-compute]').trigger('click'); await wrapper.get('[data-test=retime-compute]').trigger('click')
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/retime'))).toHaveLength(1)
    finish?.(new Response(JSON.stringify(response))); await flushPromises()
    expect(wrapper.find('[data-test=retime-success]').exists()).toBe(true)
    expect(wrapper.text()).toContain('预览独立失败')
  })
  it('上传201/预览→四字段原UTC→2576行四图→登记事件/快照，改输入过期失败清旧成功', async () => {
    let fail = false
    const network = mockNetwork(), fetchMock = vi.fn((url: string, options?: RequestInit) => url.endsWith('/retime') && fail ? Promise.resolve(new Response('{"error":"IllegalArgumentException: 原UTC无效"}', { status: 400 })) : network(url, options))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticRetimeView)
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['原CSV'], '源.csv', { type: 'text/csv' })] })
    await input.trigger('change'); await flushPromises()
    expect(wrapper.text()).toContain('源1'); expect(wrapper.text()).toContain('原文')
    await wrapper.get('[data-retime-field=taskName]').setValue('T04-retime')
    await wrapper.get('[data-retime-field=launchTimeUtc]').setValue('2026-07-02 07:57:00.000')
    await wrapper.get('[data-test=retime-compute]').trigger('click'); await flushPromises()
    const request = JSON.parse(String(fetchMock.mock.calls.find(([url]) => url.endsWith('/retime'))?.[1]?.body))
    expect(request).toEqual({ taskName: 'T04-retime', targetId: 'RET-001', trajectoryCsvPath: reference.serverPath, launchTimeUtc: '2026-07-02 07:57:00.000' })
    expect(wrapper.findAll('svg')).toHaveLength(4); expect(wrapper.get('[data-test=fit-trajectory]').text()).toContain('2576 点')
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toEqual([{ targetId: 'RETIMED-TGT', targetName: '重定时/T04-retime/RETIMED-TGT', reference: { ...reference, fileId: '输出1', serverPath: 'server/output.csv' } }])
    await wrapper.get('[data-retime-field=launchTimeUtc]').setValue('原非法UTC')
    expect(wrapper.text()).toContain('输入已修改'); expect(wrapper.get('[data-test=retime-submitted]').text()).toContain('2026-07-02 07:57:00.000')
    fail = true; await wrapper.get('[data-test=retime-compute]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('IllegalArgumentException: 原UTC无效'); expect(wrapper.find('[data-test=retime-success]').exists()).toBe(false)
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toEqual([])
  })
  it('已有引用切换/失效清旧路径，null同值提交，上传失败不能沿用旧引用', async () => {
    const fetchMock = mockNetwork(); vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticRetimeView, { props: { generatedResults: [candidate] } })
    await wrapper.get('[data-test=retime-source-mode]').setValue('generated')
    await wrapper.get('[data-test=retime-result-select]').setValue('源1'); await flushPromises()
    await wrapper.get('[data-retime-null=taskName]').setValue(true); await wrapper.get('[data-retime-null=targetId]').setValue(true)
    await wrapper.get('[data-retime-field=launchTimeUtc]').setValue('原UTC字面')
    await wrapper.get('[data-test=retime-compute]').trigger('click'); await flushPromises()
    expect(JSON.parse(String(fetchMock.mock.calls.find(([url]) => url.endsWith('/retime'))?.[1]?.body))).toEqual({ taskName: null, targetId: null, launchTimeUtc: '原UTC字面', trajectoryCsvPath: reference.serverPath })
    await wrapper.setProps({ generatedResults: [] }); await flushPromises()
    const count = fetchMock.mock.calls.filter(([url]) => url.endsWith('/retime')).length
    await wrapper.get('[data-test=retime-compute]').trigger('click'); await flushPromises()
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/retime'))).toHaveLength(count)
    await wrapper.get('[data-test=retime-source-mode]').setValue('upload')
    fetchMock.mockImplementation(() => Promise.resolve(new Response('{"error":"上传失败"}', { status: 500 })))
    const input = wrapper.get('input[type=file]'); Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['CSV'], '失败.csv')] })
    await input.trigger('change'); await flushPromises(); await wrapper.get('[data-test=retime-compute]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('请选择'); expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/retime'))).toHaveLength(count)
  })
  it('自身上次结果再次输入先截真实路径再撤自身候选，忙时防重复，旧慢登记不能污染新身份', async () => {
    let finishOld: ((value: Response) => void) | undefined, executions = 0
    const fetchMock = mockNetwork()
    fetchMock.mockImplementation((url: string) => {
      if (url.endsWith('/retime')) { executions += 1; return Promise.resolve(new Response(JSON.stringify({ ...response, files: [{ ...response.files[0], csvPath: `server/output${executions}.csv` }] }))) }
      if (url.endsWith('/register-result') && executions === 1) return new Promise<Response>(resolve => { finishOld = resolve })
      return Promise.resolve(new Response('{"error":"新文件登记失败"}', { status: 500 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const own = { ...candidate, targetName: '重定时/上次', reference: { ...reference, fileId: '自己', serverPath: 'server/self.csv' } }
    const wrapper = mount(BallisticRetimeView, { props: { generatedResults: [own] } })
    await wrapper.get('[data-test=retime-source-mode]').setValue('generated'); await wrapper.get('[data-test=retime-result-select]').setValue('自己'); await flushPromises()
    await wrapper.get('[data-test=retime-compute]').trigger('click'); await wrapper.setProps({ generatedResults: [] }); await flushPromises()
    expect(JSON.parse(String(fetchMock.mock.calls.find(([url]) => url.endsWith('/retime'))?.[1]?.body)).trajectoryCsvPath).toBe('server/self.csv')
    await wrapper.setProps({ generatedResults: [candidate] }); await wrapper.get('[data-test=retime-result-select]').setValue('源1'); await flushPromises()
    await wrapper.get('[data-test=retime-compute]').trigger('click'); await flushPromises()
    finishOld?.(new Response(JSON.stringify(reference), { status: 201 })); await flushPromises()
    expect(wrapper.emitted('references')?.at(-1)?.[0]).toEqual([])
  })
})
