/**
 * WinTool 格式摘要的纯函数工具:时间区间并集、方位口径换算、逐星观测聚合与摘要视图构建。
 * 全部为无副作用纯函数,便于单测;组件层只负责渲染。
 */

export interface MergedTimeSegment {
  startTime: string
  endTime: string
  durationSec: number
}

/** 目标展示字母:取名称尾部括号内的末位大写字母(如 "(SY-20A)" → A、"(A)" → A;"(SY-19)" 无尾字母 → null)。 */
export function satelliteLetter(satelliteName: string): string | null {
  const parenthetical = /\(([^()]*)\)\s*$/.exec(satelliteName.trim())
  if (!parenthetical) return null
  const tailLetter = /[A-Z]$/.exec(parenthetical[1].trim())
  return tailLetter ? tailLetter[0] : null
}

/** A 星方位 = 我方方位 + 180°(归一 [-180,180));B/C 星方位 = 360° − 我方方位(0~360)。 */
export function toWinToolAzimuth(rawAzimuthDeg: number, letter: string | null): number {
  if (letter === 'A') {
    const converted = rawAzimuthDeg + 180.0
    return converted >= 180.0 ? converted - 360.0 : converted
  }
  const mirrored = 360.0 - rawAzimuthDeg
  return mirrored >= 360.0 ? mirrored - 360.0 : mirrored
}

export interface MergedTimeSegmentInput {
  startTime: string
  endTime: string
  durationSec: number
}

/** 把按起点升序的区间做并集合并(重叠/相接并入),与下传合并弧段同一套规则。 */
export function mergeTimeSegments(segments: MergedTimeSegmentInput[]): MergedTimeSegment[] {
  const ordered = [...segments].sort(
    (left, right) => Date.parse(left.startTime) - Date.parse(right.startTime),
  )
  const merged: MergedTimeSegment[] = []
  for (const segment of ordered) {
    const last = merged[merged.length - 1]
    if (last && Date.parse(segment.startTime) <= Date.parse(last.endTime)) {
      if (Date.parse(segment.endTime) > Date.parse(last.endTime)) {
        last.endTime = segment.endTime
      }
      last.durationSec = (Date.parse(last.endTime) - Date.parse(last.startTime)) / 1000.0
      continue
    }
    merged.push({ ...segment })
  }
  return merged
}

/** 观测弧段的最小形状;组件层的 ArcRow/后端弧段均满足该形状。 */
export interface ObservationArcInput {
  startTime: string
  endTime: string
  durationSec: number
  satelliteSegments?: Array<{
    satelliteId: string
    satelliteName: string
    startTime: string
    endTime: string
    durationSec: number
    statistics?: {
      minDistanceKm?: number | null
      maxDistanceKm?: number | null
      minAzimuthDeg?: number | null
      maxAzimuthDeg?: number | null
      minElevationDeg?: number | null
      maxElevationDeg?: number | null
      maxAngleRateDegPerSec?: number | null
    } | null
  }>
}

export interface SatelliteObservationAggregate {
  satelliteId: string
  satelliteName: string
  letter: string | null
  windows: string[]
  firstStart: string | null
  lastEnd: string | null
  totalSec: number
  minDistanceKm: number
  maxDistanceKm: number
  minAzimuthDeg: number
  maxAzimuthDeg: number
  minElevationDeg: number
  maxElevationDeg: number
  maxAngleRateDegPerSec: number
}

/**
 * 逐星观测聚合:把一组弧段里同一颗卫星的时段与统计极值归并。
 * 相同起止的重复弧段(不同组合约束各自返回同一窗口)按 key 去重,不会重复累计。
 */
export function collectSatelliteObservationStats(
  arcs: ObservationArcInput[],
): SatelliteObservationAggregate[] {
  const bySatellite = new Map<string, SatelliteObservationAggregate>()
  const seenArcKeys = new Set<string>()
  for (const arc of arcs) {
    for (const segment of arc.satelliteSegments ?? []) {
      const arcKey = `${segment.satelliteId}@${segment.startTime}@${segment.endTime}`
      if (seenArcKeys.has(arcKey)) continue
      seenArcKeys.add(arcKey)
      let entry = bySatellite.get(segment.satelliteName)
      if (!entry) {
        entry = {
          satelliteId: segment.satelliteId,
          satelliteName: segment.satelliteName,
          letter: satelliteLetter(segment.satelliteName),
          windows: [],
          firstStart: null,
          lastEnd: null,
          totalSec: 0,
          minDistanceKm: Number.POSITIVE_INFINITY,
          maxDistanceKm: Number.NEGATIVE_INFINITY,
          minAzimuthDeg: Number.POSITIVE_INFINITY,
          maxAzimuthDeg: Number.NEGATIVE_INFINITY,
          minElevationDeg: Number.POSITIVE_INFINITY,
          maxElevationDeg: Number.NEGATIVE_INFINITY,
          maxAngleRateDegPerSec: 0,
        }
        bySatellite.set(segment.satelliteName, entry)
      }
      entry.totalSec += segment.durationSec
      entry.windows.push(`${formatIsoToUtcText(segment.startTime)}~${formatIsoToUtcText(segment.endTime)}(${segment.durationSec.toFixed(3)}s)`)
      if (!entry.firstStart || Date.parse(segment.startTime) < Date.parse(entry.firstStart)) {
        entry.firstStart = segment.startTime
      }
      if (!entry.lastEnd || Date.parse(segment.endTime) > Date.parse(entry.lastEnd)) {
        entry.lastEnd = segment.endTime
      }
      const stats = segment.statistics
      if (stats != null) {
        const { minDistanceKm, maxDistanceKm, minAzimuthDeg, maxAzimuthDeg, minElevationDeg, maxElevationDeg, maxAngleRateDegPerSec } = stats
        if (minDistanceKm != null && maxDistanceKm != null) {
          entry.minDistanceKm = Math.min(entry.minDistanceKm, minDistanceKm)
          entry.maxDistanceKm = Math.max(entry.maxDistanceKm, maxDistanceKm)
        }
        if (minAzimuthDeg != null && maxAzimuthDeg != null) {
          entry.minAzimuthDeg = Math.min(entry.minAzimuthDeg, minAzimuthDeg)
          entry.maxAzimuthDeg = Math.max(entry.maxAzimuthDeg, maxAzimuthDeg)
        }
        if (minElevationDeg != null && maxElevationDeg != null) {
          entry.minElevationDeg = Math.min(entry.minElevationDeg, minElevationDeg)
          entry.maxElevationDeg = Math.max(entry.maxElevationDeg, maxElevationDeg)
        }
        if (maxAngleRateDegPerSec != null) {
          entry.maxAngleRateDegPerSec = Math.max(entry.maxAngleRateDegPerSec, maxAngleRateDegPerSec)
        }
      }
    }
  }
  return [...bySatellite.values()].sort((left, right) => (left.letter ?? '').localeCompare(right.letter ?? ''))
}

/** 方位区间换算为 WinTool 口径;跨 0°(换算后首尾差 > 180°)时保持原始顺序,避免把短弧误表达为长弧。 */
export function formatAzimuthRange(
  minAzimuthDeg: number,
  maxAzimuthDeg: number,
  letter: string | null,
): { text: string; crossesZero: boolean } {
  const first = toWinToolAzimuth(minAzimuthDeg, letter)
  const second = toWinToolAzimuth(maxAzimuthDeg, letter)
  if (Math.abs(first - second) > 180.0) {
    return { text: `${Math.min(first, second).toFixed(4)} ~ ${Math.max(first, second).toFixed(4)} 度(跨 0°)`, crossesZero: true }
  }
  const low = Math.min(first, second)
  const high = Math.max(first, second)
  return { text: `${low.toFixed(4)} ~ ${high.toFixed(4)} 度`, crossesZero: false }
}

/** ISO 时刻转 UTC 文本(秒精度,Z 结尾),与 WinTool 文档一致。 */
export function formatIsoToUtcText(time: string): string {
  const parsed = Date.parse(time)
  if (Number.isNaN(parsed)) return time
  return new Date(parsed).toISOString().replace(/\.\d{3}Z$/, 'Z')
}

export function formatNumber(value: number, digits: number): string {
  return Number.isFinite(value) ? value.toFixed(digits) : '—'
}
