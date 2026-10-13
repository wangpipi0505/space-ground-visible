import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PayloadProfilesResponse } from '../api/types'

vi.mock('../api/client', () => ({
  ApiRequestError: class ApiRequestError extends Error {},
  getApi: vi.fn().mockResolvedValue([]),
  postApi: vi.fn().mockResolvedValue({}),
  putApi: vi.fn().mockResolvedValue({}),
  deleteApi: vi.fn().mockResolvedValue({}),
}))

import { deleteApi, getApi, postApi } from '../api/client'
import { useVisibilityWorkbench } from './useVisibilityWorkbench'

/** 已按新载荷参数完成配置的卫星载荷响应。 */
const configuredPayload: PayloadProfilesResponse = {
  activePayloadType: 'SPACE',
  profiles: [{
    payloadType: 'SPACE',
    minDistanceKm: 1000,
    maxDistanceKm: 4000,
    azimuthMinDeg: 90,
    azimuthMaxDeg: 270,
    elevationMinDeg: -4,
    elevationMaxDeg: 60,
    angleRateDegPerSec: 0.3,
    sunAvoidHalfAngleDeg: 90,
    startupAngleRateDegPerSec: 0.07,
  }],
}

/** 选中卫星、保存筛选快照并指定观测窗口，使可见性规划满足前置校验。 */
async function preparePlanning(workbench: ReturnType<typeof useVisibilityWorkbench>, targetIds = [2]): Promise<void> {
  workbench.toggleTaskSatellite({ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }, true)
  await vi.advanceTimersByTimeAsync(0)
  workbench.selectedTargetIds.value = targetIds
  workbench.selectPlannedWindow('2026-08-12T00:00:00Z', '2026-08-12T00:10:00Z', '下传窗口：观测卫星一号—地面站一号')
}

describe('useVisibilityWorkbench', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(getApi).mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/satellites/1/payload-profiles': configuredPayload,
      }
      return Promise.resolve(responses[path]) as never
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  /** 旧记录接口的无后缀时间也是 UTC；北京浏览器不能再把它解释为本地时间。 */
  it('按UTC解析无后缀历史时刻，保留Z与显式偏移的原时刻', () => {
    const workbench = useVisibilityWorkbench()
    const utc = workbench.formatTime('2026-07-01T10:00:00.000Z')
    expect(workbench.formatTime('2026-07-01T10:00:00.000')).toBe(utc)
    expect(workbench.formatTime('2026-07-01T18:00:00.000+08:00')).toBe(utc)
    expect(utc).toContain('18:00:00.000')
  })

  it('maps the new payload fields into the editable form', async () => {
    const workbench = useVisibilityWorkbench()

    workbench.toggleTaskSatellite({ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }, true)
    await vi.advanceTimersByTimeAsync(0)

    expect(workbench.satellitePayloadForms[1]).toEqual({
      payloadType: 'SPACE',
      minDistanceKm: 1000,
      maxDistanceKm: 4000,
      azimuthMinDeg: 90,
      azimuthMaxDeg: 270,
      elevationMinDeg: -4,
      elevationMaxDeg: 60,
      angleRateDegPerSec: 0.3,
      sunAvoidHalfAngleDeg: 90,
      startupAngleRateDegPerSec: 0.07,
    })
  })

  it('maps the document ground profile into the same editable fields without persisting its legacy marker', async () => {
    const groundPayload: PayloadProfilesResponse = {
      activePayloadType: 'GROUND',
      profiles: [{
        payloadType: 'GROUND',
        legacyGroundProfile: false,
        minDistanceKm: 800,
        maxDistanceKm: 3500,
        elevationMinDeg: -91,
        elevationMaxDeg: 0,
        angleRateDegPerSec: 1,
        sunAvoidHalfAngleDeg: 45,
        startupAngleRateDegPerSec: 1,
      }],
    }
    vi.mocked(getApi).mockResolvedValue(groundPayload as never)
    const workbench = useVisibilityWorkbench()

    workbench.toggleTaskSatellite({ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }, true)
    await vi.advanceTimersByTimeAsync(0)

    expect(workbench.satellitePayloadForms[1]).toEqual({
      payloadType: 'GROUND',
      minDistanceKm: 800,
      maxDistanceKm: 3500,
      elevationMinDeg: -91,
      elevationMaxDeg: 0,
      angleRateDegPerSec: 1,
      sunAvoidHalfAngleDeg: 45,
      startupAngleRateDegPerSec: 1,
    })
  })

  it('submits the selected target IDs without repeating target filters during planning', async () => {
    const workbench = useVisibilityWorkbench()
    workbench.targetForm.minTleEpochCode = '23340'
    workbench.targetForm.maxApproximationDays = 14
    vi.mocked(postApi).mockResolvedValueOnce({
      total: 2, pageNumber: 1, pageSize: 50, targets: [], targetIds: [2, 4],
    } as never)

    await workbench.queryTargets()

    expect(vi.mocked(postApi)).toHaveBeenCalledWith('/targets/query', expect.objectContaining({
      minTleEpochCode: '23340',
    }))
    const targetQueryPayload = vi.mocked(postApi).mock.calls[0][1] as Record<string, unknown>
    expect(targetQueryPayload).not.toHaveProperty('planningStartTime')
    expect(targetQueryPayload).not.toHaveProperty('planningEndTime')
    expect(targetQueryPayload).not.toHaveProperty('maxApproximationDays')
    expect(workbench.selectedTargetIds.value).toEqual([2, 4])

    await preparePlanning(workbench, [2, 4])
    workbench.visibilityPlanMode.value = 'sync'
    await workbench.runVisibilityPlan()

    expect(vi.mocked(postApi)).toHaveBeenCalledWith('/visibility/plan', expect.objectContaining({
      targetIds: [2, 4], maxApproximationDays: 14,
    }))
    const visibilityPlanPayload = vi.mocked(postApi).mock.calls[1][1] as Record<string, unknown>
    expect(visibilityPlanPayload).not.toHaveProperty('belongs')
    expect(visibilityPlanPayload).not.toHaveProperty('minWeight')
    expect(visibilityPlanPayload).not.toHaveProperty('minTleEpochCode')
    expect(visibilityPlanPayload).not.toHaveProperty('excludeNameRegex')
  })

  it('submits a background task, polls progress and loads the result', async () => {
    const taskResponses: Record<string, unknown> = {
      '/visibility/tasks/task-1': { taskId: 'task-1', status: 'RUNNING', totalPairs: 4, completedPairs: 1 },
      '/visibility/tasks/task-1/result': { results: [] },
    }
    vi.mocked(getApi).mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/satellites/1/payload-profiles': configuredPayload,
        ...taskResponses,
      }
      return Promise.resolve(responses[path]) as never
    })
    const workbench = useVisibilityWorkbench()
    await preparePlanning(workbench)
    workbench.visibilityPlanMode.value = 'task'

    vi.mocked(postApi).mockResolvedValue({ taskId: 'task-1' } as never)

    await workbench.runVisibilityPlan()
    await vi.advanceTimersByTimeAsync(0)

    expect(workbench.visibilityTask.value).toMatchObject({ status: 'RUNNING', completedPairs: 1, totalPairs: 4 })
    expect(workbench.visibilityTaskProgressPercent.value).toBe(25)
    expect(workbench.visibilityTaskRunning.value).toBe(true)

    taskResponses['/visibility/tasks/task-1'] = { taskId: 'task-1', status: 'SUCCEEDED', totalPairs: 4, completedPairs: 4 }
    await vi.advanceTimersByTimeAsync(2000)

    expect(workbench.visibilityPlan.value).toEqual({ results: [] })
    expect(workbench.visibilityTaskRunning.value).toBe(false)
    expect(vi.mocked(getApi)).toHaveBeenCalledWith('/visibility/tasks/task-1/result')
  })

  it('stops polling and surfaces the error message when the task fails', async () => {
    vi.mocked(getApi).mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/satellites/1/payload-profiles': configuredPayload,
        '/visibility/tasks/task-1': {
          taskId: 'task-1', status: 'FAILED', totalPairs: 4, completedPairs: 2, errorMessage: '轨道数据缺失',
        },
      }
      return Promise.resolve(responses[path]) as never
    })
    const workbench = useVisibilityWorkbench()
    await preparePlanning(workbench)
    workbench.visibilityPlanMode.value = 'task'
    vi.mocked(postApi).mockResolvedValue({ taskId: 'task-1' } as never)

    await workbench.runVisibilityPlan()
    await vi.advanceTimersByTimeAsync(0)

    expect(workbench.visibilityTask.value).toMatchObject({ status: 'FAILED', errorMessage: '轨道数据缺失' })
    expect(workbench.visibilityTaskRunning.value).toBe(false)
    expect(workbench.visibilityPlan.value).toBeNull()
  })

  it('cancels a running background task and stops polling', async () => {
    vi.mocked(getApi).mockImplementation((path) => {
      const responses: Record<string, unknown> = {
        '/satellites/1/payload-profiles': configuredPayload,
        '/visibility/tasks/task-1': { taskId: 'task-1', status: 'RUNNING', totalPairs: 4, completedPairs: 1 },
      }
      return Promise.resolve(responses[path]) as never
    })
    const workbench = useVisibilityWorkbench()
    await preparePlanning(workbench)
    workbench.visibilityPlanMode.value = 'task'
    vi.mocked(postApi).mockResolvedValue({ taskId: 'task-1' } as never)

    await workbench.runVisibilityPlan()
    await vi.advanceTimersByTimeAsync(0)
    expect(workbench.visibilityTaskRunning.value).toBe(true)

    await workbench.cancelVisibilityTaskPlan()
    await vi.advanceTimersByTimeAsync(0)

    expect(vi.mocked(deleteApi)).toHaveBeenCalledWith('/visibility/tasks/task-1')
    expect(workbench.visibilityTask.value).toMatchObject({ status: 'CANCELLED' })
    expect(workbench.visibilityTaskRunning.value).toBe(false)
  })
})
