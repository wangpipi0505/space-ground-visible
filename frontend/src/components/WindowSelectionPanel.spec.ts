import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import WindowSelectionPanel from './WindowSelectionPanel.vue'

describe('WindowSelectionPanel', () => {
  it('binds merged coverage windows to their contributing link summary', () => {
    const wrapper = mount(WindowSelectionPanel, {
      props: {
        rows: [{
          key: 'coverage-0-2026-08-12T00:00:00Z',
          sourceLabel: '观测卫星一号—酒泉站；观测卫星二号—西安站',
          sourceCount: 2,
          startTime: '2026-08-12T00:00:00Z',
          endTime: '2026-08-12T00:10:00Z',
          durationSec: 600,
        }],
        customWindowRange: [new Date('2026-08-12T00:00:00Z'), new Date('2026-08-12T01:00:00Z')],
        loading: false,
        selectedWindowLabel: '连续下传覆盖：观测卫星一号—酒泉站；观测卫星二号—西安站',
        selectedWindowStartTime: '2026-08-12T00:00:00Z',
        selectedWindowEndTime: '2026-08-12T00:10:00Z',
        formatTime: (time: string) => time,
        formatDuration: (durationSec: number) => `${durationSec} 秒`,
      },
      global: { plugins: [ElementPlus] },
    })

    const columnProps = wrapper.findAllComponents({ name: 'ElTableColumn' })
      .map((column) => column.props('prop'))

    expect(columnProps).toContain('sourceLabel')
    expect(columnProps).toContain('sourceCount')
    expect(columnProps).not.toContain('satelliteName')
    expect(columnProps).not.toContain('stationName')
    expect(wrapper.get('[data-test="selected-observation-window"]').text())
      .toContain('2026-08-12T00:00:00Z')
  })
})
