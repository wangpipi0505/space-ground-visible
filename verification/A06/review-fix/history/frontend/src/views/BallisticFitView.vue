<script setup lang="ts">
/**
 * 原102完整JSON→原拟合→逐目标HTTP文件流。JSON是唯一输入，不另建解码后的观测草稿。
 * 提交快照与结果绑定；切换tab保留输入，失败清旧结果，忙时冻结编辑，无虚假进度。
 */
import { computed, ref, shallowRef } from 'vue'
import { fitBallisticPackage102 } from '../api/ballisticFit'
import type { BallisticFitHttpResult } from '../api/ballisticFit'
import type { BallisticFitRequest } from '../api/ballisticFitTypes'
import type { BallisticFileReference, GeneratedBallisticResult } from '../api/ballisticFiles'
import { saveBallisticBlob } from '../api/ballisticHttp'
import { parseFitInput } from '../utils/ballisticFit'
import { swingFitDefaults, swing102Columns, swing102CellOwner, swing102CellKey } from '../utils/ballisticSwingDefaults'
import type { Package102Record } from '../api/ballisticFitTypes'
import BallisticFitFileResult from '../components/BallisticFitFileResult.vue'

const emit = defineEmits<{ references: [value: GeneratedBallisticResult[]] }>()
const references = shallowRef<Record<number, GeneratedBallisticResult>>({})
const inputText = ref(JSON.stringify(swingFitDefaults(), null, 2))
const inputName = ref('')
const loadingInput = ref(false)
const computing = ref(false)
const error = ref('')
const result = shallowRef<BallisticFitHttpResult>()
const submitted = shallowRef<BallisticFitRequest>()
const submittedText = ref('')
let runSequence = 0
const runIdentity = ref(0)
/** 校验不发HTTP；提交预览反映outputDir明确省略，错误由用户点击计算后或导入时显示。 */
const parsed = computed(() => {
  try { return { value: parseFitInput(inputText.value), error: '' } }
  catch (failure) { return { value: undefined, error: message(failure) } }
})
/** 类型/步长错误与空表仍可在主表单修正；危险数字或损坏JSON仅在高级原文中修正。 */
const editable = computed(() => {
  if (parsed.value.error.includes('JSON数字') || parsed.value.error.includes('JSON格式')) return undefined
  try {
    const value = JSON.parse(inputText.value)
    return value && Array.isArray(value.package102) && value.package102.every((row: unknown) => {
      if (typeof row !== 'object' || row === null || Array.isArray(row)) return false
      return ['coordinateInfo','coordinate','coords','targetInfo','target'].every(key => {
        const alias = (row as Record<string, unknown>)[key]
        return alias == null || typeof alias === 'object' && !Array.isArray(alias)
      })
    }) ? value as BallisticFitRequest : undefined
  } catch { return undefined }
})
const changed = computed(() => submitted.value && inputText.value !== submittedText.value)
const taskFields = [{ key: 'taskName', label: '任务名称' }, { key: 'taskStartTime', label: '任务起始UTC' }, { key: 'taskEndTime', label: '任务结束UTC' }, { key: 'interpolationStepSec', label: '插值步长（秒）' }] as const
/** 表格只修改唯一JSON草稿的生效字段；旧嵌套别名、17位编码和未知字符串始终原值保留。 */
function editInput(event: Event, key: string, rowIndex?: number): void {
  if (!(event.target instanceof HTMLInputElement) || !editable.value) return
  const draft = JSON.parse(inputText.value)
  if (rowIndex === undefined) draft[key] = key === 'interpolationStepSec' && event.target.value.trim() && Number.isFinite(Number(event.target.value)) ? Number(event.target.value) : event.target.value
  else {
    const owner = swing102CellOwner(draft.package102[rowIndex], key as typeof swing102Columns[number])
    owner[swing102CellKey(owner, key)] = event.target.value
  }
  inputText.value = JSON.stringify(draft, null, 2)
}
function cellValue(row: Package102Record, key: typeof swing102Columns[number]): unknown {
  const owner = swing102CellOwner(row, key)
  return owner[swing102CellKey(owner, key)] ?? ''
}
/** 行增删直接处理同一草稿，不把既有记录摊平重建而丢失别名。 */
function changeRows(index?: number): void {
  if (!editable.value) return
  const draft = JSON.parse(inputText.value)
  if (index === undefined) draft.package102.push(Object.fromEntries(swing102Columns.map(key => [key, ''])))
  else draft.package102.splice(index, 1)
  inputText.value = JSON.stringify(draft, null, 2)
}
function loadDefault(): void { inputText.value = JSON.stringify(swingFitDefaults(), null, 2); inputName.value = ''; error.value = '' }
function message(failure: unknown): string { return failure instanceof Error ? failure.message : '请求失败，请检查服务连接' }

/** 原生选择仅在浏览器读UTF8 JSON，不把本机路径当服务输入；失败保留待纠正的原文本。 */
async function chooseJson(event: Event): Promise<void> {
  const element = event.target
  if (!(element instanceof HTMLInputElement)) return
  const file = element.files?.[0]
  if (!file) return
  loadingInput.value = true
  error.value = ''
  try {
    inputText.value = await file.text()
    inputName.value = file.name
    parseFitInput(inputText.value)
  } catch (failure) { error.value = message(failure) }
  finally { loadingInput.value = false; element.value = '' }
}

/** 提交JSON经过边界校验且独立于编辑文本；开始新尝试立即清旧成功，响应只绑定本次快照。 */
async function calculate(): Promise<void> {
  if (computing.value || loadingInput.value) return
  references.value = {}; emit('references', [])
  result.value = undefined
  submitted.value = undefined
  error.value = ''
  try {
    const { request } = parseFitInput(inputText.value)
    submittedText.value = inputText.value
    computing.value = true
    const response = await fitBallisticPackage102(request)
    submitted.value = request
    result.value = response
    runSequence += 1
    runIdentity.value = runSequence
  } catch (failure) { error.value = message(failure) }
  finally { computing.value = false }
}
/** 共享文件真实201事件逐文件更新；清自身拟合候选不影响App其它来源。 */
function updateReference(index: number, reference: BallisticFileReference | undefined): void {
  const file = result.value?.response.files[index]
  if (!file) return
  const next = { ...references.value }
  if (reference) next[index] = { targetId: file.targetId, targetName: `拟合/${result.value?.response.taskName}/${file.targetId}`, reference }
  else delete next[index]
  references.value = next; emit('references', Object.values(next))
}
/** 输入快照仅适配outputDir；计算结果JSON下载保持原HTTP字节，不与图表数据混合。 */
function exportInput(): void {
  if (submitted.value) saveBallisticBlob(new Blob([JSON.stringify(submitted.value, null, 2)], { type: 'application/json;charset=utf-8' }), '102包实际提交.json')
}
function exportResult(): void {
  if (result.value) saveBallisticBlob(new Blob([result.value.rawBytes], { type: 'application/json;charset=utf-8' }), '102包原拟合响应.json')
}
</script>

<template>
  <section class="ballistic-fit-view ballistic-window-controls" aria-label="102包轨迹拟合">
    <h2>102包轨迹拟合</h2>
    <details><summary>高级协议说明（编码、兼容别名与单位）</summary>
    <p>完整 FitRequest JSON 输入：package102 保留原字符串编码、扁平 tack3d 历史拼写 / track3d 时间别名及 coordinateInfo / coordinate / coords、targetInfo / target。位置 0.1 m、速度 0.0001 m/s、加速度 0.01 m/s²；原别名优先级和模型选择由服务执行。</p>
    <p>taskStartTime / taskEndTime 为字面 UTC 任务区间，不是物理起落时刻；浏览器时区不转换。interpolationStepSec 为秒，省略默认 1，至少 0.001。时间码、ID、代码和观测值按原 DTO 加引号，禁止危险数字舍入。</p>
    <p class="ballistic-storage-notice">Web 使用服务管理结果目录，提交始终省略 outputDir；不使用浏览器本机或自填服务目录。</p>
    </details>
    <fieldset class="ballistic-native-form" :disabled="computing || loadingInput">
      <legend>原102包与任务参数</legend>
      <button type="button" data-test="load-default-fit" @click="loadDefault">加载原默认102数据</button>
      <div v-if="editable" class="ballistic-field-grid"><label v-for="field in taskFields" :key="field.key">{{ field.label }}<input :value="editable[field.key] ?? ''" :data-fit-field="field.key" :inputmode="field.key === 'interpolationStepSec' ? 'decimal' : undefined" @input="editInput($event, field.key)" /></label></div>
      <p>一行即一个目标观测点。位置编码 0.1米，速度编码 0.0001米/秒，加速度编码 0.01米/秒²；时间码、ID和代码直接保留字符串。横向滚动查看全部28列。</p>
      <button type="button" @click="changeRows()">新增102记录</button>
      <div v-if="editable" class="ballistic-table-scroll"><table><thead><tr><th>操作</th><th v-for="key in swing102Columns" :key="key">{{ key }}</th></tr></thead><tbody><tr v-for="(row,index) in editable.package102" :key="index" :data-fit-row="index"><td><button type="button" @click="changeRows(index)">删除第{{ index + 1 }}行</button></td><td v-for="key in swing102Columns" :key="key"><input :value="cellValue(row, key)" :aria-label="`第${index + 1}行 ${key}`" :data-fit-cell="`${index}/${key}`" @input="editInput($event, key, index)" /></td></tr></tbody></table></div>
      <details><summary>高级设置：完整JSON导入与兼容字段</summary>
      <label>选择完整请求 JSON<input type="file" accept=".json,application/json" @change="chooseJson" /></label>
      <p v-if="inputName">已读取 {{ inputName }}（本地原文保留，可在下方编辑）</p>
      <label class="ballistic-json-label">完整 FitRequest JSON<textarea v-model="inputText" data-test="fit-input" rows="18" spellcheck="false" /></label>
      </details>
      <p v-if="parsed.error" role="alert" class="ballistic-error">输入待纠正：{{ parsed.error }}；可在高级JSON中修正。</p>
      <p v-if="parsed.value">{{ parsed.value.request.package102.length }} 条原包 · 任务 {{ parsed.value.request.taskName ?? 'task（原缺省）' }} · 步长 {{ parsed.value.request.interpolationStepSec ?? 1 }} 秒</p>
      <p v-if="parsed.value?.omittedOutputDir" data-test="fit-storage-notice">导入含 outputDir：已明确改用服务管理目录，实际请求将省略该字段；其余包与任务参数保持原值。</p>
      <details v-if="parsed.value"><summary>实际提交预览（outputDir 已省略）</summary><pre>{{ JSON.stringify(parsed.value.request, null, 2) }}</pre></details>
    </fieldset>
    <p v-if="loadingInput" role="status">正在读取本地 JSON…</p>
    <p v-if="computing" role="status">正在执行原102包拟合，请等待服务响应…</p>
    <p v-if="error" role="alert" class="ballistic-error">{{ error }}</p>
    <button type="button" data-test="fit-compute" :disabled="computing || loadingInput" @click="calculate">{{ computing ? '拟合请求进行中…' : '执行原102包拟合' }}</button>
    <section v-if="result && submitted" data-test="fit-success" aria-label="本次原拟合结果">
      <h3>原拟合已成功 · {{ result.response.taskName }} · {{ result.response.targetCount }} 个目标</h3>
      <p v-if="changed" role="status">输入已修改：下面结果仍绑定上次实际提交快照，请重新拟合以获得新输入结果。</p>
      <div class="ballistic-actions"><button type="button" @click="exportInput">下载实际提交 JSON</button><button type="button" @click="exportResult">下载原拟合响应 JSON</button></div>
      <details><summary>不可变实际提交快照（outputDir 省略）</summary><pre data-test="fit-submitted">{{ JSON.stringify(submitted, null, 2) }}</pre></details>
      <details><summary>本次全部原102包（原字符串/别名/单位保留）</summary><pre>{{ JSON.stringify(submitted.package102, null, 2) }}</pre></details>
      <BallisticFitFileResult v-for="(file, index) in result.response.files" :key="`${runIdentity}/${index}/${file.targetId}`" :file="file" :task-name="result.response.taskName" @reference="updateReference(index, $event)" />
    </section>
  </section>
</template>

<style scoped>
.ballistic-json-label { display: grid; gap: 8px; margin: 12px 0; }
textarea { box-sizing: border-box; width: 100%; font-family: monospace; line-height: 1.5; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 380px; overflow: auto; }
</style>
