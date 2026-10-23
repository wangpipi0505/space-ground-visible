import { describe, expect, it } from 'vitest'
import { createDefaultPayload, standardPayloadFields, validateStandardPayload } from './standardPayload'
/** 期望来源为 T20 冻结协议，包含每个数值边界与 sentinel，不从待测逻辑生成期望。 */
describe('统一标准载荷边界', () => {
  it('保留十字段、双null、SPACE零负一及startup负一原值', () => {
    const payload = {
      ...createDefaultPayload(),
      azimuthMinDeg: null,
      azimuthMaxDeg: null,
      elevationMinDeg: null,
      elevationMaxDeg: null,
      angleRateDegPerSec: -1,
      startupAngleRateDegPerSec: -1,
    }
    expect(validateStandardPayload(payload)).toEqual([])
    expect(standardPayloadFields({ ...payload, payloadId: '不应进入标准对象' })).toEqual(payload)
    expect(Object.keys(standardPayloadFields(payload))).toHaveLength(10)
    expect(validateStandardPayload({ ...payload, angleRateDegPerSec: 0, maxDistanceKm: 0 })).toEqual([])
    expect(
      validateStandardPayload({
        ...createDefaultPayload('GROUND'),
        elevationMinDeg: -91,
        elevationMaxDeg: 90,
        angleRateDegPerSec: 0,
      }),
    ).toEqual([])
    for (const startup of [null, -1, 0, 0.1])
      expect(validateStandardPayload({ ...payload, startupAngleRateDegPerSec: startup })).toEqual([])
  })
  it('拒绝所有非有限、缺必填、单端空和倒置跨字段', () => {
    const base = createDefaultPayload()
    const invalid = [
      { minDistanceKm: -1 },
      { maxDistanceKm: -1 },
      { maxDistanceKm: 1000 },
      { minDistanceKm: 1e308 },
      { maxDistanceKm: 1e308 },
      { azimuthMinDeg: null },
      { azimuthMaxDeg: null },
      { azimuthMinDeg: -1 },
      { azimuthMinDeg: 270 },
      { azimuthMaxDeg: 361 },
      { elevationMinDeg: -91 },
      { elevationMaxDeg: 91 },
      { elevationMaxDeg: -4 },
      { elevationMaxDeg: null },
      { angleRateDegPerSec: -0.5 },
      { sunAvoidHalfAngleDeg: -1 },
      { sunAvoidHalfAngleDeg: 181 },
      { startupAngleRateDegPerSec: -0.5 },
    ]
    for (const change of invalid) expect(validateStandardPayload({ ...base, ...change }).length).toBeGreaterThan(0)
    for (const field of [
      'minDistanceKm',
      'maxDistanceKm',
      'azimuthMinDeg',
      'azimuthMaxDeg',
      'elevationMinDeg',
      'elevationMaxDeg',
      'angleRateDegPerSec',
      'sunAvoidHalfAngleDeg',
      'startupAngleRateDegPerSec',
    ]) {
      for (const value of [NaN, Infinity, -Infinity])
        expect(validateStandardPayload({ ...base, [field]: value }).length).toBeGreaterThan(0)
    }
    for (const field of ['minDistanceKm', 'maxDistanceKm', 'angleRateDegPerSec', 'sunAvoidHalfAngleDeg']) {
      expect(validateStandardPayload({ ...base, [field]: null }).length).toBeGreaterThan(0)
    }
    expect(
      validateStandardPayload({ ...createDefaultPayload('GROUND'), angleRateDegPerSec: -1 }).length,
    ).toBeGreaterThan(0)
    expect(
      validateStandardPayload({ ...createDefaultPayload('GROUND'), elevationMinDeg: -91.1 }).length,
    ).toBeGreaterThan(0)
  })
})
