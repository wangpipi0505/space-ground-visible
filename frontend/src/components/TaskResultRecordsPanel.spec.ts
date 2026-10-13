import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { VisibilityTaskResultDetail, VisibilityTaskResultSummary } from '../api/types'

vi.mock('../api/visibilityTaskResults', () => ({
  pageVisibilityTaskResults: vi.fn(),
  getVisibilityTaskResultRecord: vi.fn(),
  updateVisibilityTaskResultRemark: vi.fn(),
  deleteVisibilityTaskResultRecord: vi.fn(),
}))

import {
  deleteVisibilityTaskResultRecord,
  getVisibilityTaskResultRecord,
  pageVisibilityTaskResults,
  updateVisibilityTaskResultRemark,
} from '../api/visibilityTaskResults'
import TaskResultRecordsPanel from './TaskResultRecordsPanel.vue'

const summaryRecord: VisibilityTaskResultSummary = {
  // 选用超过 Number.MAX_SAFE_INTEGER 的真实形态 ID，防止测试退化为安全小整数场景。
  id: '2097222553115705346',
  taskId: 'task-abc',
  taskType: 'VISIBILITY',
  status: 'SUCCEEDED',
  totalPairs: 10,
  completedPairs: 10,
  remark: '重点批次',
  createTime: '2026-08-12T00:00:00Z',
  completeTime: '2026-08-12T00:05:00Z',
}

const detailRecord: VisibilityTaskResultDetail = {
  ...summaryRecord,
  requestJson: '{"observerSatelliteIds":[101]}',
  resultJson: '{"results":[]}',
}

/** 以工作台的时间格式化函数挂载任务结果记录面板。 */
function mountPanel() {
  return mount(TaskResultRecordsPanel, {
    props: { formatTime: (time: string) => time },
    global: { plugins: [ElementPlus] },
  })
}

describe('TaskResultRecordsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(pageVisibilityTaskResults).mockResolvedValue({
      total: 1, pageNumber: 1, pageSize: 20, records: [summaryRecord],
    })
  })

  it('loads persisted task result records on mount', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(pageVisibilityTaskResults).toHaveBeenCalledWith({
      pageNumber: 1, pageSize: 20, status: undefined, keyword: '',
    })
    expect(wrapper.text()).toContain('task-abc')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('重点批次')
  })

  it('passes status filter and keyword to the paged query', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const recordsPanel = wrapper.findComponent(TaskResultRecordsPanel)
    await wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'FAILED')
    await wrapper.get('[data-test="task-result-keyword"]').setValue('批次')
    await wrapper.get('[data-test="query-task-results"]').trigger('click')
    await flushPromises()

    expect(recordsPanel.exists()).toBe(true)
    expect(pageVisibilityTaskResults).toHaveBeenLastCalledWith({
      pageNumber: 1, pageSize: 20, status: 'FAILED', keyword: '批次',
    })
  })

  it('opens the detail drawer with formatted request and result JSON', async () => {
    vi.mocked(getVisibilityTaskResultRecord).mockResolvedValue(detailRecord)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="open-task-result-detail"]').trigger('click')
    await flushPromises()

    expect(getVisibilityTaskResultRecord).toHaveBeenCalledWith('2097222553115705346')
    expect(wrapper.get('[data-test="task-result-request-json"]').text()).toContain('"observerSatelliteIds": [')
    expect(wrapper.get('[data-test="task-result-result-json"]').text()).toContain('"results": []')
  })

  it('saves the edited remark and refreshes the row', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '新备注', action: 'confirm' } as never)
    vi.mocked(updateVisibilityTaskResultRemark).mockResolvedValue({ ...detailRecord, remark: '新备注' })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="edit-task-result-remark"]').trigger('click')
    await flushPromises()

    expect(updateVisibilityTaskResultRemark).toHaveBeenCalledWith('2097222553115705346', '新备注')
    expect(wrapper.text()).toContain('新备注')
  })

  it('deletes a record after confirmation and reloads the list', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({ value: '', action: 'confirm' } as never)
    vi.mocked(deleteVisibilityTaskResultRecord).mockResolvedValue(undefined)
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-test="delete-task-result"]').trigger('click')
    await flushPromises()

    expect(deleteVisibilityTaskResultRecord).toHaveBeenCalledWith('2097222553115705346')
    expect(pageVisibilityTaskResults).toHaveBeenCalledTimes(2)
  })
})
