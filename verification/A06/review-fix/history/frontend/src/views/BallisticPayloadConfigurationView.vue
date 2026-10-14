<script setup lang="ts">
/**
 * 弹道窗口与真实载荷一体页面：空间目录只提供身份，根数须显式输入。
 * T21 唯一多载荷草稿直接提供T22计算；空间长期profile与本页草稿独立，切页保留状态。
 */
import { computed, reactive, ref } from 'vue'
import StandardPayloadEditor from '../components/StandardPayloadEditor.vue'
import BallisticWindowControls from '../components/BallisticWindowControls.vue'
import type { BallisticSatelliteDraft, SatelliteOrbitDraft } from '../api/ballisticWindowTypes'
import type { GeneratedBallisticResult } from '../api/ballisticFiles'
import { emptySatelliteOrbit, applyReferenceOrbit } from '../utils/ballisticWindow'
import { swingConstellation } from '../utils/ballisticSwingDefaults'
import { previewBallisticPayload } from '../api/ballisticPayloads'
import type { BallisticPayloadDefinition } from '../api/ballisticPayloads'
import type { PayloadType, Satellite } from '../api/types'
import { createDefaultPayload, standardPayloadFields, validateStandardPayload } from '../utils/standardPayload'
import type { StandardPayloadRequest } from '../utils/standardPayload'

defineOptions({ name: 'BallisticPayloadConfigurationView' })
const props = defineProps<{ satellites: Satellite[]; generatedResults?: GeneratedBallisticResult[] }>()

/** 每颗星保持原选择顺序与载荷数组顺序；原空间数值主键仅在弹道包装层转成字符串。 */
const selectedSatelliteIds = ref<number[]>([])
const configurations = reactive<Record<number, BallisticSatelliteDraft>>({})
const selectedSatellites = computed(() => selectedSatelliteIds.value.flatMap((id) => configurations[id] ? [configurations[id]] : []))
const computing = ref(false)
const manualSatelliteOptions = reactive<Array<{ id: number; name: string }>>([])
const satelliteOptions = computed(() => [...props.satellites, ...manualSatelliteOptions])
let manualSatelliteSequence = 0
/** 根数米/度显式编辑；空输入保留null由提交校验拒绝，不能静默使用DTO缺省。 */
const orbitFields: Array<{ key: Exclude<keyof SatelliteOrbitDraft, 'epochUtc'>; label: string }> = [
  { key: 'semiMajorAxisM', label: '半长轴（米）' }, { key: 'eccentricity', label: '偏心率' },
  { key: 'inclinationDeg', label: '倾角（度）' }, { key: 'raanDeg', label: '升交点赤经（度）' },
  { key: 'argumentOfPerigeeDeg', label: '近地点幅角（度）' }, { key: 'meanAnomalyDeg', label: '平近点角（度）' },
]
const previews = reactive<Record<string, BallisticPayloadDefinition | undefined>>({})
const previewErrors = reactive<Record<string, string | undefined>>({})
const previewingIds = ref<string[]>([])
let payloadSequence = 0
const constellationExample = ref(0)
const generator = reactive({ name: '低轨星座', planeCount: 6, satellitesPerPlane: 6, ...applyReferenceOrbit() })
const generatorError = ref('')

/** 示例/生成全部属于弹道手工身份；替换只取消当前选择，不修改目录和过去的独立草稿。 */
function replaceManualSatellites(rows: Array<SatelliteOrbitDraft & { name: string }>): void {
  const ids: number[] = []
  for (const row of rows) {
    manualSatelliteSequence += 1
    const id = -manualSatelliteSequence
    configurations[id] = { ...row, satelliteId: `MANUAL-${manualSatelliteSequence}`, payloads: [] }
    manualSatelliteOptions.push({ id, name: row.name })
    addPayload(configurations[id])
    ids.push(id)
  }
  selectedSatelliteIds.value = ids
}
/** 相位完全沿Main:1379—1383；仅生成输入根数，不在浏览器传播轨道。 */
function generateConstellation(): void {
  generatorError.value = ''
  const { planeCount, satellitesPerPlane } = generator
  if (!Number.isSafeInteger(planeCount) || !Number.isSafeInteger(satellitesPerPlane) || planeCount <= 0 || satellitesPerPlane <= 0) { generatorError.value = '轨道平面数和每平面卫星数必须为正安全整数'; return }
  const normalize = (value: number) => ((value % 360) + 360) % 360
  const rows = Array.from({ length: planeCount }, (_, plane) => Array.from({ length: satellitesPerPlane }, (_, sat) => ({
    ...generator, name: `${generator.name.trim() || '星座'}-轨道面${plane + 1}-卫星${sat + 1}`,
    raanDeg: normalize(Number(generator.raanDeg) + plane * 360 / planeCount),
    meanAnomalyDeg: normalize(Number(generator.meanAnomalyDeg) + sat * 360 / satellitesPerPlane + plane * 360 / (planeCount * satellitesPerPlane)),
  }))).flat()
  replaceManualSatellites(rows)
}
replaceManualSatellites(swingConstellation(0))

/** 目录切换页/取消选择不删草稿，重新选择回显原载荷；初次选择创建一个唯一身份默认载荷。 */
function selectSatellites(ids: number[]): void {
  for (const id of ids) {
    if (configurations[id]) continue
    const satellite = satelliteOptions.value.find((item) => item.id === id)
    if (!satellite) continue
    configurations[id] = { satelliteId: String(satellite.id), name: satellite.name, payloads: [], ...emptySatelliteOrbit() }
    addPayload(configurations[id])
  }
  selectedSatelliteIds.value = ids.filter((id) => configurations[id])
}

/** 全页面递增编号不回收；相同星增删也不会复用正在预览或曾删除的身份。 */
function addPayload(satellite: BallisticSatelliteDraft): void {
  payloadSequence += 1
  satellite.payloads.push({ payloadId: `${satellite.satelliteId}-P${payloadSequence}`, payloadName: `载荷${payloadSequence}`, ...createDefaultPayload() })
}

/** 仅删除指定星的指定对象，其他星与同名载荷均不受影响。 */
function removePayload(satellite: BallisticSatelliteDraft, payload: BallisticPayloadDefinition): void {
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
async function previewPayload(satellite: BallisticSatelliteDraft, payload: BallisticPayloadDefinition): Promise<void> {
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

/** 手工身份与目录主键分开，新增不伪称目录已提供根数，供无目录或独立部署使用。 */
function addManualSatellite(): void {
  manualSatelliteSequence += 1
  const id = -manualSatelliteSequence
  const name = `手工卫星${manualSatelliteSequence}`
  configurations[id] = { satelliteId: `MANUAL-${manualSatelliteSequence}`, name, payloads: [], ...applyReferenceOrbit(), meanAnomalyDeg: 0 }
  manualSatelliteOptions.push({ id, name })
  addPayload(configurations[id])
  selectedSatelliteIds.value.push(id)
}

/** 原生根数框明确清空null，所有其它数值保留原输入，由有限/物理校验定位。 */
function updateOrbitNumber(satellite: BallisticSatelliteDraft, field: Exclude<keyof SatelliteOrbitDraft, 'epochUtc'>, event: Event): void {
  const input = event.target
  if (input instanceof HTMLInputElement) satellite[field] = input.value.trim() ? Number(input.value) : null
}
</script>

<template>
  <section class="ballistic-payload-view" aria-label="弹道载荷配置">
    <el-card shadow="never" class="planning-card">
      <template #header><h2>可视窗口计算</h2></template>
      <p>默认双星及四种星座来自原 Swing 非生产示例。六根数使用米/度、历元为字面 UTC，可直接编辑；空间目录身份首次选择仍为空根数。本页每星多个 SPACE/GROUND 标准载荷与窗口请求共用同一草稿。</p>
      <fieldset :disabled="computing" class="ballistic-native-form"><legend>卫星六根数表</legend>
        <label>示例星座<select v-model.number="constellationExample" data-test="constellation-example"><option :value="0">默认双星星座 SAT-A/SAT-B</option><option :value="1">太阳同步四星星座</option><option :value="2">MEO导航六星星座</option><option :value="3">Molniya高椭圆三星星座</option></select></label>
        <button type="button" data-test="load-constellation" @click="replaceManualSatellites(swingConstellation(constellationExample))">加载示例星座（替换当前选择）</button>
        <div class="ballistic-table-scroll"><table><thead><tr><th>名称</th><th v-for="field in orbitFields" :key="field.key">{{ field.label }}</th><th>历元时刻（UTC）</th><th>操作</th></tr></thead><tbody>
          <tr v-for="satellite in selectedSatellites" :key="satellite.satelliteId" :data-satellite-id="satellite.satelliteId"><td><input v-model="satellite.name" :aria-label="`${satellite.satelliteId} 卫星名称`" /></td><td v-for="field in orbitFields" :key="field.key"><input :value="satellite[field.key]" :aria-label="`${satellite.name} ${field.label}`" :data-orbit-field="field.key" type="number" step="any" @input="updateOrbitNumber(satellite, field.key, $event)" /></td><td><input v-model="satellite.epochUtc" :aria-label="`${satellite.name} 历元UTC`" data-orbit-field="epochUtc" /></td><td><button type="button" @click="selectedSatelliteIds = selectedSatelliteIds.filter(id => configurations[id] !== satellite)">删除卫星</button><button type="button" data-test="use-reference-orbit" @click="Object.assign(satellite, applyReferenceOrbit())">采用非生产原基准根数</button></td></tr>
        </tbody></table></div>
        <details><summary>按种子卫星生成星座（替换当前选择）</summary><div class="ballistic-field-grid">
          <label>星座名<input v-model="generator.name" /></label><label>轨道平面数<input v-model.number="generator.planeCount" data-generator-field="planeCount" type="number" /></label><label>每平面卫星数<input v-model.number="generator.satellitesPerPlane" data-generator-field="satellitesPerPlane" type="number" /></label>
          <label v-for="field in orbitFields" :key="field.key">种子{{ field.label }}<input v-model.number="generator[field.key]" :data-generator-field="field.key" type="number" step="any" /></label><label>历元 UTC<input v-model="generator.epochUtc" /></label>
        </div><button type="button" data-test="generate-constellation" @click="generateConstellation">生成星座</button><p v-if="generatorError" role="alert" class="ballistic-error">{{ generatorError }}</p></details>
      </fieldset>
      <el-form label-position="top"><el-form-item label="配置卫星">
        <el-select :model-value="selectedSatelliteIds" :disabled="computing" multiple filterable placeholder="选择配置卫星" @update:model-value="selectSatellites">
          <el-option v-for="satellite in satelliteOptions" :key="satellite.id" :value="satellite.id" :label="satellite.name" />
        </el-select>
      </el-form-item></el-form>
      <el-button :disabled="computing" data-test="add-manual-satellite" @click="addManualSatellite">新增手工卫星</el-button>
      <el-empty v-if="selectedSatellites.length === 0" :image-size="42" description="选择卫星后添加和编辑载荷" />
    </el-card>
    <BallisticWindowControls :satellites="selectedSatellites" :generated-results="generatedResults" @busy="computing = $event">
    <el-card v-for="satellite in selectedSatellites" :key="satellite.satelliteId" :data-payload-satellite-id="satellite.satelliteId" shadow="never" class="planning-card">
      <template #header><div class="compact-card-header"><h2>2. {{ satellite.name }} · {{ satellite.satelliteId }}</h2><el-button :disabled="computing" :data-test="`add-ballistic-payload-${satellite.satelliteId}`" @click="addPayload(satellite)">新增载荷</el-button></div></template>
      <el-empty v-if="satellite.payloads.length === 0" :image-size="42" description="暂无载荷，可点击新增载荷" />
      <article v-for="payload in satellite.payloads" :key="payload.payloadId" :data-payload-id="payload.payloadId" class="ballistic-payload-item">
        <div class="payload-identity">
          <strong>载荷标识：{{ payload.payloadId }}</strong>
          <el-form label-position="top"><el-form-item label="载荷名称"><el-input :model-value="payload.payloadName" :disabled="computing || previewingIds.includes(payload.payloadId)" @update:model-value="updatePayloadName(payload, $event)" /></el-form-item></el-form>
        </div>
        <StandardPayloadEditor :model-value="payload" :disabled="computing || previewingIds.includes(payload.payloadId)"
          @update:model-value="updatePayload(payload, $event)" @select-type="selectPayloadType(payload, $event)" />
        <div class="payload-action">
          <el-button data-test="remove-ballistic-payload" :disabled="computing || previewingIds.includes(payload.payloadId)" @click="removePayload(satellite, payload)">删除载荷</el-button>
          <el-button data-test="preview-ballistic-payload" type="primary" :loading="previewingIds.includes(payload.payloadId)"
            :disabled="computing || validateStandardPayload(payload).length > 0" @click="previewPayload(satellite, payload)">校验并预览</el-button>
        </div>
        <el-alert v-if="previewErrors[payload.payloadId]" :title="previewErrors[payload.payloadId]" type="error" :closable="false" show-icon />
        <div v-if="previews[payload.payloadId]" class="payload-preview"><h3>服务已校验，完整配置回显</h3><pre data-test="ballistic-preview">{{ JSON.stringify(previews[payload.payloadId], null, 2) }}</pre></div>
      </article>
    </el-card>
    </BallisticWindowControls>
  </section>
</template>
