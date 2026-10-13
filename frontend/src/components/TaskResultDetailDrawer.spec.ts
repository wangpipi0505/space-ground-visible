import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import TaskResultDetailDrawer from './TaskResultDetailDrawer.vue'

describe('TaskResultDetailDrawer', () => {
  it('shows the persisted full skipped-pair list separately from result JSON', () => {
    const wrapper = mount(TaskResultDetailDrawer, {
      props: {
        visible: true,
        loading: false,
        formatTime: (time?: string | null) => time ?? '—',
        detail: {
          id: '1',
          taskId: 'task-1',
          taskType: 'VISIBILITY',
          status: 'SUCCEEDED',
          totalPairs: 2,
          completedPairs: 2,
          skippedPairsJson: '[{"targetId":"21137","stage":"PRECHECK"}]',
        },
      },
      global: {
        plugins: [ElementPlus],
        // 抽屉生产环境会 Teleport 到 body；此处保留插槽，专注验证明细内容本身。
        stubs: { ElDrawer: { template: '<section><slot /></section>' } },
      },
    })

    expect(wrapper.find('[data-test="task-result-skipped-pairs-json"]').text())
      .toContain('21137')
  })
})
