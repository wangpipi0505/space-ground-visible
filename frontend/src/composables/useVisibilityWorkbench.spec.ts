import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PayloadProfilesResponse } from '../api/types'

vi.mock('../api/client', () => ({
  ApiRequestError: class ApiRequestError extends Error {},
  getApi: vi.fn().mockResolvedValue([]),
  postApi: vi.fn().mockResolvedValue({}),
  putApi: vi.fn().mockResolvedValue({}),
  deleteApi: vi.fn().mockResolvedValue({}),
}))

import { deleteApi, getApi, postApi, putApi } from '../api/client'
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

  /** 单边清空姿态与非法数值必须在 HTTP 前拦截，不能交给 JSON.stringify 变成 null。 */
  it('阻止非法载荷保存并保护已保存配置', async () => {
    const workbench = useVisibilityWorkbench()
    workbench.toggleTaskSatellite({ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }, true)
    await vi.advanceTimersByTimeAsync(0)
    const original = JSON.stringify(workbench.satellitePayloadConfigurations.value[1])
    workbench.satellitePayloadForms[1]!.azimuthMinDeg = undefined
    await workbench.saveSatellitePayload(1)
    expect(putApi).not.toHaveBeenCalled()
    expect(JSON.stringify(workbench.satellitePayloadConfigurations.value[1])).toBe(original)
    workbench.satellitePayloadForms[1]!.azimuthMinDeg = 90
    workbench.satellitePayloadForms[1]!.minDistanceKm = Infinity
    await workbench.saveSatellitePayload(1)
    expect(putApi).not.toHaveBeenCalled()
  })

  /** 模拟持久化服务按类型更新；两次真实保存的请求必须只有十个标准字段。 */
  it('两类独立保存后切换回显原零负一与姿态空值', async () => {
    const workbench = useVisibilityWorkbench()
    workbench.toggleTaskSatellite({ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }, true)
    await vi.advanceTimersByTimeAsync(0)
    const profiles = [...configuredPayload.profiles]
    vi.mocked(putApi).mockImplementation((_path, payload) => {
      const saved = payload as PayloadProfilesResponse['profiles'][number]
      const index = profiles.findIndex((profile) => profile.payloadType === saved.payloadType)
      if (index < 0) profiles.push({ ...saved })
      else profiles[index] = { ...saved }
      return Promise.resolve({ activePayloadType: saved.payloadType, profiles: [...profiles] }) as never
    })
    Object.assign(workbench.satellitePayloadForms[1]!, {
      angleRateDegPerSec: -1, startupAngleRateDegPerSec: -1, azimuthMinDeg: null, azimuthMaxDeg: null,
    })
    await workbench.saveSatellitePayload(1)
    workbench.selectSatellitePayloadType(1, 'GROUND')
    Object.assign(workbench.satellitePayloadForms[1]!, { angleRateDegPerSec: 0, minDistanceKm: 800 })
    await workbench.saveSatellitePayload(1)
    workbench.selectSatellitePayloadType(1, 'SPACE')
    expect(workbench.satellitePayloadForms[1]).toMatchObject({ angleRateDegPerSec: -1, startupAngleRateDegPerSec: -1, azimuthMinDeg: null, azimuthMaxDeg: null, minDistanceKm: 1000 })
    workbench.selectSatellitePayloadType(1, 'GROUND')
    expect(workbench.satellitePayloadForms[1]).toMatchObject({ angleRateDegPerSec: 0, minDistanceKm: 800, elevationMinDeg: -91 })
    expect(Object.keys(vi.mocked(putApi).mock.calls[0]![1] as object)).toHaveLength(10)
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

  /** 仅读历史米字段且保留缺省姿态响应；标准保存明确成对 null，不混发米/公里或只读标记。 */
  it('旧米profile读取换算且保存只发送标准公里字段', async () => {
    vi.mocked(getApi).mockResolvedValue({ activePayloadType: 'SPACE', profiles: [{
      payloadType: 'SPACE', minDistanceM: 100000, maxDistanceM: 8000000,
      angleRateDegPerSec: 0, sunAvoidHalfAngleDeg: 0,
    }] } as never)
    const workbench = useVisibilityWorkbench()
    workbench.toggleTaskSatellite({ id: 1, name: '旧星', modelType: 'KEPLER_6' }, true)
    await vi.advanceTimersByTimeAsync(0)
    expect(workbench.satellitePayloadForms[1]).toMatchObject({ minDistanceKm: 100, maxDistanceKm: 8000, minDistanceM: 100000, maxDistanceM: 8000000 })
    vi.mocked(putApi).mockResolvedValue({ activePayloadType: 'SPACE', profiles: configuredPayload.profiles } as never)
    await workbench.saveSatellitePayload(1)
    expect(putApi).toHaveBeenCalledWith('/satellites/1/payload-profiles', {
      payloadType: 'SPACE', minDistanceKm: 100, maxDistanceKm: 8000,
      azimuthMinDeg: null, azimuthMaxDeg: null, elevationMinDeg: null, elevationMaxDeg: null,
      angleRateDegPerSec: 0, sunAvoidHalfAngleDeg: 0, startupAngleRateDegPerSec: null,
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
