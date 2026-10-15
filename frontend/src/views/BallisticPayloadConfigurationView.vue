<script setup lang="ts">
/**
 * 弹道窗口与真实载荷一体页面：空间目录只提供身份，根数须显式输入。
 * T21 唯一多载荷草稿直接提供T22计算；空间长期profile与本页草稿独立，切页保留状态。
 */
import { computed, reactive, ref, watch } from 'vue'
import StandardPayloadEditor from '../components/StandardPayloadEditor.vue'
import BallisticWindowControls from '../components/BallisticWindowControls.vue'
import type { BallisticSatelliteDraft, SatelliteOrbitDraft } from '../api/ballisticWindowTypes'
import type { GeneratedBallisticResult } from '../api/ballisticFiles'
import { emptySatelliteOrbit, applyReferenceOrbit, isValidLiteralUtc } from '../utils/ballisticWindow'
import { swingConstellation, swingTableNumber } from '../utils/ballisticSwingDefaults'
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
const currentSatelliteId = ref('')
const exampleConfiguration = ref(true)
const payloadSection = ref<HTMLElement>()
// 当前星只控制可见性，所有草稿仍保留在父级唯一数组，切换不会丢失末格编辑。
watch(selectedSatellites, rows => {
  if (!rows.some(row => row.satelliteId === currentSatelliteId.value)) currentSatelliteId.value = rows[0]?.satelliteId || ''
}, { immediate: true })
function focusPayloads(): void {
  payloadSection.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  payloadSection.value?.querySelector<HTMLSelectElement>('select')?.focus()
  const current = payloadSection.value?.querySelector<HTMLElement>(`[data-payload-satellite-id="${currentSatelliteId.value}"]`)
  const editor = current?.querySelector<HTMLDetailsElement>('details.ballistic-payload-item')
  if (editor) editor.open = true
}
let payloadSequence = 0
const constellationExample = ref(0)
const generator = reactive({ name: '低轨星座', planeCount: 6, satellitesPerPlane: 6, ...applyReferenceOrbit() })
const generatorError = ref('')
const generatorErrorField = ref('')

/** 示例/生成全部属于弹道手工身份；替换只取消当前选择，不修改目录和过去的独立草稿。 */
function replaceManualSatellites(rows: Array<SatelliteOrbitDraft & { name: string }>, example = false): void {
  exampleConfiguration.value = example
  const ids: number[] = []
  for (const row of rows) {
    manualSatelliteSequence += 1
    const id = -manualSatelliteSequence
    configurations[id] = { ...row, satelliteId: `MANUAL-${manualSatelliteSequence}`, payloads: [] }
    manualSatelliteOptions.push({ id, name: row.name })
    addPayload(configurations[id])
    // 仅明确的非生产示例朝向地球；目录、种子生成和新增载荷仍沿空间标准默认。
    if (example) Object.assign(configurations[id].payloads[0]!, { elevationMinDeg: -90, elevationMaxDeg: 0 })
    ids.push(id)
  }
  selectedSatelliteIds.value = ids
}
/**
 * 替换前完整校验种子，空number输入在Vue中仍为空串，禁止Number('')静默补零。
 * 相位沿Main:1379—1383，落表沿addRow的0/6/1位和整秒；只生成输入，不传播轨道。
 * 空历元沿原utcToJ2000Sec返回0的落表常量；非空只校验/整理字面UTC，不做时区换算。
 */
function generateConstellation(event: Event): void {
  generatorError.value = ''
  generatorErrorField.value = ''
  const fail = (field: string, message: string): void => {
    generatorErrorField.value = field
    generatorError.value = message
    const button = event.currentTarget
    if (button instanceof HTMLElement) button.closest('details')?.querySelector<HTMLInputElement>(`[data-generator-field="${field}"]`)?.focus()
  }
  const { planeCount, satellitesPerPlane } = generator
  for (const field of ['planeCount', 'satellitesPerPlane'] as const) {
    if (!Number.isSafeInteger(generator[field]) || generator[field] <= 0) { fail(field, '轨道平面数和每平面卫星数必须为正安全整数'); return }
  }
  for (const { key, label } of orbitFields) {
    if (typeof generator[key] !== 'number' || !Number.isFinite(generator[key])) { fail(key, `种子${label}须明确填写有限数`); return }
  }
  if (!isValidLiteralUtc(generator.epochUtc)) { fail('epochUtc', '种子历元UTC格式或日期无效'); return }
  const epochUtc = generator.epochUtc.trim() ? generator.epochUtc.trim().replace(/-/g, '/').split('.')[0]! : '2000/01/01 12:00:00'
  // 原normalizeDeg只对负余数加360，正半值不能先加360而引入额外浮点抵消。
  const normalize = (value: number) => { const remainder = value % 360; return remainder < 0 ? remainder + 360 : remainder }
  const rows = Array.from({ length: planeCount }, (_, plane) => Array.from({ length: satellitesPerPlane }, (_, sat) => ({
    name: `${generator.name.trim() || '星座'}-轨道面${plane + 1}-卫星${sat + 1}`, epochUtc,
    semiMajorAxisM: swingTableNumber(generator.semiMajorAxisM!, 0),
    eccentricity: swingTableNumber(generator.eccentricity!, 6),
    inclinationDeg: swingTableNumber(generator.inclinationDeg!, 1),
    argumentOfPerigeeDeg: swingTableNumber(generator.argumentOfPerigeeDeg!, 1),
    raanDeg: swingTableNumber(normalize(generator.raanDeg! + plane * 360 / planeCount), 1),
    meanAnomalyDeg: swingTableNumber(normalize(generator.meanAnomalyDeg! + sat * 360 / satellitesPerPlane + plane * 360 / (planeCount * satellitesPerPlane)), 1),
  }))).flat()
  replaceManualSatellites(rows)
}
replaceManualSatellites(swingConstellation(0), true)

/** 目录切换页/取消选择不删草稿，重新选择回显原载荷；初次选择创建一个唯一身份默认载荷。 */
function selectSatellites(ids: number[]): void {
  exampleConfiguration.value = false
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
}

/** 共用组件只提供十字段，包装层合并进当前载荷，payloadId/payloadName 永远不从其他项获取。 */
function updatePayload(payload: BallisticPayloadDefinition, fields: StandardPayloadRequest): void {
  Object.assign(payload, fields)
}

/** 切换只改变当前载荷类型与校验口径，保留当前数值以便显式核对，无历史圆锥等价映射。 */
function selectPayloadType(payload: BallisticPayloadDefinition, payloadType: PayloadType): void {
  updatePayload(payload, { ...standardPayloadFields(payload), payloadType })
}

/** 名称独立于载荷十字段，改名后旧预览作废；ID稳定且只读。 */
function updatePayloadName(payload: BallisticPayloadDefinition, name: string): void {
  payload.payloadName = name
}

/** 手工身份与目录主键分开，新增不伪称目录已提供根数，供无目录或独立部署使用。 */
function addManualSatellite(): void {
  exampleConfiguration.value = false
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
  <section class="ballistic-payload-view ballistic-workspace" aria-label="弹道载荷配置">
    <header class="ballistic-page-header"><h2>可视窗口计算</h2><span class="ballistic-badge">{{ exampleConfiguration ? '示例配置' : '自定义配置' }}</span><span class="ballistic-muted">{{ selectedSatellites.length }} 颗卫星</span></header>
    <BallisticWindowControls :satellites="selectedSatellites" :generated-results="generatedResults" @busy="computing = $event" @edit-payloads="focusPayloads">
    <template #satellites>
    <section class="ballistic-panel">
      <fieldset :disabled="computing" class="ballistic-native-form"><legend>卫星六根数表</legend>
        <div class="ballistic-orbit-toolbar"><label>示例星座<select v-model.number="constellationExample" data-test="constellation-example"><option :value="0">默认双星星座 SAT-A/SAT-B</option><option :value="1">太阳同步四星星座</option><option :value="2">MEO导航六星星座</option><option :value="3">Molniya高椭圆三星星座</option></select></label>
        <button type="button" data-test="load-constellation" @click="replaceManualSatellites(swingConstellation(constellationExample), true)">载入示例</button></div>
        <div class="ballistic-table-scroll"><table><thead><tr><th>名称</th><th v-for="field in orbitFields" :key="field.key">{{ field.label }}</th><th>历元时刻（UTC）</th><th>操作</th></tr></thead><tbody>
          <tr v-for="satellite in selectedSatellites" :key="satellite.satelliteId" :data-satellite-id="satellite.satelliteId"><td><input v-model="satellite.name" :aria-label="`${satellite.satelliteId} 卫星名称`" /></td><td v-for="field in orbitFields" :key="field.key"><input :value="satellite[field.key]" :aria-label="`${satellite.name} ${field.label}`" :data-orbit-field="field.key" type="number" step="any" @input="updateOrbitNumber(satellite, field.key, $event)" /></td><td><input v-model="satellite.epochUtc" :aria-label="`${satellite.name} 历元UTC`" data-orbit-field="epochUtc" /></td><td><button type="button" @click="selectedSatelliteIds = selectedSatelliteIds.filter(id => configurations[id] !== satellite)">删除卫星</button><button type="button" data-test="use-reference-orbit" @click="Object.assign(satellite, applyReferenceOrbit())">恢复基准</button></td></tr>
        </tbody></table></div>
        <details class="ballistic-generator"><summary>按种子生成星座</summary><div class="ballistic-field-grid">
          <label>星座名<input v-model="generator.name" /></label><label>轨道平面数<input v-model.number="generator.planeCount" data-generator-field="planeCount" :aria-invalid="generatorErrorField === 'planeCount'" type="number" /></label><label>每平面卫星数<input v-model.number="generator.satellitesPerPlane" data-generator-field="satellitesPerPlane" :aria-invalid="generatorErrorField === 'satellitesPerPlane'" type="number" /></label>
          <label v-for="field in orbitFields" :key="field.key">种子{{ field.label }}<input v-model.number="generator[field.key]" :data-generator-field="field.key" :aria-invalid="generatorErrorField === field.key" type="number" step="any" /></label><label>历元 UTC<input v-model="generator.epochUtc" data-generator-field="epochUtc" :aria-invalid="generatorErrorField === 'epochUtc'" /></label>
        </div><button type="button" data-test="generate-constellation" @click="generateConstellation">生成星座</button><p v-if="generatorError" role="alert" class="ballistic-error">{{ generatorError }}</p></details>
      <details class="ballistic-catalog"><summary>从目录选择卫星</summary><el-form label-position="top"><el-form-item label="配置卫星">
        <el-select :model-value="selectedSatelliteIds" :disabled="computing" multiple filterable placeholder="选择配置卫星" @update:model-value="selectSatellites">
          <el-option v-for="satellite in satelliteOptions" :key="satellite.id" :value="satellite.id" :label="satellite.name" />
        </el-select>
      </el-form-item></el-form></details>
      <el-button size="small" :disabled="computing" data-test="add-manual-satellite" @click="addManualSatellite">新增手工卫星</el-button>
      </fieldset>
      <el-empty v-if="selectedSatellites.length === 0" :image-size="42" description="选择卫星后添加和编辑载荷" />
    </section>
    </template>
    <section ref="payloadSection" class="ballistic-panel ballistic-payload-panel" tabindex="-1">
      <div class="ballistic-section-heading"><h3>载荷约束</h3><label>当前卫星<select v-model="currentSatelliteId" :disabled="computing" data-test="current-payload-satellite"><option v-for="satellite in selectedSatellites" :key="satellite.satelliteId" :value="satellite.satelliteId">{{ satellite.name }} · {{ satellite.payloads.length }} 个载荷</option></select></label></div>
    <el-card v-for="satellite in selectedSatellites" v-show="satellite.satelliteId === currentSatelliteId" :key="satellite.satelliteId" :data-payload-satellite-id="satellite.satelliteId" shadow="never" class="planning-card">
      <template #header><div class="compact-card-header"><span class="ballistic-muted">{{ satellite.payloads.length }} 个载荷</span><el-button :disabled="computing" :data-test="`add-ballistic-payload-${satellite.satelliteId}`" @click="addPayload(satellite)">新增载荷</el-button></div></template>
      <el-empty v-if="satellite.payloads.length === 0" :image-size="42" description="暂无载荷，可点击新增载荷" />
      <details v-for="payload in satellite.payloads" :key="payload.payloadId" :data-payload-id="payload.payloadId" class="ballistic-payload-item"><summary><strong>{{ payload.payloadName }} · {{ payload.payloadType }}</strong><span class="ballistic-payload-summary">距离 {{ payload.minDistanceKm }}–{{ payload.maxDistanceKm }} km · 方位 {{ payload.azimuthMinDeg }}–{{ payload.azimuthMaxDeg }}° · 俯仰 {{ payload.elevationMinDeg }}–{{ payload.elevationMaxDeg }}°</span><span class="ballistic-muted">展开编辑</span><span v-if="validateStandardPayload(payload).length" class="ballistic-invalid-indicator"> · {{ validateStandardPayload(payload).length }} 项待修正</span></summary>
        <div class="payload-identity">

          <el-form label-position="top"><el-form-item label="载荷名称"><el-input :model-value="payload.payloadName" :disabled="computing" @update:model-value="updatePayloadName(payload, $event)" /></el-form-item></el-form>
        </div>
        <StandardPayloadEditor compact :model-value="payload" :disabled="computing"
          @update:model-value="updatePayload(payload, $event)" @select-type="selectPayloadType(payload, $event)" />
        <div class="payload-action">
          <el-button data-test="remove-ballistic-payload" :disabled="computing" @click="removePayload(satellite, payload)">删除载荷</el-button>
        </div>
      </details>
    </el-card>
    </section>
    </BallisticWindowControls>
  </section>
</template>
