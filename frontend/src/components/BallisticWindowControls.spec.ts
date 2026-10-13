import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticWindowControls from './BallisticWindowControls.vue'
import { applyReferenceOrbit } from '../utils/ballisticWindow'
import { createDefaultPayload } from '../utils/standardPayload'

/** 组件真实事件→HTTP封装→结果：失败换文件不得沿用旧成功引用，输入错误不得发送计算。 */
describe('T22 单一草稿文件与计算状态', () => {
  afterEach(() => vi.unstubAllGlobals())
  const satellites = [{ satelliteId: 'S1', name: '星甲', ...applyReferenceOrbit(), payloads: [{ payloadId: 'P1', payloadName: '甲', ...createDefaultPayload() }] }]
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
