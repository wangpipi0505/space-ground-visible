import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import StandardPayloadEditor from './StandardPayloadEditor.vue'
import { createDefaultPayload } from '../utils/standardPayload'

describe('共用标准载荷编辑器', () => {
  /** 使用真正 ElementPlus 控件，验证清空后派发 null 而非默认 0/NaN。 */
  it('九个数字字段清空为null并展示成对校验与LVLH说明', async () => {
    const wrapper = mount(StandardPayloadEditor, { props: { modelValue: createDefaultPayload() }, global: { plugins: [ElementPlus] } })
    expect(wrapper.findAllComponents({ name: 'ElInputNumber' })).toHaveLength(9)
    const input = wrapper.get('[data-field="azimuthMinDeg"] input')
    await input.setValue('')
    await input.trigger('change')
    await input.trigger('blur')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toMatchObject({ azimuthMinDeg: null, azimuthMaxDeg: 270 })
    await wrapper.setProps({ modelValue: { ...createDefaultPayload(), azimuthMinDeg: null } })
    expect(wrapper.text()).toContain('方位角上下限必须同时填写或同时留空')
    expect(wrapper.text()).toContain('方位 0° 沿速度方向')
    expect(wrapper.text()).toContain('俯仰正向背离地球')
  })

  it('切换类型事件交由业务包装处理且标准事件不带身份', async () => {
    const wrapper = mount(StandardPayloadEditor, { props: { modelValue: createDefaultPayload() }, global: { plugins: [ElementPlus] } })
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'GROUND')
    expect(wrapper.emitted('selectType')).toEqual([['GROUND']])
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  /** 锁定版本仅挂载时写 aria-disabled；从读取/保存状态恢复后必须真正可编辑。 */
  it('加载结束恢复数字框ARIA启用状态并保留大有限数原值', async () => {
    const wrapper = mount(StandardPayloadEditor, { props: { modelValue: { ...createDefaultPayload(), maxDistanceKm: 1e20 }, disabled: true }, global: { plugins: [ElementPlus] } })
    expect(wrapper.get('[data-field="maxDistanceKm"] input').attributes('aria-disabled')).toBe('true')
    await wrapper.setProps({ disabled: false })
    expect(wrapper.get('[data-field="maxDistanceKm"] input').attributes('aria-disabled')).toBe('false')
    expect((wrapper.get('[data-field="maxDistanceKm"] input').element as HTMLInputElement).value).toBe('100000000000000000000')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
