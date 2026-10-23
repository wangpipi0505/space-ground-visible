import { mount } from '../test/vueTestUtils'
import ElementUI from 'element-ui'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import VisibilityPlanningStep from './VisibilityPlanningStep.vue'
/** 组装组件必需的基础属性，测试用例可按需覆盖。 */
function baseProps(overrides = {}) {
  return {
    satellites: [],
    selectedWindowLabel: '下传窗口：卫星一号—地面站一号',
    selectedWindowStartTime: '2026-08-12T00:00:00Z',
    selectedWindowEndTime: '2026-08-12T00:10:00Z',
    timeRange: [new Date('2026-08-12T00:00:00Z'), new Date('2026-08-12T00:10:00Z')],
    form: {
      coarseStepSec: 60,
      fineStepSec: 0.001,
      illuminationConstraint: {
        mode: 'ANY',
        observerStates: [],
        targetStates: [],
      },
    },
    plan: {
      results: [
        {
          targetId: 'default-target',
          targetName: '默认目标',
          observerSatelliteIds: ['1'],
          observerSatelliteNames: ['观测星一'],
          observationType: 'SINGLE',
          observationTypeName: '单星观测',
          arcs: [],
        },
      ],
    },
    loading: false,
    mode: 'sync',
    task: null,
    taskRunning: false,
    taskProgressPercent: 0,
    formatTime: (time) => time,
    formatDuration: (durationSec) => `${durationSec} 秒`,
    ...overrides,
  }
}
describe('VisibilityPlanningStep', () => {
  it('A19 点击实际目标复选框保持折叠组展开状态', async () => {
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        plan: {
          results: [
            {
              targetId: 'target-1',
              targetName: '目标一',
              observerSatelliteIds: ['1'],
              observerSatelliteNames: ['观测星一'],
              observationType: 'SINGLE',
              observationTypeName: '单星观测',
              arcs: [
                {
                  startTime: '2026-08-12T00:01:00Z',
                  endTime: '2026-08-12T00:09:00Z',
                  durationSec: 480,
                  observerIlluminations: [],
                  targetIllumination: { stateName: '阳照' },
                },
              ],
            },
          ],
        },
      }),
    })
    const group = wrapper.getComponent({ name: 'ElCollapse' })
    const before = [...group.vm.activeNames]
    wrapper.get('[data-test="target-select-checkbox"] input').element.click()
    await nextTick()
    expect(group.vm.activeNames).toEqual(before)
  })
  it('groups one target once, nests its observation combinations, and renders a separate summary block', () => {
    const tripleArc = {
      startTime: '2026-08-12T00:01:00Z',
      endTime: '2026-08-12T00:09:00Z',
      durationSec: 480,
      observerIlluminations: [],
      targetIllumination: { targetId: 'target-1', targetName: '目标一', state: 'SUNLIT', stateName: '阳照' },
      satelliteSegments: [],
    }
    const singleArc = {
      startTime: '2026-08-12T00:02:00Z',
      endTime: '2026-08-12T00:05:00Z',
      durationSec: 180,
      observerIlluminations: [],
      targetIllumination: { targetId: 'target-1', targetName: '目标一', state: 'SUNLIT', stateName: '阳照' },
    }
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        satellites: [
          { id: 1, name: '观测星一', modelType: 'TLE_SGP4' },
          { id: 2, name: '观测星二', modelType: 'TLE_SGP4' },
          { id: 3, name: '观测星三', modelType: 'TLE_SGP4' },
        ],
        form: {
          coarseStepSec: 60,
          fineStepSec: 0.001,
          illuminationConstraint: { mode: 'ANY', observerStates: [], targetStates: [] },
          observationConstraints: [
            { satelliteIds: [1, 2, 3], minDurationSec: 400 },
            { satelliteIds: [1, 2], minDurationSec: 400 },
          ],
        },
        plan: {
          results: [
            {
              targetId: 'target-1',
              targetName: '目标一',
              observerSatelliteIds: ['1', '2', '3'],
              observerSatelliteNames: ['观测星一', '观测星二', '观测星三'],
              observationType: 'COOPERATIVE',
              observationTypeName: '共同观测',
              conciseSummary: '任务摘要：ABC(480秒)',
              arcs: [tripleArc],
            },
            {
              targetId: 'target-1',
              targetName: '目标一',
              observerSatelliteIds: ['1'],
              observerSatelliteNames: ['观测星一'],
              observationType: 'SINGLE',
              observationTypeName: '单星观测',
              conciseSummary: '任务摘要：ABC(480秒)',
              arcs: [singleArc],
            },
            {
              targetId: 'target-2',
              targetName: '目标二',
              observerSatelliteIds: ['1'],
              observerSatelliteNames: ['观测星一'],
              observationType: 'SINGLE',
              observationTypeName: '单星观测',
              arcs: [singleArc],
            },
          ],
        },
      }),
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.findAll('[data-test="target-result-group"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-test="combination-result-group"]')).toHaveLength(3)
    // 列表分组不再内嵌摘要原文;结构化摘要拆分到独立的“摘要信息”折叠块。
    const groupTexts = wrapper
      .findAll('[data-test="target-result-group"]')
      .map((group) => group.text())
      .join('\n')
    expect(groupTexts).not.toContain('任务摘要：ABC(480秒)')
    expect(wrapper.find('[data-test="wintool-summary-collapse"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="constraint-summary"]').exists()).toBe(false)
  })
  it('filters grouped results by target name without changing the contained combinations', async () => {
    const arc = {
      startTime: '2026-08-12T00:01:00Z',
      endTime: '2026-08-12T00:09:00Z',
      durationSec: 480,
      observerIlluminations: [],
      targetIllumination: { targetId: 'target', targetName: '目标', state: 'SUNLIT', stateName: '阳照' },
    }
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        plan: {
          results: [
            {
              targetId: 'target-a',
              targetName: '目标甲',
              observerSatelliteIds: ['1'],
              observerSatelliteNames: ['观测星一'],
              observationType: 'SINGLE',
              observationTypeName: '单星观测',
              arcs: [arc],
            },
            {
              targetId: 'target-b',
              targetName: '目标乙',
              observerSatelliteIds: ['1', '2'],
              observerSatelliteNames: ['观测星一', '观测星二'],
              observationType: 'COOPERATIVE',
              observationTypeName: '共同观测',
              arcs: [arc],
            },
          ],
        },
      }),
      global: { plugins: [ElementUI] },
    })
    const targetSelect = wrapper
      .findAllComponents({ name: 'ElSelect' })
      .find((component) => component.attributes('data-test') === 'target-result-select')
    if (!targetSelect) throw new Error('未找到目标结果下拉框')
    targetSelect.vm.$emit('input', 'target-b')
    await nextTick()
    const groups = wrapper.findAll('[data-test="target-result-group"]')
    expect(groups).toHaveLength(1)
    expect(groups[0].text()).toContain('目标乙')
    expect(groups[0].findAll('[data-test="combination-result-group"]')).toHaveLength(1)
  })
  it('uses configured filters, paginates ten targets per page, and opens skipped pairs in a dialog', async () => {
    const arc = {
      startTime: '2026-08-12T00:01:00Z',
      endTime: '2026-08-12T00:09:00Z',
      durationSec: 480,
      observerIlluminations: [],
      targetIllumination: { targetId: 'target', targetName: '目标', state: 'SUNLIT', stateName: '阳照' },
    }
    const results = Array.from({ length: 11 }, (_, index) => ({
      targetId: `target-${index}`,
      targetName: `目标${index}`,
      observerSatelliteIds: ['1', '2'],
      observerSatelliteNames: ['观测星一', '观测星二'],
      observationType: 'COOPERATIVE',
      observationTypeName: '共同观测',
      arcs: [arc],
    }))
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        satellites: [
          { id: 1, name: '观测星一', modelType: 'TLE_SGP4' },
          { id: 2, name: '观测星二', modelType: 'TLE_SGP4' },
        ],
        form: {
          coarseStepSec: 60,
          fineStepSec: 0.001,
          illuminationConstraint: { mode: 'ANY', observerStates: [], targetStates: [] },
          observationConstraints: [{ satelliteIds: [1, 2], minDurationSec: 400 }],
        },
        plan: {
          results,
          totalSkippedPairs: 1,
          skippedPairs: [
            {
              observerSatelliteId: '1',
              observerSatelliteName: '观测星一',
              targetId: 'skipped',
              targetName: '跳过目标',
              stage: 'PRECHECK',
              reason: '目标轨道状态不可用',
            },
          ],
        },
      }),
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.find('[data-test="constraint-summary"]').exists()).toBe(false)
    const targetOptionLabels = wrapper
      .findAllComponents({ name: 'ElOption' })
      .filter((option) => option.attributes('data-test') === 'target-result-option')
      .map((option) => option.props('label'))
    const combinationOptionLabels = wrapper
      .findAllComponents({ name: 'ElOption' })
      .filter((option) => option.attributes('data-test') === 'combination-result-option')
      .map((option) => option.props('label'))
    expect(targetOptionLabels).toHaveLength(11)
    expect(combinationOptionLabels).toContain('观测星一 + 观测星二 ≥ 400 秒')
    expect(wrapper.getComponent({ name: 'ElPagination' }).props('pageSize')).toBe(10)
    expect(wrapper.get('[data-test="skipped-pairs-button"]').text()).toContain('查看跳过项（1）')
    await wrapper.get('[data-test="skipped-pairs-button"]').trigger('click')
    expect(wrapper.getComponent({ name: 'ElDialog' }).props('visible')).toBe(true)
  })
  it('shows a qualified parent window without rendering its illumination fragments', async () => {
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        plan: {
          results: [
            {
              targetId: 'target-1',
              targetName: '目标一',
              observerSatelliteIds: ['1', '2'],
              observerSatelliteNames: ['观测星一', '观测星二'],
              observationType: 'COOPERATIVE',
              observationTypeName: '共同观测',
              arcs: [
                {
                  startTime: '2026-08-12T00:01:00Z',
                  endTime: '2026-08-12T00:09:20Z',
                  durationSec: 500,
                  observerIlluminations: [],
                  targetIllumination: { targetId: 'target-1', targetName: '目标一', state: 'UMBRA', stateName: '本影' },
                  satelliteSegments: [
                    {
                      satelliteId: '1',
                      satelliteName: '观测星一',
                      state: 'UMBRA',
                      stateName: '本影',
                      startTime: '2026-08-12T00:01:00Z',
                      endTime: '2026-08-12T00:09:20Z',
                      durationSec: 500,
                    },
                    {
                      satelliteId: '2',
                      satelliteName: '观测星二',
                      state: 'UMBRA',
                      stateName: '本影',
                      startTime: '2026-08-12T00:01:00Z',
                      endTime: '2026-08-12T00:09:20Z',
                      durationSec: 500,
                    },
                  ],
                  illuminationSegments: [
                    {
                      startTime: '2026-08-12T00:01:00Z',
                      endTime: '2026-08-12T00:09:19.900Z',
                      durationSec: 499.9,
                      observerIlluminations: [],
                      targetIllumination: {
                        targetId: 'target-1',
                        targetName: '目标一',
                        state: 'UMBRA',
                        stateName: '本影',
                      },
                    },
                    {
                      startTime: '2026-08-12T00:09:19.900Z',
                      endTime: '2026-08-12T00:09:20Z',
                      durationSec: 0.1,
                      observerIlluminations: [],
                      targetIllumination: {
                        targetId: 'target-1',
                        targetName: '目标一',
                        state: 'SUNLIT',
                        stateName: '阳照',
                      },
                    },
                  ],
                },
              ],
            },
          ],
        },
      }),
      global: { plugins: [ElementUI] },
    })
    await nextTick()
    await nextTick()
    expect(wrapper.get('[data-test="qualified-arc-duration"]').text()).toContain('500 秒')
    await wrapper.get('.el-table__expand-icon').trigger('click')
    await nextTick()
    expect(wrapper.get('[data-test="satellite-segment-heading"]').text()).toContain('各星原始单星观测窗口')
    expect(wrapper.find('[data-test="illumination-segment-detail"]').exists()).toBe(false)
  })
  it('binds result columns to readable names and observation type', () => {
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps(),
      global: { plugins: [ElementUI] },
    })
    const columnProps = wrapper.findAllComponents({ name: 'ElTableColumn' }).map((column) => column.props('prop'))
    expect(columnProps).toContain('observerSatelliteNames')
    expect(columnProps).toContain('targetName')
    expect(columnProps).toContain('observationTypeName')
    expect(columnProps).toContain('observerIlluminationText')
    expect(columnProps).toContain('targetIlluminationName')
    expect(columnProps).not.toContain('targetId')
    expect(wrapper.get('[data-test="observation-time-range"]').text()).toContain('2026-08-12T00:00:00Z')
  })
  it('locks full-target planning to a background task with progress', () => {
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps(),
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.get('[data-test="plan-mode"]').text()).toContain('后台任务（显示进度）')
    expect(wrapper.findComponent({ name: 'ElRadioGroup' }).exists()).toBe(false)
  })
  it('shows background task progress and emits cancellation', async () => {
    const task = {
      taskId: 'task-1',
      status: 'RUNNING',
      totalPairs: 10,
      completedPairs: 4,
    }
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({ mode: 'task', task, taskRunning: true, taskProgressPercent: 40 }),
      global: { plugins: [ElementUI] },
    })
    const progressSection = wrapper.get('[data-test="task-progress"]')
    expect(progressSection.text()).toContain('执行中')
    expect(progressSection.text()).toContain('4 / 10')
    expect(wrapper.getComponent({ name: 'ElProgress' }).props('percentage')).toBe(40)
    await wrapper.get('[data-test="cancel-task"]').trigger('click')
    expect(wrapper.emitted('cancelTask')).toHaveLength(1)
  })
  it('hides the cancel button once the background task has finished', () => {
    const task = {
      taskId: 'task-1',
      status: 'SUCCEEDED',
      totalPairs: 10,
      completedPairs: 10,
    }
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({ mode: 'task', task, taskRunning: false, taskProgressPercent: 100 }),
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.get('[data-test="task-progress"]').text()).toContain('已完成')
    expect(wrapper.find('[data-test="cancel-task"]').exists()).toBe(false)
  })
  it('keeps skipped pair details out of the main result area until the dialog is opened', async () => {
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        plan: {
          results: [],
          totalSkippedPairs: 2,
          skippedPairs: [
            {
              observerSatelliteId: '1',
              observerSatelliteName: '观测星一',
              targetId: '2',
              targetName: '目标二',
              stage: 'PRECHECK',
              reason: '目标轨道状态不可用',
            },
          ],
        },
      }),
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.find('[data-test="skipped-pairs-summary"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ephemeris-warning-summary"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="skipped-pairs-table"]').exists()).toBe(false)
    await wrapper.get('[data-test="skipped-pairs-button"]').trigger('click')
    await nextTick()
    expect(wrapper.get('[data-test="skipped-pairs-table"]').attributes('data-test')).toBe('skipped-pairs-table')
  })
  it('marks combination arcs expandable and single arcs not expandable', () => {
    const combinationRow = {
      key: 'combo',
      observerSatelliteNames: '观测星一 + 观测星二',
      targetName: '目标一',
      observationTypeName: '协同观测',
      observerIlluminationText: '观测星一：阴影；观测星二：阴影',
      targetIlluminationName: '阳照',
      startTime: '2026-08-12T00:01:00Z',
      endTime: '2026-08-12T00:09:00Z',
      durationSec: 480,
      satelliteSegments: [
        {
          satelliteId: '1',
          satelliteName: '观测星一',
          state: 'UMBRA',
          stateName: '本影',
          startTime: '2026-08-12T00:01:00Z',
          endTime: '2026-08-12T00:09:00Z',
          durationSec: 480,
        },
        {
          satelliteId: '2',
          satelliteName: '观测星二',
          state: 'UMBRA',
          stateName: '本影',
          startTime: '2026-08-12T00:01:00Z',
          endTime: '2026-08-12T00:09:00Z',
          durationSec: 480,
        },
      ],
    }
    const singleRow = {
      key: 'single',
      observerSatelliteNames: '观测星一',
      targetName: '目标二',
      observationTypeName: '单星观测',
      observerIlluminationText: '观测星一：阴影',
      targetIlluminationName: '阳照',
      startTime: '2026-08-12T00:02:00Z',
      endTime: '2026-08-12T00:05:00Z',
      durationSec: 180,
    }
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        plan: {
          results: [
            {
              targetId: 'target-1',
              targetName: '目标一',
              observerSatelliteIds: ['1', '2'],
              observerSatelliteNames: ['观测星一', '观测星二'],
              observationType: 'COOPERATIVE',
              observationTypeName: '共同观测',
              arcs: [
                {
                  ...combinationRow,
                  observerIlluminations: [],
                  targetIllumination: {
                    targetId: 'target-1',
                    targetName: '目标一',
                    state: 'SUNLIT',
                    stateName: '阳照',
                  },
                },
              ],
            },
            {
              targetId: 'target-2',
              targetName: '目标二',
              observerSatelliteIds: ['1'],
              observerSatelliteNames: ['观测星一'],
              observationType: 'SINGLE',
              observationTypeName: '单星观测',
              arcs: [
                {
                  ...singleRow,
                  observerIlluminations: [],
                  targetIllumination: {
                    targetId: 'target-2',
                    targetName: '目标二',
                    state: 'SUNLIT',
                    stateName: '阳照',
                  },
                },
              ],
            },
          ],
        },
      }),
      global: { plugins: [ElementUI] },
    })
    const rowClasses = wrapper.findAll('tbody tr').map((row) => row.classes())
    expect(rowClasses.some((classes) => classes.includes('combination-arc-row'))).toBe(true)
    expect(rowClasses.some((classes) => classes.includes('single-arc-row'))).toBe(true)
  })
  it('expands a combination arc into per-satellite observation segments', async () => {
    const combinationRow = {
      key: 'combo',
      observerSatelliteNames: '观测星一 + 观测星二',
      targetName: '目标一',
      observationTypeName: '协同观测',
      observerIlluminationText: '观测星一：阴影；观测星二：阴影',
      targetIlluminationName: '阳照',
      startTime: '2026-08-12T00:01:00Z',
      endTime: '2026-08-12T00:09:00Z',
      durationSec: 480,
      satelliteSegments: [
        {
          satelliteId: '1',
          satelliteName: '观测星一',
          state: 'UMBRA',
          stateName: '本影',
          startTime: '2026-08-12T00:01:00Z',
          endTime: '2026-08-12T00:09:00Z',
          durationSec: 480,
        },
        {
          satelliteId: '2',
          satelliteName: '观测星二',
          state: 'SUNLIT',
          stateName: '阳照',
          startTime: '2026-08-12T00:01:00Z',
          endTime: '2026-08-12T00:09:00Z',
          durationSec: 480,
        },
      ],
    }
    const wrapper = mount(VisibilityPlanningStep, {
      props: baseProps({
        plan: {
          results: [
            {
              targetId: 'target-1',
              targetName: '目标一',
              observerSatelliteIds: ['1', '2'],
              observerSatelliteNames: ['观测星一', '观测星二'],
              observationType: 'COOPERATIVE',
              observationTypeName: '共同观测',
              arcs: [
                {
                  ...combinationRow,
                  observerIlluminations: [],
                  targetIllumination: {
                    targetId: 'target-1',
                    targetName: '目标一',
                    state: 'SUNLIT',
                    stateName: '阳照',
                  },
                },
              ],
            },
          ],
        },
      }),
      global: { plugins: [ElementUI] },
    })
    // Element UI 表格行在挂载后异步渲染，需等两次 nextTick 后展开图标才出现
    await nextTick()
    await nextTick()
    await wrapper.get('.el-table__expand-icon').trigger('click')
    await nextTick()
    const segments = wrapper.findAll('[data-test="satellite-segment"]')
    expect(segments).toHaveLength(2)
    expect(segments[0].text()).toContain('观测星一')
    expect(segments[0].text()).toContain('本影')
    expect(segments[0].text()).toContain('480 秒')
    expect(segments[1].text()).toContain('观测星二')
    expect(segments[1].text()).toContain('阳照')
  })
})
