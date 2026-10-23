import { flushPromises } from './vueTestUtils'
/** Element UI控件将测试标记放在组件根节点；文本编辑仍由其真实input/textarea事件驱动。 */
export async function setElementInput(wrapper, selector, value) {
  const root = wrapper.get(selector)
  const control = root.element.matches('input, textarea') ? root : root.find('input, textarea')
  await control.setValue(value)
}
/** 通过ElSelect公开的v-model事件选择选项，避免依赖Element UI弹层的测试环境定位细节。 */
export async function setElementSelect(wrapper, selector, value) {
  const component = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((candidate) => candidate.element.matches(selector))
  if (!component) throw new Error(`找不到选择器 ${selector} 对应的 ElSelect`)
  component.vm.$emit('input', value)
  await flushPromises()
}
