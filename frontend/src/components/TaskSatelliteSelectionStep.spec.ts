import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import TaskSatelliteSelectionStep from './TaskSatelliteSelectionStep.vue'

describe('TaskSatelliteSelectionStep', () => {
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
      global: { plugins: [ElementPlus] },
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
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.get('.compact-card-header h2').text()).toBe('任务卫星')
  })
})
