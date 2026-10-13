import { describe, expect, it } from 'vitest'
import {
  collectSatelliteObservationStats,
  formatAzimuthRange,
  mergeTimeSegments,
  satelliteLetter,
  toWinToolAzimuth,
} from './winToolSummary'

describe('satelliteLetter', () => {
  it('extracts the trailing uppercase letter from the last parenthetical', () => {
    // 库内任务卫星命名为 "SHIYAN 20A (SY-20A)",字母取括号内容末位大写字母
    expect(satelliteLetter('SHIYAN 20A (SY-20A)')).toBe('A')
    expect(satelliteLetter('SHIYAN 20B (SY-20B)')).toBe('B')
    expect(satelliteLetter('SHIYAN 20C (SY-20C)')).toBe('C')
    // 兼容旧的 "(A)" 单字母风格
    expect(satelliteLetter('OBSERVER (A)')).toBe('A')
  })

  it('returns null when the name has no trailing letter', () => {
    expect(satelliteLetter('SHIYAN 19 (SY-19)')).toBeNull()
    expect(satelliteLetter('SHIYAN 20A DEB')).toBeNull()
    expect(satelliteLetter('')).toBeNull()
  })
})

describe('toWinToolAzimuth', () => {
  it('converts A with +180 normalized to [-180,180) and B/C with mirror', () => {
    // A:我方方位 252.8915 → +180 后越界回绕 → 72.8915(与 WinTool 基准一致)
    expect(toWinToolAzimuth(252.8915, 'A')).toBeCloseTo(72.8915, 6)
    // 10 + 180 = 190,归一到 [-180,180) 后为 -170
    expect(toWinToolAzimuth(10, 'A')).toBe(-170)
    // B/C:360 − 我方方位
    expect(toWinToolAzimuth(318.3553, 'B')).toBeCloseTo(41.6447, 6)
    expect(toWinToolAzimuth(60.8961, null)).toBeCloseTo(299.1039, 6)
  })
})

describe('formatAzimuthRange', () => {
  it('keeps ascending order after conversion', () => {
    const range = formatAzimuthRange(252.8915, 267.1627, 'A')
    expect(range.text).toContain('72.8915')
    expect(range.text).toContain('87.1627')
  })
})

describe('mergeTimeSegments', () => {
  it('merges overlapping and adjacent segments by union', () => {
    const merged = mergeTimeSegments([
      { startTime: '2026-09-01T16:00:00Z', endTime: '2026-09-01T16:10:00Z', durationSec: 600 },
      { startTime: '2026-09-01T16:05:00Z', endTime: '2026-09-01T16:20:00Z', durationSec: 900 },
      { startTime: '2026-09-01T16:25:00Z', endTime: '2026-09-01T16:30:00Z', durationSec: 300 },
    ])
    expect(merged).toHaveLength(2)
    expect(merged[0].endTime).toBe('2026-09-01T16:20:00Z')
    expect(merged[0].durationSec).toBe(1200)
  })
})

describe('collectSatelliteObservationStats', () => {
  it('aggregates per satellite and dedupes identical arcs across constraints', () => {
    const arc = {
      startTime: '2026-09-01T16:24:34Z', endTime: '2026-09-01T16:38:12Z', durationSec: 818,
      satelliteSegments: [{
        satelliteId: '2068', satelliteName: 'SHIYAN 20A (SY-20A)',
        startTime: '2026-09-01T16:24:34Z', endTime: '2026-09-01T16:38:12Z', durationSec: 818,
        statistics: {
          minDistanceKm: 2150.12, maxDistanceKm: 2597.50,
          minAzimuthDeg: 252.8915, maxAzimuthDeg: 267.1627,
          minElevationDeg: 3.9410, maxElevationDeg: 8.6979,
          maxAngleRateDegPerSec: 0.0394427,
        },
      }],
    }
    const aggregates = collectSatelliteObservationStats([arc, { ...arc }])
    expect(aggregates).toHaveLength(1)
    expect(aggregates[0].letter).toBe('A')
    // 同一弧段在两条组合约束里重复返回时,统计只累计一次
    expect(aggregates[0].totalSec).toBe(818)
    expect(aggregates[0].minDistanceKm).toBe(2150.12)
  })
})
