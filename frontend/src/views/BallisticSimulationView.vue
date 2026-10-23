<script>
export default { name: 'BallisticSimulationView' }
</script>

<script setup>
/** 正式模拟页：完整原JSON为唯一草稿；核心编辑不覆盖高级事件/别名。快照与真实201引用分离，由App供窗口显式选择。 */
import { computed, ref, shallowRef } from 'vue'
import { simulateBallisticTrajectory } from '../api/ballisticSimulation'
import { saveBallisticBlob } from '../api/ballisticHttp'
import { parseSimulationInput } from '../utils/ballisticSimulation'
import BallisticFitFileResult from '../components/BallisticFitFileResult.vue'
const emit = defineEmits(['references'])
/** 任务/物理UTC与两组三维米端点恢复原Swing默认；用户仍可显式清空落点时间沿原服务估计。 */
const defaultInput = {
  taskName: 'defaultSimTask',
  targetId: 'SIM-001',
  launchTimeUtc: '2026-06-04 04:00:00.000',
  impactTimeUtc: '2026-06-04 04:42:55.000',
  taskStartTime: '2026-06-04 04:00:00.000',
  taskEndTime: '2026-06-04 04:42:55.000',
  launchPointX: 2167790.227,
  launchPointY: 4775481.109,
  launchPointZ: 3618557.253,
  launchPointCoordFrame: 'J2000',
  launchPointUnit: 'm',
  impactPointX: 3578635.76,
  impactPointY: 3522525.227,
  impactPointZ: 3919735.591,
  impactPointCoordFrame: 'J2000',
  impactPointUnit: 'm',
  outputPointDurationSec: 1,
  outputStepSec: 1,
  phaseEvents: [],
}
const inputText = ref(JSON.stringify(defaultInput, null, 2))
const inputName = ref(''),
  error = ref(''),
  submittedText = ref('')
const loadingInput = ref(false),
  computing = ref(false)
const result = shallowRef(),
  submitted = shallowRef()
const references = shallowRef({})
const runIdentity = ref(0)
const fields = [
  { key: 'taskName', label: '任务名称' },
  { key: 'targetId', label: '目标标识' },
  { key: 'launchTimeUtc', label: '发射 UTC' },
  { key: 'impactTimeUtc', label: '落点 UTC（可空）' },
  { key: 'taskStartTime', label: '任务起始UTC' },
  { key: 'taskEndTime', label: '任务结束UTC' },
  { key: 'outputPointDurationSec', label: '输出间隔（秒）', numeric: true },
  ...['launch', 'impact'].flatMap((prefix) => [
    ...['X', 'Y', 'Z'].map((axis) => ({
      key: `${prefix}Point${axis}`,
      label: `${prefix === 'launch' ? '发射' : '落'}点 ${axis}`,
      numeric: true,
    })),
    { key: `${prefix}PointCoordFrame`, label: `${prefix === 'launch' ? '发射' : '落'}点坐标系` },
    { key: `${prefix}PointUnit`, label: `${prefix === 'launch' ? '发射' : '落'}点单位` },
  ]),
]
function message(failure) {
  return failure instanceof Error ? failure.message : '请求失败，请检查服务连接'
}
const parsed = computed(() => {
  try {
    return { value: parseSimulationInput(inputText.value), error: '' }
  } catch (failure) {
    return { value: undefined, error: message(failure) }
  }
})
/** 非法单字段仍显示其原文字串，允许继续输入小数/负号；危险数字和损坏JSON保持高级原文。 */
const editable = computed(() => {
  if (
    parsed.value.error.includes('JSON数字') ||
    parsed.value.error.includes('JSON格式') ||
    parsed.value.error.includes('JSON对象')
  )
    return undefined
  try {
    const value = JSON.parse(inputText.value)
    return value && !Array.isArray(value) ? value : undefined
  } catch {
    return undefined
  }
})
const changed = computed(() => submitted.value && inputText.value !== submittedText.value)
/** 更新唯一JSON中一个字段；非法数值原文保留为明确待纠正字符串，不能偷偷沿用旧合法值；空数值明确null。 */
function editField(key, numeric, textValue) {
  if (!editable.value) return
  const value = JSON.parse(inputText.value),
    text = String(textValue)
  if (typeof value !== 'object' || value === null) return
  let edited = text
  error.value = ''
  if (numeric) {
    edited = text.trim() ? text : null
    if (text.trim()) {
      try {
        if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(text.trim())) throw new Error(`${key}须为原JSON有限数`)
        parseSimulationInput(`{"${key}":${text}}`)
        edited = Number(text)
      } catch (failure) {
        error.value = `${key}输入待纠正：${message(failure)}；请在完整JSON修正该原文字段`
      }
    }
  }
  Object.assign(value, { [key]: edited })
  inputText.value = JSON.stringify(value, null, 2)
}
/** 浏览器本地File仅UTF8文本，非法输入不发HTTP、不提交本机路径。 */
async function chooseJson(uploadFile) {
  const file = uploadFile.raw
  if (!file) return
  loadingInput.value = true
  error.value = ''
  try {
    inputText.value = await file.text()
    inputName.value = file.name
    parseSimulationInput(inputText.value)
  } catch (failure) {
    error.value = message(failure)
  } finally {
    loadingInput.value = false
  }
}
/** 新尝试清旧成功/引用；服务结果只绑定本次独立解析快照，后续编辑只标过期。 */
async function calculate() {
  if (computing.value || loadingInput.value) return
  result.value = undefined
  submitted.value = undefined
  references.value = {}
  emit('references', [])
  error.value = ''
  try {
    const { request } = parseSimulationInput(inputText.value)
    submittedText.value = inputText.value
    computing.value = true
    const response = await simulateBallisticTrajectory(request)
    submitted.value = request
    result.value = response
    runIdentity.value += 1
  } catch (failure) {
    error.value = message(failure)
  } finally {
    computing.value = false
  }
}
/** 只接收共享组件真实201引用；按文件撤销/重登记，不由csvPath伪造身份。 */
function updateReference(index, reference) {
  const file = result.value?.response.files[index]
  if (!file) return
  const next = { ...references.value }
  if (reference)
    next[index] = {
      targetId: file.targetId,
      targetName: `模拟/${result.value?.response.taskName}/${file.targetId}`,
      reference,
    }
  else delete next[index]
  references.value = next
  emit('references', Object.values(next))
}
function exportInput() {
  if (submitted.value)
    saveBallisticBlob(
      new Blob([JSON.stringify(submitted.value, null, 2)], { type: 'application/json;charset=utf-8' }),
      '模拟实际提交.json',
    )
}
function exportResult() {
  if (result.value)
    saveBallisticBlob(new Blob([result.value.rawBytes], { type: 'application/json;charset=utf-8' }), '模拟原响应.json')
}
</script>

<template>
  <section class="ballistic-simulation-view ballistic-workspace" aria-label="模拟弹道">
    <header class="ballistic-workspace-header"><h2>模拟弹道</h2><span class="ballistic-source-badge">端点约束模拟</span></header>
    <div class="ballistic-workbench-grid">
      <div class="ballistic-input-column">
        <div class="ballistic-input-scroll">
        <fieldset :disabled="computing || loadingInput" class="ballistic-native-form ballistic-panel"><legend>任务与端点</legend>
          <el-button data-test="load-default-simulation" @click="inputText = JSON.stringify(defaultInput, null, 2); inputName = ''; error = ''">加载默认数据</el-button>
          <p v-if="inputName">已读取 {{ inputName }}</p>
          <el-form v-if="editable" label-position="top" class="ballistic-field-grid"><el-form-item v-for="field in fields" :key="field.key" :label="field.label"><el-input :value="editable[field.key] ?? ''" :data-simulation-field="field.key" :inputmode="field.numeric ? 'decimal' : undefined" @input="editField(field.key, field.numeric, $event)" /></el-form-item></el-form>
          <p class="ballistic-field-hint">时间均为UTC。落点时间留空时自动估计；坐标值按各端点单位输入。</p>
          <details><summary>高级设置 · JSON导入与编辑</summary>
            <el-form label-position="top"><el-form-item label="导入请求 JSON"><el-upload action="" :auto-upload="false" :show-file-list="false" accept=".json,application/json" :disabled="computing || loadingInput" :on-change="chooseJson"><el-button>选择 JSON 文件</el-button></el-upload></el-form-item></el-form>
            <el-form label-position="top" class="ballistic-json-label"><el-form-item label="完整请求 JSON"><el-input v-model="inputText" type="textarea" :rows="16" data-test="simulation-input" spellcheck="false" /></el-form-item></el-form>
            <p v-if="parsed.value?.omittedOutputDir" data-test="simulation-storage-notice">导入的 outputDir 将省略，结果保存到服务管理目录。</p>
          </details>
          <details><summary>坐标、时间与模型说明</summary>
            <p>端点约束 Lambert 射击法 / 二体 RK4 模型。发射和落点坐标分别指定坐标系与单位；所有时间按字面UTC提交，不转换本地时区。任务起止时间仅为任务信息。</p>
            <p>坐标系支持 J2000 / ECI / ECIJ2000、ECEF / ITRF / WGS84ECEF、LLA / LLH / BLH / GEODETIC / WGS84 / WGS84LLA；大小写、空格、连字符和下划线按服务规则归一。WGS84下 X=经度（度）、Y=纬度（度）、Z=高度（米），忽略单位字段。</p>
            <p>笛卡尔单位支持 m / meter / meters、km / kilometer / kilometers、0.1m / dm / package102 / 102（0.1米）及数值比例。</p>
            <p>impactTimeUtc 优先于 impactTime；均为空时估计落点时间。outputPointDurationSec 的正数优先于 outputStepSec，否则回退1秒；最小间隔0.001秒。任务名称为null或省略时使用默认名称。</p>
            <p>高级JSON保留全部35字段及 PhaseEvent。初速度、integrationStepSec、boost、drag 等兼容字段不参与当前模型；phaseEvents仅返回预留警告，不启用分段动力学。outputDir由服务管理。</p>
          </details>
          <p v-if="parsed.error" role="alert" class="ballistic-error">输入待纠正：{{ parsed.error }}</p>
        </fieldset>
        </div>
        <div class="ballistic-compute-bar">
          <p v-if="loadingInput" role="status">正在读取 JSON…</p>
          <p v-if="error" class="ballistic-error" role="alert">{{ error }}</p>
          <el-button type="primary" class="ballistic-primary-action" data-test="simulation-compute" :disabled="computing || loadingInput" @click="calculate">{{ computing ? '正在模拟…' : '开始模拟' }}</el-button>
        </div>
      </div>
      <aside class="ballistic-results-column ballistic-panel" aria-label="模拟结果" :aria-busy="computing">
        <h3>模拟结果</h3>
        <div v-if="!result" class="ballistic-empty-state" role="status"><p>{{ computing ? '正在模拟，请等待结果。' : '完成左侧任务与端点设置，点击“开始模拟”。' }}</p></div>
        <section v-if="result && submitted" data-test="simulation-success" aria-label="本次模拟结果">
          <h3>{{ result.response.taskName }} · {{ result.response.targetCount }} 个目标</h3>
          <p v-if="changed" role="status">输入已修改，当前结果来自上次计算。重新模拟以更新结果。</p>
          <p class="ballistic-field-hint">结果可用于可视窗口计算，目标时间沿用CSV历元。</p>
          <div class="ballistic-actions"><el-button @click="exportInput">下载输入 JSON</el-button><el-button @click="exportResult">下载结果 JSON</el-button></div>
          <details><summary>计算输入快照</summary><pre data-test="simulation-submitted">{{ JSON.stringify(submitted, null, 2) }}</pre></details>
          <BallisticFitFileResult v-for="(file, index) in result.response.files" :key="`${runIdentity}/${index}`" :file="file" :task-name="result.response.taskName" result-label="模拟" @reference="updateReference(index, $event)" />
        </section>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.ballistic-json-label { display: grid; gap: 8px; margin: 12px 0; }
.ballistic-json-label :deep(.el-textarea__inner) { font-family: Consolas, monospace; line-height: 1.5; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 380px; overflow: auto; }
</style>
