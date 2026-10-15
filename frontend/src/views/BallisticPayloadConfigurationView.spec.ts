import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BallisticPayloadConfigurationView from './BallisticPayloadConfigurationView.vue'
import BallisticWindowControls from '../components/BallisticWindowControls.vue'
import StandardPayloadEditor from '../components/StandardPayloadEditor.vue'
import { createDefaultPayload } from '../utils/standardPayload'

/** 真实组件与真实 typed API；仅 HTTP 传输使用 mock，浏览器验收另接独立 Spring 服务。 */
describe('弹道双星多载荷配置生产视图', () => {
  afterEach(() => vi.unstubAllGlobals())
  const satellites = [{ id: 1, name: '星甲', modelType: 'TLE_SGP4' }, { id: 2, name: '星乙', modelType: 'TLE_SGP4' }]

  it('示例朝地球俯仰与空间标准、新增和目录默认隔离', async () => {
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites }, global: { plugins: [ElementPlus] } })
    const draft = () => wrapper.findComponent(BallisticWindowControls).props('satellites')
    expect(draft().map(s => s.payloads[0]?.elevationMinDeg)).toEqual([-90, -90])
    expect(draft().map(s => s.payloads[0]?.elevationMaxDeg)).toEqual([0, 0])
    expect(createDefaultPayload()).toMatchObject({ elevationMinDeg: -4, elevationMaxDeg: 60 })
    await wrapper.get('[data-test=add-ballistic-payload-MANUAL-1]').trigger('click')
    expect(draft()[0]!.payloads[1]).toMatchObject({ elevationMinDeg: -4, elevationMaxDeg: 60 })
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', [1])
    await flushPromises()
    expect(draft()[0]!.payloads[0]).toMatchObject({ elevationMinDeg: -4, elevationMaxDeg: 60 })
  })

  it('不用预览HTTP即可计算，非法载荷阻止提交，失败不伪造成功', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"error":"测试：服务拒绝"}', { status: 400 }))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites }, global: { plugins: [ElementPlus] } })
    expect(wrapper.find('[data-test=preview-ballistic-payload]').exists()).toBe(false)
    await wrapper.get('[data-test=window-source]').setValue('remote')
    await wrapper.get('input[placeholder="https://host/path/trajectory.csv"]').setValue('https://host/path/trajectory.csv')
    await wrapper.findAll('button').find(b => b.text() === '导入网络地址')!.trigger('click')
    wrapper.findComponent(StandardPayloadEditor).vm.$emit('update:modelValue', { ...createDefaultPayload('GROUND'), angleRateDegPerSec: -1 })
    await flushPromises()
    expect(wrapper.text()).toContain('不能使用 -1')
    await wrapper.get('[data-test=compute-window]').trigger('click')
    expect(fetcher).not.toHaveBeenCalled()
    wrapper.findComponent(StandardPayloadEditor).vm.$emit('update:modelValue', createDefaultPayload())
    await flushPromises()
    await wrapper.get('[data-test=compute-window]').trigger('click'); await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]![0]).toMatch(/\/search$/)
    expect(wrapper.text()).toContain('测试：服务拒绝')
    expect(wrapper.find('[data-test=window-success]').exists()).toBe(false)
  })

  it('当前星切换保持原草稿身份且初始就有结果位置', async () => {
    const wrapper = mount(BallisticPayloadConfigurationView, { props: { satellites }, global: { plugins: [ElementPlus] } })
    expect(wrapper.find('[data-test=window-result-panel]').exists()).toBe(true)
    const ids = wrapper.findAll('[data-payload-id]').map(p => p.attributes('data-payload-id'))
    await wrapper.get('[data-test=current-payload-satellite]').setValue('MANUAL-2')
    expect(wrapper.findAll('[data-payload-id]').map(p => p.attributes('data-payload-id'))).toEqual(ids)
  })
})
