import { afterEach, describe, expect, it, vi } from 'vitest'
import { uploadBallisticFile, downloadBallisticFile, previewBallisticFile } from './ballisticFiles'
import { searchBallisticWindows } from './ballisticWindow'
import { validateWindowRequest, emptySatelliteOrbit, applyReferenceOrbit } from '../utils/ballisticWindow'
import { createDefaultPayload } from '../utils/standardPayload'
/** 真实 HTTP 封装/校验；固定期望来自 T20 DTO，不以待测实现产生黄金。 */
describe('T22 原文件与窗口 HTTP/协议', () => {
  afterEach(() => vi.unstubAllGlobals())
  function request() {
    return {
      ballistics: [
        {
          targetId: 'T1',
          targetName: '甲',
          csvPath: 'https://example.org/a.csv',
          baseLaunchUTC: '2026/07/01 07:57:10.000',
        },
      ],
      satellites: [
        {
          satelliteId: 'S1',
          name: '星甲',
          ...applyReferenceOrbit(),
          payloads: [{ payloadId: 'P1', payloadName: '载荷甲', ...createDefaultPayload() }],
        },
      ],
      startOffsetSec: -1200,
      stepSizeSec: 10,
      numCalculations: 3,
      targetSampleStepSec: 1,
      trajectoryPointOutput: 1,
      orbitModel: 'J2_NUMERICAL',
    }
  }
  it('上传真实FormData不写JSON头；预览分页及下载中文原名/字节', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            fileId: 'f1',
            serverPath: '/managed/f1.csv',
            originalName: '弹道甲.csv',
            sizeBytes: 4,
            contentType: 'text/csv',
          }),
          { status: 201 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ header: ['原表头'], rows: [['1']], totalRows: 1, page: 2, limit: 50, metadata: {} }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(new Uint8Array([0, 10, 128, 255]), {
          headers: {
            'Content-Disposition': "attachment; filename=_.csv; filename*=UTF-8''%E5%BC%B9%E9%81%93%E7%94%B2.csv",
          },
        }),
      )
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['abcd'], '弹道甲.csv', { type: 'text/csv' })
    expect((await uploadBallisticFile(file)).serverPath).toBe('/managed/f1.csv')
    const options = fetchMock.mock.calls[0][1]
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file')).toBe(file)
    expect(options.headers).toBeUndefined()
    await previewBallisticFile('f1', 2)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/ballistic/files/f1/preview?page=2&limit=50')
    const result = await downloadBallisticFile('f1')
    expect(result.filename).toBe('弹道甲.csv')
    expect([...new Uint8Array(await result.blob.arrayBuffer())]).toEqual([0, 10, 128, 255])
  })
  it('原计算路径独立且原响应空白/数值/字节不重新序列化，失败下载不返回错误文件', async () => {
    const raw = '{\n "referenceUtc":"UTC", "best":null, "allResults":[], "v":1.230000\n}\n'
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(raw))
      .mockResolvedValueOnce(new Response('{"error":"文件不存在"}', { status: 404 }))
    vi.stubGlobal('fetch', fetchMock)
    const result = await searchBallisticWindows(request())
    expect(fetchMock.mock.calls[0][0]).toBe('/api/orbit/visibility/search')
    expect(result.rawText).toBe(raw)
    expect(new TextDecoder().decode(result.rawBytes)).toBe(raw)
    expect(JSON.parse(String(fetchMock.mock.calls[0][1].body)).ballistics[0].baseLaunchUTC).toBe(
      '2026/07/01 07:57:10.000',
    )
    await expect(downloadBallisticFile('missing')).rejects.toThrow('文件不存在')
  })
  it('空根数不伪造，负起点合法；有限参数/安全整数/UTC/本机路径/标准载荷中文定位', () => {
    expect(emptySatelliteOrbit().semiMajorAxisM).toBeNull()
    const value = request()
    expect(validateWindowRequest(value)).toEqual([])
    value.startOffsetSec = Number.MAX_SAFE_INTEGER + 1
    value.stepSizeSec = 0
    value.satellites[0].inclinationDeg = Infinity
    value.satellites[0].epochUtc = '2026/02/30 10:00:00.000'
    value.ballistics[0].csvPath = 'C:\\fakepath\\a.csv'
    value.satellites[0].payloads[0].minDistanceKm = -1
    const errors = validateWindowRequest(value).join('；')
    for (const word of ['安全整数', '步长', '星甲', 'UTC', '甲', '载荷甲']) expect(errors).toContain(word)
  })
  it('Windows上传返回精确路径可信且不猜storage目录，末偏移BigInt边界不舍入', () => {
    const value = request()
    value.ballistics[0].csvPath = 'G:\\隔离运行\\自定义文件根\\登记文件.csv'
    expect(validateWindowRequest(value).join('；')).toContain('本机路径')
    expect(validateWindowRequest(value, new Set([value.ballistics[0].csvPath]))).toEqual([])
    value.ballistics[0].csvPath = 'C:\\fakepath\\storage\\假.csv'
    expect(validateWindowRequest(value, new Set(['G:\\隔离运行\\自定义文件根\\登记文件.csv'])).join('；')).toContain(
      '本机路径',
    )
    value.ballistics[0].csvPath = 'https://example.org/a.csv'
    value.startOffsetSec = Number.MAX_SAFE_INTEGER
    value.stepSizeSec = 1
    value.numCalculations = 2
    expect(validateWindowRequest(value)).toContain('末次偏移超过浏览器安全整数范围')
    value.numCalculations = 1
    expect(validateWindowRequest(value)).toEqual([])
  })
  it('空间/辅助/计算独立部署根保持各自完整含义', async () => {
    vi.resetModules()
    vi.stubEnv('VITE_API_BASE_URL', 'https://space.example/api/v1/space')
    vi.stubEnv('VITE_BALLISTIC_API_BASE_URL', 'https://files.example/api/v1/ballistic/')
    vi.stubEnv('VITE_BALLISTIC_COMPUTE_BASE_URL', 'https://compute.example/api/')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('{"best":null,"allResults":[]}'))
      .mockResolvedValueOnce(new Response('{}', { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    const isolatedCompute = await import('./ballisticWindow')
    const isolatedFiles = await import('./ballisticFiles')
    await isolatedCompute.searchBallisticWindows(request())
    await isolatedFiles.uploadBallisticFile(new File(['x'], 'a.csv'))
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      'https://compute.example/api/orbit/visibility/search',
      'https://files.example/api/v1/ballistic/files',
    ])
    vi.unstubAllEnvs()
    vi.resetModules()
  })
})
