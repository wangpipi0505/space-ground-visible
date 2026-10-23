<script>
export default { name: 'BallisticPayloadConfigurationView' }
</script>

<script setup>
/**
 * 弹道窗口与真实载荷一体页面：空间目录只提供身份，根数须显式输入。
 * T21 唯一多载荷草稿直接提供T22计算；空间长期profile与本页草稿独立，切页保留状态。
 */
import { computed, reactive, ref, watch, set } from 'vue'
import StandardPayloadEditor from '../components/StandardPayloadEditor.vue'
import BallisticWindowControls from '../components/BallisticWindowControls.vue'
import { emptySatelliteOrbit, applyReferenceOrbit, isValidLiteralUtc } from '../utils/ballisticWindow'
import { swingConstellation, swingTableNumber } from '../utils/ballisticSwingDefaults'
import { createDefaultPayload, standardPayloadFields, validateStandardPayload } from '../utils/standardPayload'
const props = defineProps({ satellites: { type: Array, required: true }, generatedResults: { type: Array } })
/** 每颗星保持原选择顺序与载荷数组顺序；原空间数值主键仅在弹道包装层转成字符串。 */
const selectedSatelliteIds = ref([])
const configurations = reactive({})
const selectedSatellites = computed(() =>
  selectedSatelliteIds.value.flatMap((id) => (configurations[id] ? [configurations[id]] : [])),
)
const computing = ref(false)
const manualSatelliteOptions = ref([])
const satelliteOptions = computed(() => [...props.satellites, ...manualSatelliteOptions.value])
let manualSatelliteSequence = 0
/** 根数米/度显式编辑；空输入保留null由提交校验拒绝，不能静默使用DTO缺省。 */
const orbitFields = [
  { key: 'semiMajorAxisM', label: '半长轴（米）' },
  { key: 'eccentricity', label: '偏心率' },
  { key: 'inclinationDeg', label: '倾角（度）' },
  { key: 'raanDeg', label: '升交点赤经（度）' },
  { key: 'argumentOfPerigeeDeg', label: '近地点幅角（度）' },
  { key: 'meanAnomalyDeg', label: '平近点角（度）' },
]
const currentSatelliteId = ref('')
const exampleConfiguration = ref(true)
const payloadSection = ref()
// 当前星只控制可见性，所有草稿仍保留在父级唯一数组，切换不会丢失末格编辑。
watch(
  selectedSatellites,
  (rows) => {
    if (!rows.some((row) => row.satelliteId === currentSatelliteId.value))
      currentSatelliteId.value = rows[0]?.satelliteId || ''
  },
  { immediate: true },
)
function focusPayloads() {
  payloadSection.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  payloadSection.value?.querySelector('.el-select input')?.focus()
  const current = payloadSection.value?.querySelector(`[data-payload-satellite-id="${currentSatelliteId.value}"]`)
  const editor = current?.querySelector('details.ballistic-payload-item')
  if (editor) editor.open = true
}
let payloadSequence = 0
const constellationExample = ref(0)
const generator = reactive({ name: '低轨星座', planeCount: 6, satellitesPerPlane: 6, ...applyReferenceOrbit() })
const generatorError = ref('')
const generatorErrorField = ref('')
/** 示例/生成全部属于弹道手工身份；替换只取消当前选择，不修改目录和过去的独立草稿。 */
function replaceManualSatellites(rows, example = false) {
  exampleConfiguration.value = example
  const ids = []
  for (const row of rows) {
    manualSatelliteSequence += 1
    const id = -manualSatelliteSequence
    set(configurations, id, { ...row, satelliteId: `MANUAL-${manualSatelliteSequence}`, payloads: [] })
    manualSatelliteOptions.value.push({ id, name: row.name })
    addPayload(configurations[id])
    // 仅明确的非生产示例朝向地球；目录、种子生成和新增载荷仍沿空间标准默认。
    if (example) Object.assign(configurations[id].payloads[0], { elevationMinDeg: -90, elevationMaxDeg: 0 })
    ids.push(id)
  }
  selectedSatelliteIds.value = ids
}
/**
 * 替换前完整校验种子，空number输入在Vue中仍为空串，禁止Number('')静默补零。
 * 相位沿Main:1379—1383，落表沿addRow的0/6/1位和整秒；只生成输入，不传播轨道。
 * 空历元沿原utcToJ2000Sec返回0的落表常量；非空只校验/整理字面UTC，不做时区换算。
 */
function generateConstellation(event) {
  generatorError.value = ''
  generatorErrorField.value = ''
  const fail = (field, message) => {
    generatorErrorField.value = field
    generatorError.value = message
    const button = event.currentTarget
    if (button instanceof HTMLElement)
      button.closest('details')?.querySelector(`[data-generator-field="${field}"]`)?.focus()
  }
  const { planeCount, satellitesPerPlane } = generator
  for (const field of ['planeCount', 'satellitesPerPlane']) {
    if (!Number.isSafeInteger(generator[field]) || generator[field] <= 0) {
      fail(field, '轨道平面数和每平面卫星数必须为正安全整数')
      return
    }
  }
  for (const { key, label } of orbitFields) {
    if (typeof generator[key] !== 'number' || !Number.isFinite(generator[key])) {
      fail(key, `种子${label}须明确填写有限数`)
      return
    }
  }
  if (!isValidLiteralUtc(generator.epochUtc)) {
    fail('epochUtc', '种子历元UTC格式或日期无效')
    return
  }
  const epochUtc = generator.epochUtc.trim()
    ? generator.epochUtc.trim().replace(/-/g, '/').split('.')[0]
    : '2000/01/01 12:00:00'
  // 原normalizeDeg只对负余数加360，正半值不能先加360而引入额外浮点抵消。
  const normalize = (value) => {
    const remainder = value % 360
    return remainder < 0 ? remainder + 360 : remainder
  }
  const rows = Array.from({ length: planeCount }, (_, plane) =>
    Array.from({ length: satellitesPerPlane }, (_, sat) => ({
      name: `${generator.name.trim() || '星座'}-轨道面${plane + 1}-卫星${sat + 1}`,
      epochUtc,
      semiMajorAxisM: swingTableNumber(generator.semiMajorAxisM, 0),
      eccentricity: swingTableNumber(generator.eccentricity, 6),
      inclinationDeg: swingTableNumber(generator.inclinationDeg, 1),
      argumentOfPerigeeDeg: swingTableNumber(generator.argumentOfPerigeeDeg, 1),
      raanDeg: swingTableNumber(normalize(generator.raanDeg + (plane * 360) / planeCount), 1),
      meanAnomalyDeg: swingTableNumber(
        normalize(
          generator.meanAnomalyDeg +
            (sat * 360) / satellitesPerPlane +
            (plane * 360) / (planeCount * satellitesPerPlane),
        ),
        1,
      ),
    })),
  ).flat()
  replaceManualSatellites(rows)
}
replaceManualSatellites(swingConstellation(0), true)
/** 目录切换页/取消选择不删草稿，重新选择回显原载荷；初次选择创建一个唯一身份默认载荷。 */
function selectSatellites(ids) {
  exampleConfiguration.value = false
  for (const id of ids) {
    if (configurations[id]) continue
    const satellite = satelliteOptions.value.find((item) => item.id === id)
    if (!satellite) continue
    set(configurations, id, {
      satelliteId: String(satellite.id),
      name: satellite.name,
      payloads: [],
      ...emptySatelliteOrbit(),
    })
    addPayload(configurations[id])
  }
  selectedSatelliteIds.value = ids.filter((id) => configurations[id])
}
/** 全页面递增编号不回收；相同星增删也不会复用正在预览或曾删除的身份。 */
function addPayload(satellite) {
  payloadSequence += 1
  satellite.payloads.push({
    payloadId: `${satellite.satelliteId}-P${payloadSequence}`,
    payloadName: `载荷${payloadSequence}`,
    ...createDefaultPayload(),
  })
}
/** 仅删除指定星的指定对象，其他星与同名载荷均不受影响。 */
function removePayload(satellite, payload) {
  satellite.payloads = satellite.payloads.filter((item) => item.payloadId !== payload.payloadId)
}
/** 共用组件只提供十字段，包装层合并进当前载荷，payloadId/payloadName 永远不从其他项获取。 */
function updatePayload(payload, fields) {
  Object.assign(payload, fields)
}
/** 切换只改变当前载荷类型与校验口径，保留当前数值以便显式核对，无历史圆锥等价映射。 */
function selectPayloadType(payload, payloadType) {
  updatePayload(payload, { ...standardPayloadFields(payload), payloadType })
}
/** 名称独立于载荷十字段，改名后旧预览作废；ID稳定且只读。 */
function updatePayloadName(payload, name) {
  payload.payloadName = name
}
/** 手工身份与目录主键分开，新增不伪称目录已提供根数，供无目录或独立部署使用。 */
function addManualSatellite() {
  exampleConfiguration.value = false
  manualSatelliteSequence += 1
  const id = -manualSatelliteSequence
  const name = `手工卫星${manualSatelliteSequence}`
  set(configurations, id, {
    satelliteId: `MANUAL-${manualSatelliteSequence}`,
    name,
    payloads: [],
    ...applyReferenceOrbit(),
    meanAnomalyDeg: 0,
  })
  manualSatelliteOptions.value.push({ id, name })
  addPayload(configurations[id])
  selectedSatelliteIds.value.push(id)
}
/** Element UI 数值输入框明确清空 null，其它数值原样写回草稿，由有限数和轨道物理校验定位。 */
function updateOrbitNumber(satellite, field, value) {
  const text = String(value)
  satellite[field] = text.trim() ? Number(text) : null
}
</script>

<template>
  <section class="ballistic-payload-view ballistic-workspace" aria-label="弹道载荷配置">
    <header class="ballistic-page-header"><h2>可视窗口计算</h2><span class="ballistic-badge">{{ exampleConfiguration ? '示例配置' : '自定义配置' }}</span><span class="ballistic-muted">{{ selectedSatellites.length }} 颗卫星</span></header>
    <BallisticWindowControls :satellites="selectedSatellites" :generated-results="generatedResults" @busy="computing = $event" @editPayloads="focusPayloads">
    <template #satellites>
    <section class="ballistic-panel">
      <fieldset :disabled="computing" class="ballistic-native-form"><legend>卫星六根数表</legend>
        <div class="ballistic-orbit-toolbar"><el-form label-position="top"><el-form-item label="示例星座"><el-select v-model="constellationExample" data-test="constellation-example"><el-option :value="0" label="默认双星星座 SAT-A/SAT-B" /><el-option :value="1" label="太阳同步四星星座" /><el-option :value="2" label="MEO导航六星星座" /><el-option :value="3" label="Molniya高椭圆三星星座" /></el-select></el-form-item></el-form>
        <el-button type="primary" data-test="load-constellation" @click="replaceManualSatellites(swingConstellation(constellationExample), true)">载入示例</el-button></div>
        <div class="ballistic-table-scroll"><el-table :data="selectedSatellites" row-key="satelliteId" border size="small" table-layout="auto">
          <el-table-column label="名称" min-width="150"><template #default="{ row: satellite }"><el-input v-model="satellite.name" :label="`${satellite.satelliteId} 卫星名称`" /></template></el-table-column>
          <el-table-column v-for="field in orbitFields" :key="field.key" :label="field.label" min-width="150"><template #default="{ row: satellite }"><el-input :value="satellite[field.key] ?? ''" :label="`${satellite.name} ${field.label}`" :data-orbit-field="field.key" type="number" step="any" @input="updateOrbitNumber(satellite, field.key, $event)" /></template></el-table-column>
          <el-table-column label="历元时刻（UTC）" min-width="210"><template #default="{ row: satellite }"><el-input v-model="satellite.epochUtc" :label="`${satellite.name} 历元UTC`" data-orbit-field="epochUtc" /></template></el-table-column>
          <el-table-column label="操作" width="190" fixed="right"><template #default="{ row: satellite }"><el-button size="small" @click="selectedSatelliteIds = selectedSatelliteIds.filter(id => configurations[id] !== satellite)">删除卫星</el-button><el-button size="small" data-test="use-reference-orbit" @click="Object.assign(satellite, applyReferenceOrbit())">恢复基准</el-button></template></el-table-column>
        </el-table></div>
        <details class="ballistic-generator"><summary>按种子生成星座</summary><el-form label-position="top" class="ballistic-field-grid">
          <el-form-item label="星座名"><el-input v-model="generator.name" /></el-form-item><el-form-item label="轨道平面数"><el-input v-model.number="generator.planeCount" data-generator-field="planeCount" :aria-invalid="generatorErrorField === 'planeCount'" type="number" /></el-form-item><el-form-item label="每平面卫星数"><el-input v-model.number="generator.satellitesPerPlane" data-generator-field="satellitesPerPlane" :aria-invalid="generatorErrorField === 'satellitesPerPlane'" type="number" /></el-form-item>
          <el-form-item v-for="field in orbitFields" :key="field.key" :label="`种子${field.label}`"><el-input v-model.number="generator[field.key]" :data-generator-field="field.key" :aria-invalid="generatorErrorField === field.key" type="number" step="any" /></el-form-item><el-form-item label="历元 UTC"><el-input v-model="generator.epochUtc" data-generator-field="epochUtc" :aria-invalid="generatorErrorField === 'epochUtc'" /></el-form-item>
        </el-form><el-button type="primary" data-test="generate-constellation" @click="generateConstellation">生成星座</el-button><p v-if="generatorError" role="alert" class="ballistic-error">{{ generatorError }}</p></details>
      <details class="ballistic-catalog"><summary>从目录选择卫星</summary><el-form label-position="top"><el-form-item label="配置卫星">
        <el-select :value="selectedSatelliteIds" :disabled="computing" multiple filterable placeholder="选择配置卫星" data-test="catalog-satellite-select" @input="selectSatellites">
          <el-option v-for="satellite in satelliteOptions" :key="satellite.id" :value="satellite.id" :label="satellite.name" />
        </el-select>
      </el-form-item></el-form></details>
      <el-button size="small" :disabled="computing" data-test="add-manual-satellite" @click="addManualSatellite">新增手工卫星</el-button>
      </fieldset>
      <el-empty v-if="selectedSatellites.length === 0" :image-size="42" description="选择卫星后添加和编辑载荷" />
    </section>
    </template>
    <section ref="payloadSection" class="ballistic-panel ballistic-payload-panel" tabindex="-1">
      <div class="ballistic-section-heading"><h3>载荷约束</h3><el-form label-position="top"><el-form-item label="当前卫星"><el-select v-model="currentSatelliteId" :disabled="computing" data-test="current-payload-satellite"><el-option v-for="satellite in selectedSatellites" :key="satellite.satelliteId" :value="satellite.satelliteId" :label="`${satellite.name} · ${satellite.payloads.length} 个载荷`" /></el-select></el-form-item></el-form></div>
    <el-card v-for="satellite in selectedSatellites" v-show="satellite.satelliteId === currentSatelliteId" :key="satellite.satelliteId" :data-payload-satellite-id="satellite.satelliteId" shadow="never" class="planning-card">
      <template #header><div class="compact-card-header"><span class="ballistic-muted">{{ satellite.payloads.length }} 个载荷</span><el-button :disabled="computing" :data-test="`add-ballistic-payload-${satellite.satelliteId}`" @click="addPayload(satellite)">新增载荷</el-button></div></template>
      <el-empty v-if="satellite.payloads.length === 0" :image-size="42" description="暂无载荷，可点击新增载荷" />
      <details v-for="payload in satellite.payloads" :key="payload.payloadId" :data-payload-id="payload.payloadId" class="ballistic-payload-item"><summary><strong>{{ payload.payloadName }} · {{ payload.payloadType }}</strong><span class="ballistic-payload-summary">距离 {{ payload.minDistanceKm }}–{{ payload.maxDistanceKm }} km · 方位 {{ payload.azimuthMinDeg }}–{{ payload.azimuthMaxDeg }}° · 俯仰 {{ payload.elevationMinDeg }}–{{ payload.elevationMaxDeg }}°</span><span class="ballistic-muted">展开编辑</span><span v-if="validateStandardPayload(payload).length" class="ballistic-invalid-indicator"> · {{ validateStandardPayload(payload).length }} 项待修正</span></summary>
        <div class="payload-identity">

          <el-form label-position="top"><el-form-item label="载荷名称"><el-input :value="payload.payloadName" :disabled="computing" @input="updatePayloadName(payload, $event)" /></el-form-item></el-form>
        </div>
        <StandardPayloadEditor compact :model-value="payload" :disabled="computing"
          @update:modelValue="updatePayload(payload, $event)" @selectType="selectPayloadType(payload, $event)" />
        <div class="payload-action">
          <el-button data-test="remove-ballistic-payload" :disabled="computing" @click="removePayload(satellite, payload)">删除载荷</el-button>
        </div>
      </details>
    </el-card>
    </section>
    </BallisticWindowControls>
  </section>
</template>
