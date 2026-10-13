import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

vi.mock('./api/client', () => ({
  ApiRequestError: class ApiRequestError extends Error {},
  getApi: vi.fn().mockResolvedValue([]),
  postApi: vi.fn().mockResolvedValue({}),
  putApi: vi.fn().mockResolvedValue({}),
  deleteApi: vi.fn().mockResolvedValue({}),
}))

import App from './App.vue'
import { getApi, postApi } from './api/client'

describe('App', () => {
  it('does not render health status controls in the page header', () => {
    const wrapper = mount(App, {
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.find('.workspace-status').exists()).toBe(false)
    expect(wrapper.find('.workspace-toolbar').text()).not.toContain('检查服务')
  })

  it('lays out satellite selection, link planning, and target planning side by side', () => {
    const wrapper = mount(App, {
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.find('[data-test="satellite-station-selection"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="satellite-station-links"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="target-visibility-planning"]').exists()).toBe(true)
    expect(wrapper.find('.planning-workbench').classes()).toContain('planning-workbench')
    expect(wrapper.findComponent({ name: 'ElSteps' }).exists()).toBe(false)
  })

  it('uses dedicated panels for station links and observation constraints', () => {
    const wrapper = mount(App, {
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.findComponent({ name: 'SatelliteStationLinkPanel' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'ObservationConstraintPanel' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'WindowSelectionPanel' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'SatellitePayloadPanel' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'RawResponsePanel' }).exists()).toBe(false)
  })

  it('loads only the first satellite catalog page and sends target pagination parameters', async () => {
    const getApiMock = vi.mocked(getApi)
    const postApiMock = vi.mocked(postApi)
    getApiMock.mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/health': { status: 'UP' },
        '/satellites/page?pageNumber=1&pageSize=50': { total: 5414, pageNumber: 1, pageSize: 50, records: [] },
        '/stations': [],
        '/defaults': { preGuardSec: 300, postGuardSec: 0, allowedGapSec: 0 },
      }
      return Promise.resolve(responses[path]) as never
    })
    postApiMock.mockResolvedValue({ total: 25870, pageNumber: 1, pageSize: 50, targets: [], targetIds: [2, 4] })

    const wrapper = mount(App, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(getApiMock).toHaveBeenCalledWith('/satellites/page?pageNumber=1&pageSize=50')
    expect(getApiMock).not.toHaveBeenCalledWith('/satellites/options')

    await wrapper.get('[data-test="query-targets"]').trigger('click')
    await flushPromises()

    expect(postApiMock).toHaveBeenCalledWith('/targets/query', expect.objectContaining({
      pageNumber: 1,
      pageSize: 50,
    }))
    const targetQueryPayload = postApiMock.mock.calls.find(([path]) => path === '/targets/query')?.[1]
    expect(targetQueryPayload).not.toHaveProperty('epochStartTime')
    expect(targetQueryPayload).not.toHaveProperty('epochEndTime')
    expect(targetQueryPayload).not.toHaveProperty('planningStartTime')
    expect(targetQueryPayload).not.toHaveProperty('planningEndTime')
  })

  it('shows imported catalog fields and mass for satellites and targets', () => {
    const wrapper = mount(App, {
      global: { plugins: [ElementPlus] },
    })

    const columnProps = wrapper.findAllComponents({ name: 'ElTableColumn' })
      .map((column) => column.props('prop'))

    for (const field of ['noradCatId', 'rcsSize', 'weight', 'launchDate', 'catalogComment']) {
      expect(columnProps.filter((prop) => prop === field)).toHaveLength(2)
    }
  })

  it('uses the selected downlink window as the only visibility planning time range', async () => {
    const selectedSatellite = { id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }
    const getApiMock = vi.mocked(getApi)
    const postApiMock = vi.mocked(postApi)
    getApiMock.mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/health': { status: 'UP' },
        '/satellites/page?pageNumber=1&pageSize=50': { total: 1, pageNumber: 1, pageSize: 50, records: [selectedSatellite] },
        '/stations': [],
        '/defaults': { preGuardSec: 300, postGuardSec: 0, allowedGapSec: 0 },
        '/satellites/1/payload-profiles': { activePayloadType: 'SPACE', profiles: [{
          payloadType: 'SPACE', minDistanceKm: 1000, maxDistanceKm: 4000,
          azimuthMinDeg: 90, azimuthMaxDeg: 270, elevationMinDeg: -4, elevationMaxDeg: 60,
          angleRateDegPerSec: 0.3, sunAvoidHalfAngleDeg: 90, startupAngleRateDegPerSec: 0.07,
        }] },
      }
      return Promise.resolve(responses[path]) as never
    })
    postApiMock.mockImplementation((path) => (
      path === '/targets/query'
        ? Promise.resolve({ total: 1, pageNumber: 1, pageSize: 50, targets: [], targetIds: [2] }) as never
        : Promise.resolve({ results: [] }) as never
    ))

    const wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="query-targets"]').trigger('click')
    await flushPromises()

    wrapper.findComponent({ name: 'TaskSatelliteSelectionStep' }).vm.$emit('toggle', selectedSatellite, true)
    await flushPromises()
    wrapper.findComponent({ name: 'WindowSelectionPanel' }).vm.$emit(
      'selectPlanned', '2026-08-12T00:00:00Z', '2026-08-12T00:10:00Z', '下传窗口：观测卫星一号—地面站一号',
    )
    await flushPromises()
    const planningStep = wrapper.findComponent({ name: 'VisibilityPlanningStep' })
    planningStep.vm.$emit('update:mode', 'sync')
    await flushPromises()
    planningStep.vm.$emit('plan')
    await flushPromises()

    expect(postApiMock).toHaveBeenCalledWith('/visibility/plan', expect.objectContaining({
      startTime: '2026-08-12T00:00:00.000Z',
      endTime: '2026-08-12T00:10:00.000Z',
    }))
  })

  it('sends recommended illumination constraint with millisecond fine step', async () => {
    const selectedSatellite = { id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }
    const getApiMock = vi.mocked(getApi)
    const postApiMock = vi.mocked(postApi)
    getApiMock.mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/health': { status: 'UP' },
        '/satellites/page?pageNumber=1&pageSize=50': { total: 1, pageNumber: 1, pageSize: 50, records: [selectedSatellite] },
        '/stations': [],
        '/defaults': { preGuardSec: 300, postGuardSec: 0, allowedGapSec: 0 },
        '/satellites/1/payload-profiles': { activePayloadType: 'SPACE', profiles: [{
          payloadType: 'SPACE', minDistanceKm: 1000, maxDistanceKm: 4000,
          azimuthMinDeg: 90, azimuthMaxDeg: 270, elevationMinDeg: -4, elevationMaxDeg: 60,
          angleRateDegPerSec: 0.3, sunAvoidHalfAngleDeg: 90, startupAngleRateDegPerSec: null,
        }] },
      }
      return Promise.resolve(responses[path]) as never
    })
    postApiMock.mockImplementation((path) => (
      path === '/targets/query'
        ? Promise.resolve({ total: 1, pageNumber: 1, pageSize: 50, targets: [], targetIds: [2] }) as never
        : Promise.resolve({ results: [] }) as never
    ))

    const wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="query-targets"]').trigger('click')
    await flushPromises()
    wrapper.findComponent({ name: 'TaskSatelliteSelectionStep' }).vm.$emit('toggle', selectedSatellite, true)
    await flushPromises()
    wrapper.findComponent({ name: 'WindowSelectionPanel' }).vm.$emit(
      'selectPlanned', '2026-08-12T00:00:00Z', '2026-08-12T00:10:00Z', '下传窗口：观测卫星一号—地面站一号',
    )
    await flushPromises()
    const planningStep = wrapper.findComponent({ name: 'VisibilityPlanningStep' })
    planningStep.vm.$emit('update:mode', 'sync')
    await flushPromises()
    planningStep.vm.$emit('plan')
    await flushPromises()

    expect(postApiMock).toHaveBeenCalledWith('/visibility/plan', expect.objectContaining({
      coarseStepSec: 60,
      fineStepSec: 0.001,
      illuminationConstraint: {
        mode: 'ANY', observerStates: [], targetStates: [],
      },
    }))
  })

  it('runs visibility planning as a background task and loads the finished result', async () => {
    const selectedSatellite = { id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }
    const getApiMock = vi.mocked(getApi)
    const postApiMock = vi.mocked(postApi)
    getApiMock.mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/health': { status: 'UP' },
        '/satellites/page?pageNumber=1&pageSize=50': { total: 1, pageNumber: 1, pageSize: 50, records: [selectedSatellite] },
        '/stations': [],
        '/defaults': { preGuardSec: 300, postGuardSec: 0, allowedGapSec: 0 },
        '/satellites/1/payload-profiles': { activePayloadType: 'SPACE', profiles: [{
          payloadType: 'SPACE', minDistanceKm: 1000, maxDistanceKm: 4000,
          azimuthMinDeg: 90, azimuthMaxDeg: 270, elevationMinDeg: -4, elevationMaxDeg: 60,
          angleRateDegPerSec: 0.3, sunAvoidHalfAngleDeg: 90, startupAngleRateDegPerSec: 0.07,
        }] },
        '/visibility/tasks/task-1': { taskId: 'task-1', status: 'SUCCEEDED', totalPairs: 2, completedPairs: 2 },
        '/visibility/tasks/task-1/result': { results: [] },
      }
      return Promise.resolve(responses[path]) as never
    })
    postApiMock.mockImplementation((path) => (
      path === '/visibility/tasks'
        ? Promise.resolve({ taskId: 'task-1' }) as never
        : path === '/targets/query'
          ? Promise.resolve({ total: 1, pageNumber: 1, pageSize: 50, targets: [], targetIds: [2] }) as never
          : Promise.resolve({ results: [] }) as never
    ))

    const wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="query-targets"]').trigger('click')
    await flushPromises()

    wrapper.findComponent({ name: 'TaskSatelliteSelectionStep' }).vm.$emit('toggle', selectedSatellite, true)
    await flushPromises()
    wrapper.findComponent({ name: 'WindowSelectionPanel' }).vm.$emit(
      'selectPlanned', '2026-08-12T00:00:00Z', '2026-08-12T00:10:00Z', '下传窗口：观测卫星一号—地面站一号',
    )
    await flushPromises()
    const planningStep = wrapper.findComponent({ name: 'VisibilityPlanningStep' })
    planningStep.vm.$emit('update:mode', 'task')
    await flushPromises()
    planningStep.vm.$emit('plan')
    await flushPromises()

    expect(postApiMock).toHaveBeenCalledWith('/visibility/tasks', expect.objectContaining({
      startTime: '2026-08-12T00:00:00.000Z',
      endTime: '2026-08-12T00:10:00.000Z',
    }))
    expect(getApiMock).toHaveBeenCalledWith('/visibility/tasks/task-1')
    expect(getApiMock).toHaveBeenCalledWith('/visibility/tasks/task-1/result')
  })
})
