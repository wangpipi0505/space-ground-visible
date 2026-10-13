import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import TargetSelectionStep from './TargetSelectionStep.vue'

describe('TargetSelectionStep', () => {
  it('collects a minimum mass and plain-text target exclusion condition', () => {
    const wrapper = mount(TargetSelectionStep, {
      props: {
        form: {
          belongs: [], minWeight: undefined, excludeNameText: '', excludeNameMode: 'TEXT',
          minTleEpochCode: '23340', maxApproximationDays: 14,
        } as any,
        belongOptions: [{ code: 'X-TEST', count: 3, description: 'BELONG_=X-TEST，当前目标数=3' }],
        loading: false,
        targets: [],
        total: 0,
        pageNumber: 1,
        pageSize: 50,
        filterBreakdown: { belongsHit: 8, weightHit: 5, epochHit: 5, excludeHit: 2 },
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('最小质量')
    expect(wrapper.text()).not.toContain('最大质量')
    expect(wrapper.text()).toContain('历元约束（YYDDD）')
    expect(wrapper.text()).toContain('23340')
    expect(wrapper.text()).toContain('建议近似传播时长')
    expect(wrapper.text()).toContain('超出建议时长的目标仍会尝试计算')
    expect(wrapper.findAllComponents({ name: 'ElOption' }).map((option) => option.props('label')))
      .toContain('X-TEST（3）')
    expect(wrapper.find('[data-test="target-name-exclude"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="target-filter-breakdown"]').text())
      .toContain('国籍后 8')
    expect(wrapper.find('[data-test="target-filter-breakdown"]').text())
      .not.toContain('发射时间后')
    expect(wrapper.text()).not.toContain('本次筛选结果将直接用于规划')
  })
})
