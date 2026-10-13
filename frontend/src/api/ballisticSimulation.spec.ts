import { afterEach, describe, expect, it, vi } from 'vitest'
import { simulateBallisticTrajectory } from './ballisticSimulation'
import { registerBallisticResult } from './ballisticFiles'

/** typed原路由、中文错误和响应原字节；辅助登记必须为实际201才拥有可选引用。 */
describe('T24 原模拟HTTP与登记边界', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('原路径、原值和完整HTTP字节保留，无包装/model字段', async () => {
    const raw = '{ "taskName": "模拟", "targetCount": 0, "files": [] }\n'
    const fetchMock = vi.fn().mockResolvedValue(new Response(raw)); vi.stubGlobal('fetch', fetchMock)
    const request = { taskName: null, launchTimeUtc: '2026-06-04 04:00:00.000', outputPointDurationSec: 0, outputStepSec: 2.5, phaseEvents: null, initialSpeedMs: 1e20 }
    const response = await simulateBallisticTrajectory(request)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/trajectory/simulate')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual(request)
    expect(response.rawText).toBe(raw); expect(new TextDecoder().decode(response.rawBytes)).toBe(raw)
  })
  it('原400中文不包新错误，200登记不能冒充201引用', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('{"error":"IllegalArgumentException: 原坐标无效"}', { status: 400 })).mockResolvedValueOnce(new Response('{"fileId":"伪成功"}', { status: 200 })))
    await expect(simulateBallisticTrajectory({})).rejects.toThrow('IllegalArgumentException: 原坐标无效')
    await expect(registerBallisticResult('原.csv', 'task', 'id')).rejects.toThrow('201')
  })
})
