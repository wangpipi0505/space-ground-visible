import { mount } from '../test/vueTestUtils'
import ElementUI from 'element-ui'
import { describe, expect, it } from 'vitest'
import TaskSatelliteSelectionStep from './TaskSatelliteSelectionStep.vue'
describe('TaskSatelliteSelectionStep', () => {
  it('A19 实际输入框按 Enter 继续触发卫星查询', async () => {
    const wrapper = mount(TaskSatelliteSelectionStep, {
      props: {
        keyword: '',
        loading: false,
        satellites: [],
        selectedSatellites: [],
        total: 0,
        pageNumber: 1,
        pageSize: 50,
      },
    })
    await wrapper.get('input').trigger('keyup', { key: 'Enter', keyCode: 13 })
    expect(wrapper.emitted('query')).toHaveLength(1)
  })
  it('shows selected satellites by name without appending internal IDs', () => {
    const wrapper = mount(TaskSatelliteSelectionStep, {
      props: {
        keyword: '',
        loading: false,
        satellites: [],
        selectedSatellites: [{ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }],
        total: 0,
        pageNumber: 1,
        pageSize: 50,
      },
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.text()).toContain('观测卫星一号')
    expect(wrapper.text()).not.toContain('（1）')
  })
  it('uses the shared compact card header for task satellites', () => {
    const wrapper = mount(TaskSatelliteSelectionStep, {
      props: {
        keyword: '',
        loading: false,
        satellites: [],
        selectedSatellites: [],
        total: 0,
        pageNumber: 1,
        pageSize: 50,
      },
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.get('.compact-card-header h2').text()).toBe('任务卫星')
  })
})
