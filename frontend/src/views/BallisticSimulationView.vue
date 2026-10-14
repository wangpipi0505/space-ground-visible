<script setup lang="ts">
/** 正式模拟页：完整原JSON为唯一草稿；核心编辑不覆盖高级事件/别名。快照与真实201引用分离，由App供窗口显式选择。 */
import { computed, ref, shallowRef } from 'vue'
import { simulateBallisticTrajectory } from '../api/ballisticSimulation'
import type { BallisticSimulationRequest } from '../api/ballisticSimulation'
import type { BallisticFitHttpResult } from '../api/ballisticFit'
import type { BallisticFileReference, GeneratedBallisticResult } from '../api/ballisticFiles'
import { saveBallisticBlob } from '../api/ballisticHttp'
import { parseSimulationInput } from '../utils/ballisticSimulation'
import BallisticFitFileResult from '../components/BallisticFitFileResult.vue'
const emit = defineEmits<{ references: [value: GeneratedBallisticResult[]] }>()
/** 任务/物理UTC与两组三维米端点恢复原Swing默认；用户仍可显式清空落点时间沿原服务估计。 */
const defaultInput = { taskName: 'defaultSimTask', targetId: 'SIM-001', launchTimeUtc: '2026-06-04 04:00:00.000', impactTimeUtc: '2026-06-04 04:42:55.000', taskStartTime: '2026-06-04 04:00:00.000', taskEndTime: '2026-06-04 04:42:55.000', launchPointX: 2167790.227, launchPointY: 4775481.109, launchPointZ: 3618557.253, launchPointCoordFrame: 'J2000', launchPointUnit: 'm', impactPointX: 3578635.760, impactPointY: 3522525.227, impactPointZ: 3919735.591, impactPointCoordFrame: 'J2000', impactPointUnit: 'm', outputPointDurationSec: 1, outputStepSec: 1, phaseEvents: [] }
const inputText = ref(JSON.stringify(defaultInput, null, 2))
const inputName = ref(''), error = ref(''), submittedText = ref('')
const loadingInput = ref(false), computing = ref(false)
const result = shallowRef<BallisticFitHttpResult>(), submitted = shallowRef<BallisticSimulationRequest>()
const references = shallowRef<Record<number, GeneratedBallisticResult>>({})
const runIdentity = ref(0)
const fields: Array<{ key: keyof BallisticSimulationRequest; label: string; numeric?: boolean }> = [
  { key: 'taskName', label: '任务名称（null/省略沿原缺省）' }, { key: 'targetId', label: '目标标识' },
  { key: 'launchTimeUtc', label: '发射 UTC（原字符串，必填）' }, { key: 'impactTimeUtc', label: '落点 UTC（可空，优先于impactTime）' },
  { key: 'taskStartTime', label: '任务起始UTC' }, { key: 'taskEndTime', label: '任务结束UTC' },
  { key: 'outputPointDurationSec', label: '输出间隔（秒，非正沿原回退）', numeric: true },
  ...(['launch', 'impact'] as const).flatMap(prefix => [
    ...(['X', 'Y', 'Z'] as const).map(axis => ({ key: `${prefix}Point${axis}` as keyof BallisticSimulationRequest, label: `${prefix === 'launch' ? '发射' : '落'}点 ${axis}`, numeric: true })),
    { key: `${prefix}PointCoordFrame` as keyof BallisticSimulationRequest, label: `${prefix === 'launch' ? '发射' : '落'}点坐标系（原别名可输入）` },
    { key: `${prefix}PointUnit` as keyof BallisticSimulationRequest, label: `${prefix === 'launch' ? '发射' : '落'}点单位（原别名/比例）` },
  ]),
]
function message(failure: unknown): string { return failure instanceof Error ? failure.message : '请求失败，请检查服务连接' }
const parsed = computed(() => { try { return { value: parseSimulationInput(inputText.value), error: '' } } catch (failure) { return { value: undefined, error: message(failure) } } })
/** 非法单字段仍显示其原文字串，允许继续输入小数/负号；危险数字和损坏JSON保持高级原文。 */
const editable = computed(() => {
  if (parsed.value.error.includes('JSON数字') || parsed.value.error.includes('JSON格式') || parsed.value.error.includes('JSON对象')) return undefined
  try { const value = JSON.parse(inputText.value); return value && !Array.isArray(value) ? value as BallisticSimulationRequest : undefined } catch { return undefined }
})
const changed = computed(() => submitted.value && inputText.value !== submittedText.value)
/** 更新唯一JSON中一个字段；非法数值原文保留为明确待纠正字符串，不能偷偷沿用旧合法值；空数值明确null。 */
function editField(key: keyof BallisticSimulationRequest, numeric: boolean | undefined, event: Event): void {
  if (!editable.value || !(event.target instanceof HTMLInputElement)) return
  const value: unknown = JSON.parse(inputText.value), text = event.target.value
  if (typeof value !== 'object' || value === null) return
  let edited: string | number | null = text
  error.value = ''
  if (numeric) {
    edited = text.trim() ? text : null
    if (text.trim()) {
      try {
        if (!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(text.trim())) throw new Error(`${key}须为原JSON有限数`)
        parseSimulationInput(`{"${key}":${text}}`)
        edited = Number(text)
      } catch (failure) { error.value = `${key}输入待纠正：${message(failure)}；请在完整JSON修正该原文字段` }
    }
  }
  Object.assign(value, { [key]: edited })
  inputText.value = JSON.stringify(value, null, 2)
}
/** 浏览器本地File仅UTF8文本，非法输入不发HTTP、不提交本机路径。 */
async function chooseJson(event: Event): Promise<void> {
  if (!(event.target instanceof HTMLInputElement)) return
  const input = event.target, file = input.files?.[0]
  if (!file) return
  loadingInput.value = true; error.value = ''
  try { inputText.value = await file.text(); inputName.value = file.name; parseSimulationInput(inputText.value) }
  catch (failure) { error.value = message(failure) }
  finally { loadingInput.value = false; input.value = '' }
}
/** 新尝试清旧成功/引用；服务结果只绑定本次独立解析快照，后续编辑只标过期。 */
async function calculate(): Promise<void> {
  if (computing.value || loadingInput.value) return
  result.value = undefined; submitted.value = undefined; references.value = {}; emit('references', []); error.value = ''
  try {
    const { request } = parseSimulationInput(inputText.value)
    submittedText.value = inputText.value; computing.value = true
    const response = await simulateBallisticTrajectory(request)
    submitted.value = request; result.value = response; runIdentity.value += 1
  } catch (failure) { error.value = message(failure) }
  finally { computing.value = false }
}
/** 只接收共享组件真实201引用；按文件撤销/重登记，不由csvPath伪造身份。 */
function updateReference(index: number, reference: BallisticFileReference | undefined): void {
  const file = result.value?.response.files[index]
  if (!file) return
  const next = { ...references.value }
  if (reference) next[index] = { targetId: file.targetId, targetName: `模拟/${result.value?.response.taskName}/${file.targetId}`, reference }
  else delete next[index]
  references.value = next; emit('references', Object.values(next))
}
function exportInput(): void { if (submitted.value) saveBallisticBlob(new Blob([JSON.stringify(submitted.value, null, 2)], { type: 'application/json;charset=utf-8' }), '模拟实际提交.json') }
function exportResult(): void { if (result.value) saveBallisticBlob(new Blob([result.value.rawBytes], { type: 'application/json;charset=utf-8' }), '模拟原响应.json') }
</script>

<template>
  <section class="ballistic-simulation-view ballistic-window-controls" aria-label="模拟弹道">
    <h2>模拟弹道 · SYSTEM_SIMULATED_LAMBERT</h2>
    <details><summary>高级协议说明（坐标、别名与动力学边界）</summary>
    <p>当前唯一正式模型：端点约束 Lambert 射击法 / 二体 RK4。起落点与物理发射 UTC 决定轨迹；不是窗口卫星的 orbitModel。impactTimeUtc 优先 impactTime；两者空/省略时由原系统估计落点时间。</p>
    <p>端点独立坐标口径：J2000 / ECI / ECIJ2000；ECEF / ITRF / WGS84ECEF；LLA / LLH / BLH / GEODETIC / WGS84 / WGS84LLA。大小写、空格、连字符、下划线按原Java规范化。WGS84 的 X=经度度，Y=纬度度，Z=高度米，unit 被原服务忽略；笛卡尔单位 m / meter / meters、km / kilometer / kilometers、0.1m / dm / package102 / 102（均0.1米），或原数值比例。</p>
    <p>所有时间为原 UTC 字符串，不转换北京时间。outputPointDurationSec 首个正数优先 outputStepSec，否则回退1秒，再由原服务要求至少0.001秒。taskStartTime / taskEndTime 仅任务元数据；初速度、integrationStepSec、boost、drag 等旧字段兼容保留但当前模型不用。phaseEvents 原值保留，当前只产生预留警告，不启用分段动力学。</p>
    <p class="ballistic-storage-notice">Web 始终省略 outputDir，使用服务管理目录；不要求客户端输出目录。</p>
    </details>
    <fieldset :disabled="computing || loadingInput" class="ballistic-native-form"><legend>原 SimulationRequest 任务与端点</legend>
      <button type="button" data-test="load-default-simulation" @click="inputText = JSON.stringify(defaultInput, null, 2); inputName = ''; error = ''">加载原默认模拟数据</button>
      <p v-if="inputName">已读取 {{ inputName }}（完整原文可编辑）</p>
      <div v-if="editable" class="ballistic-field-grid"><label v-for="field in fields" :key="field.key">{{ field.label }}<input :value="editable[field.key] ?? ''" :data-simulation-field="field.key" type="text" :inputmode="field.numeric ? 'decimal' : undefined" @input="editField(field.key, field.numeric, $event)" /></label></div>
      <details><summary>高级设置：完整JSON与兼容字段</summary><label>原生选择完整请求 JSON<input type="file" accept=".json,application/json" @change="chooseJson" /></label>
      <label class="ballistic-json-label">完整原JSON（全部35字段与嵌套PhaseEvent，单一真实草稿）<textarea v-model="inputText" rows="16" data-test="simulation-input" spellcheck="false" /></label>
      </details>
      <p v-if="parsed.error" role="status">输入待纠正：{{ parsed.error }}</p>
      <p v-if="parsed.value?.omittedOutputDir" data-test="simulation-storage-notice">导入含 outputDir：已明确改用服务管理目录，实际提交仅省略该字段，其余原值不变。</p>
      <details v-if="parsed.value"><summary>实际提交预览（outputDir省略）</summary><pre>{{ JSON.stringify(parsed.value.request, null, 2) }}</pre></details>
    </fieldset>
    <p v-if="computing || loadingInput" role="status">{{ computing ? '正在执行原端点模拟，请等待服务响应…' : '正在读取本地JSON…' }}</p>
    <p v-if="error" class="ballistic-error" role="alert">{{ error }}</p>
    <button type="button" data-test="simulation-compute" :disabled="computing || loadingInput" @click="calculate">{{ computing ? '模拟请求进行中…' : '执行原模拟弹道' }}</button>
    <section v-if="result && submitted" data-test="simulation-success" aria-label="本次原模拟结果">
      <h3>原模拟已成功 · {{ result.response.taskName }} · {{ result.response.targetCount }} 个目标</h3>
      <p v-if="changed" role="status">输入已修改：结果和可选服务引用仍属于上次实际提交快照，请重新模拟获取新输入结果。</p>
      <p>真实登记成功的结果可在“可视窗口计算”的窗口文件来源中显式选择；目标时间沿CSV历元，不自动平移。</p>
      <div class="ballistic-actions"><button type="button" @click="exportInput">下载实际提交 JSON</button><button type="button" @click="exportResult">下载原模拟响应 JSON</button></div>
      <details><summary>不可变实际提交快照（outputDir省略）</summary><pre data-test="simulation-submitted">{{ JSON.stringify(submitted, null, 2) }}</pre></details>
      <BallisticFitFileResult v-for="(file, index) in result.response.files" :key="`${runIdentity}/${index}`" :file="file" :task-name="result.response.taskName" result-label="原模拟" @reference="updateReference(index, $event)" />
    </section>
  </section>
</template>

<style scoped>
.ballistic-json-label { display: grid; gap: 8px; margin: 12px 0; }
textarea { box-sizing: border-box; width: 100%; font-family: monospace; line-height: 1.5; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 380px; overflow: auto; }
</style>
