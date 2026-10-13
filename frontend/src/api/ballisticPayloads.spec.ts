import { afterEach, describe, expect, it, vi } from 'vitest'
import { previewBallisticPayload } from './ballisticPayloads'
import { createDefaultPayload } from '../utils/standardPayload'

describe('弹道载荷独立API', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.resetModules() })

  it('默认目标为完整ballistic路径且保留身份与原值', async () => {
    const payload = { payloadId: 'P-1', payloadName: '跟踪', ...createDefaultPayload(), angleRateDegPerSec: -1 }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(payload)))
    vi.stubGlobal('fetch', fetchMock)
    expect(await previewBallisticPayload(payload)).toEqual(payload)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/ballistic/payloads/preview', expect.objectContaining({ method: 'POST', body: JSON.stringify(payload) }))
  })

  it('独立部署base不受空间base影响并显示400中文错误', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://space.example/api/v1/space')
    vi.stubEnv('VITE_BALLISTIC_API_BASE_URL', 'https://ballistic.example/api/v1/ballistic/')
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'INVALID_ARGUMENT', error: '方位角上下限必须成对' }), { status: 400 }))
    vi.stubGlobal('fetch', fetchMock)
    const { previewBallisticPayload: isolatedPreview } = await import('./ballisticPayloads')
    await expect(isolatedPreview({ payloadId: 'P', payloadName: '名', ...createDefaultPayload() })).rejects.toThrow('方位角上下限必须成对')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('https://ballistic.example/api/v1/ballistic/payloads/preview')
  })
})
