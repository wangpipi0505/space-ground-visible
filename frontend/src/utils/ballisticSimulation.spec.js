/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parseSimulationInput } from './ballisticSimulation'
/** 原35字段和嵌套事件逐值断言；唯一适配是省略浏览器不拥有的服务输出目录。 */
describe('T24 原模拟输入合同', () => {
  const source = JSON.parse(
    readFileSync('../test-data/reference/ballistic/target_trajectory_simulate_default.json', 'utf8'),
  )
  it('完整原字段、事件、null、别名、字面UTC、回退步长不转换不丢失', () => {
    const full = {
      ...source,
      taskName: null,
      targetTypeCode: '0x03',
      targetShapeCode: '0x01',
      flightPhaseCode: '0x04',
      launchLatDeg: 20,
      launchLonDeg: 120,
      launchHeightM: 100,
      initialSpeedMs: 1600,
      azimuthDeg: 80,
      elevationDeg: 30,
      durationSec: 700,
      integrationStepSec: 0.25,
      outputStepSec: 2.5,
      boostDurationSec: 30,
      boostAccelerationMs2: 11,
      dragCdAreaOverMass: 0.01,
      outputPointDurationSec: 0,
      impactTime: '2026/06/04 04:42:55.000',
      phaseEvents: [
        {
          phaseType: 'boost',
          eventType: null,
          startTime: '原文本',
          endTime: null,
          eventTime: '原值',
          dynamics: '旧模式',
          directionMode: '旧方向',
          accelerationMs2: 12,
          deltaVms: 3,
          liftDragRatio: 2,
          dragCdAreaOverMass: 0.02,
        },
      ],
    }
    const { outputDir, ...expected } = full
    void outputDir
    expect(Object.keys(full)).toHaveLength(35)
    expect(parseSimulationInput(JSON.stringify(full))).toEqual({ request: expected, omittedOutputDir: true })
    for (const outputDir of [null, '', '.', 'C:/本机目录'])
      expect(parseSimulationInput(JSON.stringify({ ...full, outputDir })).request).toEqual(expected)
    for (const variant of [
      {},
      { taskName: null, launchTimeUtc: null, phaseEvents: null },
      { launchPointCoordFrame: 'ECI-J2000', launchPointUnit: 'meters', outputPointDurationSec: -1, outputStepSec: -1 },
      { outputPointDurationSec: 0.0001 },
      { outputPointDurationSec: null },
    ])
      expect(parseSimulationInput(JSON.stringify(variant)).request).toEqual(variant)
  })
  it('非法JSON、非有限数、精度丢失、字段类型错误在HTTP前阻止', () => {
    for (const text of [
      '{',
      'null',
      '[]',
      '{"launchPointX":1e999}',
      '{"launchPointX":9007199254740993}',
      '{"launchPointX":1.00000000000000001}',
      '{"launchTimeUtc":1}',
      '{"phaseEvents":[1]}',
    ])
      expect(() => parseSimulationInput(text)).toThrow()
  })
})
