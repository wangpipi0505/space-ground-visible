import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticPayloadConfigurationView from './BallisticPayloadConfigurationView.vue'
import StandardPayloadEditor from '../components/StandardPayloadEditor.vue'
import { createDefaultPayload } from '../utils/standardPayload'
import type { BallisticPayloadDefinition } from '../api/ballisticPayloads'

/** 真实组件与真实 typed API；仅 HTTP 传输使用 mock，浏览器验收另接独立 Spring 服务。 */
describe('弹道双星多载荷配置生产视图', () => {
  afterEach(() => vi.unstubAllGlobals())
  const satellites = [{ id: 1, name: '星甲', modelType: 'TLE_SGP4' }, { id: 2, name: '星乙', modelType: 'TLE_SGP4' }]

  it('按星保持数组顺序、载荷唯一身份及删除隔离，编辑和预览保持十二字段', async () => {
    const fetchMock = vi.fn().mockImplementation((_url, options: RequestInit) => Promise.resolve(new Response(String(options.body))))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites }, global: { plugins: [ElementPlus] } })
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', [1, 2])
    await flushPromises()
    await wrapper.get('[data-test="add-ballistic-payload-1"]').trigger('click')
    await wrapper.get('[data-test="add-ballistic-payload-2"]').trigger('click')
    let editors = wrapper.findAllComponents(StandardPayloadEditor)
    expect(editors).toHaveLength(4)
    const payloads = wrapper.findAll('[data-payload-id]').map((item) => item.attributes('data-payload-id'))
    expect(new Set(payloads).size).toBe(4)
    editors[0]!.vm.$emit('update:modelValue', { ...createDefaultPayload(), angleRateDegPerSec: -1, startupAngleRateDegPerSec: -1, azimuthMinDeg: null, azimuthMaxDeg: null })
    editors[1]!.vm.$emit('selectType', 'GROUND')
    await flushPromises()
    expect(wrapper.findAllComponents(StandardPayloadEditor)[1]!.props('modelValue').payloadType).toBe('GROUND')
    expect(wrapper.findAllComponents(StandardPayloadEditor)[2]!.props('modelValue').payloadType).toBe('SPACE')
    await wrapper.findAll('[data-test="preview-ballistic-payload"]')[0]!.trigger('click')
    await flushPromises()
    const request = JSON.parse(String(fetchMock.mock.calls[0]![1].body)) as BallisticPayloadDefinition
    expect(Object.keys(request)).toHaveLength(12)
    expect(request).toMatchObject({ payloadId: payloads[0], payloadName: '载荷1', angleRateDegPerSec: -1, startupAngleRateDegPerSec: -1, azimuthMinDeg: null })
    expect(wrapper.find('[data-test="ballistic-preview"]').text()).toContain(request.payloadId)
    await wrapper.findAll('[data-test="remove-ballistic-payload"]')[0]!.trigger('click')
    editors = wrapper.findAllComponents(StandardPayloadEditor)
    expect(editors).toHaveLength(3)
    expect(wrapper.findAll('[data-payload-id]').map((item) => item.attributes('data-payload-id'))).toEqual(payloads.slice(1))
    await wrapper.get('[data-test="add-ballistic-payload-1"]').trigger('click')
    const newIds = wrapper.findAll('[data-payload-id]').map((item) => item.attributes('data-payload-id'))
    expect(new Set(newIds).size).toBe(4)
    expect(newIds).not.toContain(payloads[0])
  })

  it('非法边界阻止HTTP并显示中文；服务400显示错误且不伪造成功回显', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'INVALID_ARGUMENT', error: '测试：载荷参数被服务拒绝' }), { status: 400 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites }, global: { plugins: [ElementPlus] } })
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', [1])
    await flushPromises()
    wrapper.findComponent(StandardPayloadEditor).vm.$emit('update:modelValue', { ...createDefaultPayload('GROUND'), angleRateDegPerSec: -1 })
    await flushPromises()
    expect(wrapper.text()).toContain('不能使用 -1')
    await wrapper.get('[data-test="preview-ballistic-payload"]').trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.findComponent(StandardPayloadEditor).vm.$emit('update:modelValue', createDefaultPayload('GROUND'))
    await flushPromises()
    await wrapper.get('[data-test="preview-ballistic-payload"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('测试：载荷参数被服务拒绝')
    expect(wrapper.find('[data-test="ballistic-preview"]').exists()).toBe(false)
  })
})
