import { mount } from '../test/vueTestUtils'
import ElementUI from 'element-ui'
import { describe, expect, it } from 'vitest'
import ObservationConstraintPanel from './ObservationConstraintPanel.vue'
describe('ObservationConstraintPanel', () => {
  it('allows direct editing of an existing combination duration', async () => {
    const constraints = [{ satelliteIds: [1, 2], minDurationSec: 400 }]
    const wrapper = mount(ObservationConstraintPanel, {
      props: {
        satellites: [
          { id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' },
          { id: 2, name: '观测卫星二号', modelType: 'TLE_SGP4' },
        ],
        draft: { satelliteIds: [], minDurationSec: 400 },
        constraints,
      },
      global: { plugins: [ElementUI] },
    })
    await wrapper.vm.$nextTick()
    const editableDurationColumns = wrapper
      .findAllComponents({ name: 'ElTableColumn' })
      .filter((column) => column.props('label') === '共同可见时长（秒）')
    expect(editableDurationColumns).toHaveLength(1)
  })
})
