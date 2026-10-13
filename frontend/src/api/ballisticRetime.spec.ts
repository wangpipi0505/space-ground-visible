import { afterEach, describe, expect, it, vi } from 'vitest'
import { retimeBallisticTrajectory } from './ballisticRetime'
import { uploadBallisticFile } from './ballisticFiles'

/** 原四个Web字段与原字节/错误；时间与null直接提交，路径只能由页面传真实服务引用。 */
describe('T25 重定时原HTTP合同', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('上传200不能冒充201真实引用', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"fileId":"假引用"}', { status: 200 })))
    await expect(uploadBallisticFile(new File(['CSV'], '原.csv'))).rejects.toThrow('201')
  })
  it('四字段同值且原响应字节不包装', async () => {
    const raw = '{ "taskName": "重定时", "targetCount": 0, "files": [] }\n'
    const fetchMock = vi.fn().mockResolvedValue(new Response(raw)); vi.stubGlobal('fetch', fetchMock)
    const request = { taskName: null, targetId: null, trajectoryCsvPath: 'server/原.csv', launchTimeUtc: '2026-07-02 23:59:59.375' }
    const result = await retimeBallisticTrajectory(request)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/trajectory/retime')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual(request)
    expect(result.rawText).toBe(raw); expect(new TextDecoder().decode(result.rawBytes)).toBe(raw)
  })
  it('原400与500全文保留', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('{"error":"IllegalArgumentException: 原UTC无效"}', { status: 400 })).mockResolvedValueOnce(new Response('{"error":"IOException: 原读取失败"}', { status: 500 })))
    await expect(retimeBallisticTrajectory({ trajectoryCsvPath: 'server/原.csv', launchTimeUtc: '非法' })).rejects.toThrow('IllegalArgumentException: 原UTC无效')
    await expect(retimeBallisticTrajectory({ trajectoryCsvPath: 'server/原.csv', launchTimeUtc: null })).rejects.toThrow('IOException: 原读取失败')
  })
})
