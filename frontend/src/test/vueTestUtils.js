/** Vue Test Utils 1 的挂载边界适配；只转换工具接口，既有用例继续覆盖原业务断言。 */
import { mount as mountVue2, createLocalVue } from '@vue/test-utils'
import ElementUI from 'element-ui'

const mounted = new Set()

function wrap(wrapper) {
  if (!wrapper || wrapper.__vue2Adapted) return wrapper
  wrapper.__vue2Adapted = true
  for (const method of ['find', 'get', 'findComponent', 'getComponent']) {
    if (!wrapper[method]) continue
    const original = wrapper[method].bind(wrapper)
    wrapper[method] = (...args) => wrap(original(...args))
  }
  for (const method of ['findAll', 'findAllComponents']) {
    if (!wrapper[method]) continue
    const original = wrapper[method].bind(wrapper)
    wrapper[method] = (...args) => original(...args).wrappers.map(wrap)
  }
  if (wrapper.setValue && wrapper.element?.type === 'checkbox') {
    wrapper.setValue = (value) => wrapper.setChecked(value)
  }
  return wrapper
}

export function mount(component, options = {}) {
  const localVue = createLocalVue()
  localVue.use(ElementUI)
  const { global = {}, props, ...rest } = options
  const wrapper = wrap(
    mountVue2(component, {
      ...rest,
      localVue,
      propsData: props || rest.propsData,
      stubs: global.stubs || rest.stubs,
      mocks: global.mocks || rest.mocks,
    }),
  )
  mounted.add(wrapper)
  return wrapper
}

export function flushPromises() {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/** 每条回归结束清理挂载组件，避免 Vue 2 的全局弹层和计时器影响后续用例。 */
export function cleanupMounted() {
  for (const wrapper of mounted) wrapper.destroy()
  mounted.clear()
}
