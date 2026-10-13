import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticWindowControls from './BallisticWindowControls.vue'
import { applyReferenceOrbit } from '../utils/ballisticWindow'
import { createDefaultPayload } from '../utils/standardPayload'

/** 组件真实事件→HTTP封装→结果：失败换文件不得沿用旧成功引用，输入错误不得发送计算。 */
describe('T22 单一草稿文件与计算状态', () => {
  afterEach(() => vi.unstubAllGlobals())
  const satellites = [{ satelliteId: 'S1', name: '星甲', ...applyReferenceOrbit(), payloads: [{ payloadId: 'P1', payloadName: '甲', ...createDefaultPayload() }] }]
  it('T24 显式选择真实登记结果、Windows精确来源、UTC沿文件，注销后禁止旧成功回退', async () => {
    const reference = { fileId: 'generated-1', originalName: '模拟.csv', serverPath: 'G:\\服务运行根\\模拟.csv', sizeBytes: 561736, contentType: 'text/csv' }
    const generatedResults = [{ targetId: 'SIM-001', targetName: '默认模拟/SIM-001', reference }]
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(url.includes('/preview') ? new Response('{"header":["原头"],"rows":[["原值"]],"page":1,"limit":50,"totalRows":2576,"metadata":{}}') : new Response('{"referenceUtc":"2026-06-04 04:00:00.000","best":null,"allResults":[]}')))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticWindowControls, { props: { satellites, generatedResults } })
    await wrapper.get('[data-test=window-source]').setValue('generated')
    await wrapper.get('[data-test=generated-result-select]').setValue('generated-1'); await flushPromises()
    expect(wrapper.text()).toContain('模拟.csv')
    await wrapper.get('[data-test=compute-window]').trigger('click'); await flushPromises()
    const body = JSON.parse(String(fetchMock.mock.calls.find(([url]) => String(url).endsWith('/search'))![1].body))
    expect(body.ballistics[0]).toEqual({ targetId: 'SIM-001', targetName: '默认模拟/SIM-001', csvPath: reference.serverPath })
    expect(body.satellites[0].payloads[0].payloadId).toBe('P1')
    expect(wrapper.find('[data-test=window-success]').exists()).toBe(true)
    await wrapper.setProps({ generatedResults: [] }); await flushPromises()
    const before = fetchMock.mock.calls.length
    await wrapper.get('[data-test=compute-window]').trigger('click')
    expect(fetchMock.mock.calls).toHaveLength(before)
    expect(wrapper.find('[data-test=window-success]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已失效')
  })
  it('原生选择上传/预览后提交同一载荷UTC，计算失败清旧结果，替换失败清文件引用', async () => {
    let failUpload = false
    let failCompute = false
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.endsWith('/files')) return Promise.resolve(failUpload ? new Response('{"error":"扩展名无效"}', { status: 400 }) : new Response(JSON.stringify({ fileId: 'f1', originalName: '原.csv', sizeBytes: 4, contentType: 'text/csv', serverPath: 'G:\\自定义运行根\\登记文件.csv' }), { status: 201 }))
      if (url.includes('/preview')) return Promise.resolve(new Response('{"fileId":"f1","header":["原表头"],"rows":[["原值"]],"totalRows":1,"page":1,"limit":50,"metadata":{}}'))
      return Promise.resolve(failCompute ? new Response('{"error":"原服务拒绝文件"}', { status: 400 }) : new Response('{"referenceUtc":"UTC","best":null,"allResults":[],"payloadType":"SPACE","visibilityConstraint":{}}'))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticWindowControls, { props: { satellites } })
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['abcd'], '原.csv')] })
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('原表头')
    await wrapper.get('[data-test=compute-window]').trigger('click')
    await flushPromises()
    const body = JSON.parse(String(fetchMock.mock.calls.find(([url]) => String(url).endsWith('/search'))![1].body))
    expect(body.ballistics[0].csvPath).toBe('G:\\自定义运行根\\登记文件.csv')
    expect(body.satellites[0].payloads[0].payloadId).toBe('P1')
    expect(body.satellites[0].epochUtc).toBe('2026/07/01 10:00:00.000')
    expect(wrapper.find('[data-test=window-success]').exists()).toBe(true)
    failCompute = true
    await wrapper.get('[data-test=compute-window]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('原服务拒绝文件')
    expect(wrapper.find('[data-test=window-success]').exists()).toBe(false)
    failUpload = true
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('扩展名无效')
    const before = fetchMock.mock.calls.length
    await wrapper.get('[data-test=compute-window]').trigger('click')
    expect(fetchMock.mock.calls).toHaveLength(before)
    expect(wrapper.text()).toContain('请成功上传文件')
  })
})
