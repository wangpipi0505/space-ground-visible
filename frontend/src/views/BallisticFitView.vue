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
import BallisticFitFileResult from '../components/BallisticFitFileResult.vue'

const emit = defineEmits<{ references: [value: GeneratedBallisticResult[]] }>()
const references = shallowRef<Record<number, GeneratedBallisticResult>>({})
const inputText = ref('{\n  "taskName": "task",\n  "taskStartTime": "",\n  "taskEndTime": "",\n  "interpolationStepSec": 1,\n  "package102": []\n}')
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
const changed = computed(() => submitted.value && inputText.value !== submittedText.value)
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
    <p>完整 FitRequest JSON 输入：package102 保留原字符串编码、扁平 tack3d 历史拼写 / track3d 时间别名及 coordinateInfo / coordinate / coords、targetInfo / target。位置 0.1 m、速度 0.0001 m/s、加速度 0.01 m/s²；原别名优先级和模型选择由服务执行。</p>
    <p>taskStartTime / taskEndTime 为字面 UTC 任务区间，不是物理起落时刻；浏览器时区不转换。interpolationStepSec 为秒，省略默认 1，至少 0.001。时间码、ID、代码和观测值按原 DTO 加引号，禁止危险数字舍入。</p>
    <p class="ballistic-storage-notice">Web 使用服务管理结果目录，提交始终省略 outputDir；不使用浏览器本机或自填服务目录。</p>
    <fieldset class="ballistic-native-form" :disabled="computing || loadingInput">
      <legend>原102包与任务参数</legend>
      <label>选择完整请求 JSON<input type="file" accept=".json,application/json" @change="chooseJson" /></label>
      <p v-if="inputName">已读取 {{ inputName }}（本地原文保留，可在下方编辑）</p>
      <label class="ballistic-json-label">完整 FitRequest JSON<textarea v-model="inputText" data-test="fit-input" rows="18" spellcheck="false" /></label>
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
