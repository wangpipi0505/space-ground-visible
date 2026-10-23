import { mount } from '../test/vueTestUtils'
import ElementUI from 'element-ui'
import { describe, expect, it } from 'vitest'
import SatellitePayloadPanel from './SatellitePayloadPanel.vue'
describe('SatellitePayloadPanel', () => {
  it('provides a persistent space payload save action for every selected satellite', async () => {
    const wrapper = mount(SatellitePayloadPanel, {
      props: {
        satellites: [{ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }],
        configurations: { 1: { activePayloadType: null, profiles: [] } },
        forms: {
          1: {
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
          },
        },
        loadingSatelliteIds: [],
        savingSatelliteId: null,
      },
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.text()).toContain('观测卫星一号')
    expect(wrapper.text()).toContain('未配置')
    await wrapper.get('[data-test="save-payload-1"]').trigger('click')
    expect(wrapper.emitted('save')).toEqual([[1]])
  })
  it('renders the same unified parameter fields for a ground payload', () => {
    const wrapper = mount(SatellitePayloadPanel, {
      props: {
        satellites: [{ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }],
        configurations: { 1: { activePayloadType: 'GROUND', profiles: [{ payloadType: 'GROUND' }] } },
        forms: { 1: { payloadType: 'GROUND' } },
        loadingSatelliteIds: [],
        savingSatelliteId: null,
      },
      global: { plugins: [ElementUI] },
    })
    const labels = wrapper.findAll('.el-form-item__label').map((label) => label.text())
    expect(labels).toEqual([
      '载荷类型',
      '距离下限（公里）',
      '距离上限（公里）',
      '方位角下限（度，双端留空表示不限）',
      '方位角上限（度，双端留空表示不限）',
      '俯仰角下限（度）',
      '俯仰角上限（度）',
      '跟踪角速度（度/秒）',
      '太阳回避半角（度）',
      '启动角速度（度/秒，留空或 -1 表示不启用)',
    ])
  })
  it('warns before a legacy ground cone profile is replaced by unified fields', () => {
    const wrapper = mount(SatellitePayloadPanel, {
      props: {
        satellites: [{ id: 1, name: '观测卫星一号', modelType: 'TLE_SGP4' }],
        configurations: {
          1: {
            activePayloadType: 'GROUND',
            profiles: [{ payloadType: 'GROUND', legacyGroundProfile: true, sensorFullConeAngleDeg: 30 }],
          },
        },
        forms: { 1: { payloadType: 'GROUND' } },
        loadingSatelliteIds: [],
        savingSatelliteId: null,
      },
      global: { plugins: [ElementUI] },
    })
    expect(wrapper.text()).toContain('历史天底锥角格式')
  })
})
