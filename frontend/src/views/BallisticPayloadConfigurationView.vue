<script setup lang="ts">
/**
 * 弹道真实载荷配置页面：使用空间目录身份选择卫星，但本页草稿独立于空间长期 profile。
 * 只配置和调用辅助预览；轨道字段与窗口计算由 T22 接入，不在本页伪造计算结果。
 */
import { computed, reactive, ref } from 'vue'
import StandardPayloadEditor from '../components/StandardPayloadEditor.vue'
import { previewBallisticPayload } from '../api/ballisticPayloads'
import type { BallisticPayloadDefinition } from '../api/ballisticPayloads'
import type { PayloadType, Satellite } from '../api/types'
import { createDefaultPayload, standardPayloadFields, validateStandardPayload } from '../utils/standardPayload'
import type { StandardPayloadRequest } from '../utils/standardPayload'

defineOptions({ name: 'BallisticPayloadConfigurationView' })
const props = defineProps<{ satellites: Satellite[] }>()

/** 每颗星保持原选择顺序与载荷数组顺序；原空间数值主键仅在弹道包装层转成字符串。 */
interface SatellitePayloadDraft {
  satelliteId: string
  name: string
  payloads: BallisticPayloadDefinition[]
}
const selectedSatelliteIds = ref<number[]>([])
const configurations = reactive<Record<number, SatellitePayloadDraft>>({})
const selectedSatellites = computed(() => selectedSatelliteIds.value.flatMap((id) => configurations[id] ? [configurations[id]] : []))
const previews = reactive<Record<string, BallisticPayloadDefinition | undefined>>({})
const previewErrors = reactive<Record<string, string | undefined>>({})
const previewingIds = ref<string[]>([])
let payloadSequence = 0

/** 目录切换页/取消选择不删草稿，重新选择回显原载荷；初次选择创建一个唯一身份默认载荷。 */
function selectSatellites(ids: number[]): void {
  for (const id of ids) {
    if (configurations[id]) continue
    const satellite = props.satellites.find((item) => item.id === id)
    if (!satellite) continue
    configurations[id] = { satelliteId: String(satellite.id), name: satellite.name, payloads: [] }
    addPayload(configurations[id])
  }
  selectedSatelliteIds.value = ids.filter((id) => configurations[id])
}

/** 全页面递增编号不回收；相同星增删也不会复用正在预览或曾删除的身份。 */
function addPayload(satellite: SatellitePayloadDraft): void {
  payloadSequence += 1
  satellite.payloads.push({ payloadId: `${satellite.satelliteId}-P${payloadSequence}`, payloadName: `载荷${payloadSequence}`, ...createDefaultPayload() })
}

/** 仅删除指定星的指定对象，其他星与同名载荷均不受影响。 */
function removePayload(satellite: SatellitePayloadDraft, payload: BallisticPayloadDefinition): void {
  satellite.payloads = satellite.payloads.filter((item) => item.payloadId !== payload.payloadId)
  clearPreview(payload.payloadId)
}

/** 草稿改变立即清理旧成功/失败回显；预览是该次 HTTP 的原配置，不可冒充更新后的值。 */
function clearPreview(payloadId: string): void {
  delete previews[payloadId]
  delete previewErrors[payloadId]
}

/** 共用组件只提供十字段，包装层合并进当前载荷，payloadId/payloadName 永远不从其他项获取。 */
function updatePayload(payload: BallisticPayloadDefinition, fields: StandardPayloadRequest): void {
  Object.assign(payload, fields)
  clearPreview(payload.payloadId)
}

/** 切换只改变当前载荷类型与校验口径，保留当前数值以便显式核对，无历史圆锥等价映射。 */
function selectPayloadType(payload: BallisticPayloadDefinition, payloadType: PayloadType): void {
  updatePayload(payload, { ...standardPayloadFields(payload), payloadType })
}

/** 名称独立于载荷十字段，改名后旧预览作废；ID稳定且只读。 */
function updatePayloadName(payload: BallisticPayloadDefinition, name: string): void {
  payload.payloadName = name
  clearPreview(payload.payloadId)
}

/** 两业务共享有限/成对/跨字段校验；非法值不发 HTTP，400/网络失败逐载荷显示中文。 */
async function previewPayload(satellite: SatellitePayloadDraft, payload: BallisticPayloadDefinition): Promise<void> {
  clearPreview(payload.payloadId)
  const errors = validateStandardPayload(payload)
  if (errors.length) { previewErrors[payload.payloadId] = errors.join('；'); return }
  const request: BallisticPayloadDefinition = { payloadId: payload.payloadId, payloadName: payload.payloadName, ...standardPayloadFields(payload) }
  previewingIds.value = [...previewingIds.value, payload.payloadId]
  try {
    const response = await previewBallisticPayload(request)
    // 页面选择变化不取消 HTTP；仅仍属于原星的同一配置接收结果，避免已删除项回显污染新身份。
    if (satellite.payloads.includes(payload) && JSON.stringify(request) === JSON.stringify({ payloadId: payload.payloadId, payloadName: payload.payloadName, ...standardPayloadFields(payload) })) previews[payload.payloadId] = response
  } catch (error) {
    previewErrors[payload.payloadId] = error instanceof Error ? error.message : '载荷预览失败，请检查服务连接'
  } finally {
    previewingIds.value = previewingIds.value.filter((id) => id !== payload.payloadId)
  }
}
</script>

<template>
  <section class="ballistic-payload-view" aria-label="弹道载荷配置">
    <el-card shadow="never" class="planning-card">
      <template #header><h2>弹道载荷配置</h2></template>
      <p>从当前卫星目录选择配置卫星，每颗卫星可配置多个载荷。草稿保留在当前页面；服务预览校验并完整回显参数，不持久化。</p>
      <el-form label-position="top"><el-form-item label="配置卫星">
        <el-select :model-value="selectedSatelliteIds" multiple filterable placeholder="选择配置卫星" @update:model-value="selectSatellites">
          <el-option v-for="satellite in satellites" :key="satellite.id" :value="satellite.id" :label="satellite.name" />
        </el-select>
      </el-form-item></el-form>
      <el-empty v-if="selectedSatellites.length === 0" :image-size="42" description="选择卫星后添加和编辑载荷" />
    </el-card>
    <el-card v-for="satellite in selectedSatellites" :key="satellite.satelliteId" :data-satellite-id="satellite.satelliteId" shadow="never" class="planning-card">
      <template #header><div class="compact-card-header"><h2>{{ satellite.name }}</h2><el-button :data-test="`add-ballistic-payload-${satellite.satelliteId}`" @click="addPayload(satellite)">新增载荷</el-button></div></template>
      <el-empty v-if="satellite.payloads.length === 0" :image-size="42" description="暂无载荷，可点击新增载荷" />
      <article v-for="payload in satellite.payloads" :key="payload.payloadId" :data-payload-id="payload.payloadId" class="ballistic-payload-item">
        <div class="payload-identity">
          <strong>载荷标识：{{ payload.payloadId }}</strong>
          <el-form label-position="top"><el-form-item label="载荷名称"><el-input :model-value="payload.payloadName" :disabled="previewingIds.includes(payload.payloadId)" @update:model-value="updatePayloadName(payload, $event)" /></el-form-item></el-form>
        </div>
        <StandardPayloadEditor :model-value="payload" :disabled="previewingIds.includes(payload.payloadId)"
          @update:model-value="updatePayload(payload, $event)" @select-type="selectPayloadType(payload, $event)" />
        <div class="payload-action">
          <el-button data-test="remove-ballistic-payload" :disabled="previewingIds.includes(payload.payloadId)" @click="removePayload(satellite, payload)">删除载荷</el-button>
          <el-button data-test="preview-ballistic-payload" type="primary" :loading="previewingIds.includes(payload.payloadId)"
            :disabled="validateStandardPayload(payload).length > 0" @click="previewPayload(satellite, payload)">校验并预览</el-button>
        </div>
        <el-alert v-if="previewErrors[payload.payloadId]" :title="previewErrors[payload.payloadId]" type="error" :closable="false" show-icon />
        <div v-if="previews[payload.payloadId]" class="payload-preview"><h3>服务已校验，完整配置回显</h3><pre data-test="ballistic-preview">{{ JSON.stringify(previews[payload.payloadId], null, 2) }}</pre></div>
      </article>
    </el-card>
  </section>
</template>
