import { afterEach, describe, expect, it, vi } from 'vitest'
import { fitBallisticPackage102 } from './ballisticFit'
import { registerBallisticResult } from './ballisticFiles'

/** 原计算响应不包装fileId；辅助登记单独请求且保留HTTP错误。 */
describe('T23 原拟合与登记HTTP', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('原字段原值发送，原响应字节保留，201登记返回独立引用', async () => {
    const raw = '{ "taskName":"中文", "targetCount":1,"files":[{"targetId":"甲","model":"ORBITAL_TWO_BODY","csvPath":"G:/服务/甲.csv","pointCount":31,"warnings":["原警告"]}] }'
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(raw)).mockResolvedValueOnce(new Response('{"fileId":"f1","serverPath":"G:/服务/甲.csv"}', { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    const input = { package102: [{ tack3d_time_code: '20260701075700001', tack3d_tgt_pos_x: '001', prop_tgt_type_8: '0x02' }], taskStartTime: '2026-07-01 07:56:55.000', interpolationStepSec: .5 }
    const result = await fitBallisticPackage102(input)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/trajectory/fit/package102')
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1].body)).toEqual(input)
    expect(result.rawText).toBe(raw)
    expect(new TextDecoder().decode(result.rawBytes)).toBe(raw)
    expect(result.response.files[0]).not.toHaveProperty('fileId')
    expect((await registerBallisticResult(result.response.files[0]!.csvPath, '中文', '甲')).fileId).toBe('f1')
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1].body)).toEqual({ csvPath: 'G:/服务/甲.csv', taskName: '中文', targetId: '甲' })
  })
  it('原400错误中文转呈', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response('{"error":"无有效观测点"}', { status: 400 }))))
    await expect(fitBallisticPackage102({ package102: [{}] })).rejects.toThrow('无有效观测点')
    await expect(registerBallisticResult('x', 'task', 'a')).rejects.toThrow('无有效观测点')
  })
})
