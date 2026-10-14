<script setup lang="ts">
/** 正式重定时页：原生CSV上传或当前会话已登记结果→原请求→共享分页/完整曲线/原字节下载。
 * ECEF保持原轨迹，原服务按新UTC重算J2000 ECI；不在浏览器重拟合、转换时间或拼本机路径。
 * props为App三来源合并候选，references仅上报本页本次真实201结果，撤销不影响其它来源。
 */
import { computed, ref, shallowRef, watch } from 'vue'
import { retimeBallisticTrajectory } from '../api/ballisticRetime'
import type { BallisticRetimeRequest } from '../api/ballisticRetime'
import type { BallisticFitHttpResult } from '../api/ballisticFit'
import { uploadBallisticFile, previewBallisticFile } from '../api/ballisticFiles'
import type { BallisticFileReference, BallisticFilePreview, GeneratedBallisticResult } from '../api/ballisticFiles'
import { saveBallisticBlob } from '../api/ballisticHttp'
import BallisticFitFileResult from '../components/BallisticFitFileResult.vue'

const props = defineProps<{ generatedResults?: GeneratedBallisticResult[] }>()
const emit = defineEmits<{ references: [value: GeneratedBallisticResult[]] }>()
const sourceMode = ref<'upload' | 'generated'>('upload'), generatedId = ref('')
const source = shallowRef<BallisticFileReference>(), preview = shallowRef<BallisticFilePreview>()
const taskName = ref('retimeTrajectory'), targetId = ref('RET-001'), launchTimeUtc = ref('2026-07-01 09:00:00.000')
const nullTask = ref(false), nullTarget = ref(false)
const uploading = ref(false), computing = ref(false), previewing = ref(false)
const error = ref(''), previewError = ref('')
const result = shallowRef<BallisticFitHttpResult>(), submitted = shallowRef<BallisticRetimeRequest>()
const references = shallowRef<Record<number, GeneratedBallisticResult>>({}), runIdentity = ref(0)
let sourceIdentity = 0
function message(failure: unknown): string { return failure instanceof Error ? failure.message : '请求失败，请检查服务连接' }
/** 唯一表单到四原字段；字符串/null同值，UTC保留字面原文，绝不经Date或默认北京时间。 */
function draft(): BallisticRetimeRequest {
  return { taskName: nullTask.value ? null : taskName.value, targetId: nullTarget.value ? null : targetId.value, trajectoryCsvPath: source.value?.serverPath || '', launchTimeUtc: launchTimeUtc.value }
}
const changed = computed(() => submitted.value && JSON.stringify(draft()) !== JSON.stringify(submitted.value))
/** 来源切换/新上传先撤旧引用；异步序号保证旧慢上传/预览不会覆盖新来源身份。 */
function clearSource(): void {
  sourceIdentity += 1; source.value = undefined; preview.value = undefined; previewError.value = ''; generatedId.value = ''; previewing.value = false
}
watch(sourceMode, clearSource)
watch(() => props.generatedResults, () => {
  if (sourceMode.value !== 'generated' || !generatedId.value) return
  const current = props.generatedResults?.find(item => item.reference.fileId === generatedId.value && item.reference.serverPath === source.value?.serverPath)
  if (!current) { clearSource(); error.value = '原选择结果已撤销或失效，请重新选择真实登记结果' }
})
/** 预览只作辅助；成功上传并不代表原CSV合法，预览失败仍可交给原算法按原顺序校验。 */
async function loadSourcePreview(reference: BallisticFileReference, identity: number): Promise<void> {
  previewing.value = true; previewError.value = ''
  try { const data = await previewBallisticFile(reference.fileId); if (identity === sourceIdentity) preview.value = data }
  catch (failure) { if (identity === sourceIdentity) previewError.value = message(failure) }
  finally { if (identity === sourceIdentity) previewing.value = false }
}
async function chooseFile(event: Event): Promise<void> {
  if (!(event.target instanceof HTMLInputElement) || uploading.value || computing.value) return
  const input = event.target, file = input.files?.[0]
  clearSource(); error.value = ''
  if (!file) return
  const identity = sourceIdentity; uploading.value = true
  try {
    const reference = await uploadBallisticFile(file)
    if (identity !== sourceIdentity) return
    source.value = reference
    await loadSourcePreview(reference, identity)
  } catch (failure) { if (identity === sourceIdentity) error.value = message(failure) }
  finally { uploading.value = false; input.value = '' }
}
/** 使用原目标身份的实际登记引用，不重上传，不由响应路径伪造fileId。 */
async function chooseGenerated(event: Event): Promise<void> {
  if (!(event.target instanceof HTMLSelectElement) || computing.value) return
  const id = event.target.value; clearSource(); error.value = ''
  const selected = props.generatedResults?.find(item => item.reference.fileId === id)
  if (!selected) return
  generatedId.value = id; source.value = selected.reference
  await loadSourcePreview(selected.reference, sourceIdentity)
}
/** 先截取有效来源再撤自身旧候选：支持上次重定时再次输入，父级watch撤销不能把本次请求改为空。
 * 服务响应只绑定该不可变快照；新尝试/失败清旧成功，忙时防重复。共享文件组件key隔离旧慢登记。
 */
async function calculate(): Promise<void> {
  if (computing.value || uploading.value) return
  const request = draft()
  result.value = undefined; submitted.value = undefined; references.value = {}; emit('references', []); error.value = ''
  if (!request.trajectoryCsvPath) { error.value = '请选择上传成功或真实登记的CSV引用；不接受浏览器本机路径'; return }
  computing.value = true
  try { const response = await retimeBallisticTrajectory(request); submitted.value = request; result.value = response; runIdentity.value += 1 }
  catch (failure) { error.value = message(failure) }
  finally { computing.value = false }
}
/** 只接受本次挂载共享组件真实201事件；其撤销/重新登记事件删除本次对应文件候选。 */
function updateReference(index: number, reference: BallisticFileReference | undefined): void {
  const file = result.value?.response.files[index]
  if (!file) return
  const next = { ...references.value }
  if (reference) next[index] = { targetId: file.targetId, targetName: `重定时/${result.value?.response.taskName}/${file.targetId}`, reference }
  else delete next[index]
  references.value = next; emit('references', Object.values(next))
}
function exportInput(): void { if (submitted.value) saveBallisticBlob(new Blob([JSON.stringify(submitted.value, null, 2)], { type: 'application/json;charset=utf-8' }), '重定时实际提交.json') }
function exportResult(): void { if (result.value) saveBallisticBlob(new Blob([result.value.rawBytes], { type: 'application/json;charset=utf-8' }), '重定时原响应.json') }
</script>

<template>
  <section class="ballistic-retime-view ballistic-window-controls" aria-label="弹道重定时">
    <h2>弹道重定时 · RETIMED_TRAJECTORY</h2>
    <details><summary>高级协议说明（CSV字段与时间含义）</summary>
    <p>原CSV轨迹在ECEF地固系保持不变，新发射UTC改变后由原服务重新计算J2000 ECI位置和速度。UTC按原字符串提交，不加减北京时间。dt为累计相对首行毫秒，timeIntervalMs为相邻间隔毫秒。</p>
    <p>本接口仅支持原CSV：YYYY/MM/dd/HH/mm/ss.SSS 与 ECEF_X/Y/Z、ECEF_Vx/Vy/Vz、ECI_X/Y/Z、ECI_VX/VY/VZ 命名列。原20列 ECEF_X(m) 格式不能用于重定时。HTTP/MinIO/Excel输入能力仅属于窗口计算。</p>
    <p class="ballistic-storage-notice">Web 始终省略 outputDir，输出使用服务管理目录。已有结果为当前会话真实登记的模拟/拟合/重定时文件，刷新不持久化；不扫描服务目录。</p>
    </details>
    <fieldset class="ballistic-native-form" :disabled="computing || uploading">
      <legend>1. 选择原CSV服务引用</legend>
      <label>来源<select v-model="sourceMode" data-test="retime-source-mode"><option value="upload">原生上传CSV</option><option value="generated">服务已生成结果</option></select></label>
      <label v-if="sourceMode === 'upload'">选择原CSV<input type="file" accept=".csv,text/csv" @change="chooseFile" /></label>
      <label v-else>选择真实登记结果<select :value="generatedId" data-test="retime-result-select" @change="chooseGenerated"><option value="">请选择真实登记的模拟/拟合/重定时结果</option><option v-for="item in generatedResults || []" :key="item.reference.fileId" :value="item.reference.fileId">{{ item.targetName }} · 原目标 {{ item.targetId }} · {{ item.reference.originalName }}</option></select></label>
      <p v-if="source" data-test="retime-source-reference">真实引用 {{ source.fileId }} · {{ source.originalName }} · {{ source.sizeBytes }} 字节<br />服务路径 {{ source.serverPath }}</p>
      <p v-if="uploading" role="status">正在原生上传CSV并读取服务预览…</p>
      <p v-if="previewing" role="status">正在读取来源预览…</p>
      <p v-if="previewError" role="alert">来源预览失败（独立辅助错误，不代表原计算失败）：{{ previewError }}</p>
      <button v-if="previewError && source" type="button" :disabled="previewing" @click="loadSourcePreview(source, sourceIdentity)">重试来源预览</button>
      <details v-if="preview"><summary>来源服务原文本预览 · 共 {{ preview.totalRows }} 行</summary><div class="ballistic-table-scroll"><table><thead><tr><th v-for="(cell, index) in preview.header" :key="index">{{ cell }}</th></tr></thead><tbody><tr v-for="(row, index) in preview.rows" :key="index"><td v-for="(cell, column) in row" :key="column">{{ cell }}</td></tr></tbody></table></div></details>
    </fieldset>
    <fieldset class="ballistic-native-form" :disabled="computing || uploading"><legend>2. 原重定时参数</legend>
      <button type="button" data-test="load-default-retime" @click="taskName = 'retimeTrajectory'; targetId = 'RET-001'; launchTimeUtc = '2026-07-01 09:00:00.000'; nullTask = false; nullTarget = false">加载原默认重定时参数</button>
      <div class="ballistic-field-grid">
        <label>任务名称（原默认 retimeTrajectory）<input v-model="taskName" data-retime-field="taskName" :disabled="nullTask" /><span><input v-model="nullTask" type="checkbox" data-retime-null="taskName" /> 原 null</span></label>
        <label>目标ID（Swing默认 RET-001）<input v-model="targetId" data-retime-field="targetId" :disabled="nullTarget" /><span><input v-model="nullTarget" type="checkbox" data-retime-null="targetId" /> 原 null</span></label>
        <label>新发射 UTC（原字面，不转换本地时区）<input v-model="launchTimeUtc" data-retime-field="launchTimeUtc" placeholder="2026-07-02 07:57:00.000" /></label>
      </div>
      <details><summary>实际提交预览（四原字段，outputDir省略）</summary><pre>{{ JSON.stringify(draft(), null, 2) }}</pre></details>
    </fieldset>
    <p v-if="computing" role="status">正在执行原弹道重定时，请等待服务响应…</p>
    <p v-if="error" class="ballistic-error" role="alert">{{ error }}</p>
    <button type="button" data-test="retime-compute" :disabled="computing || uploading" @click="calculate">{{ computing ? '重定时请求进行中…' : '执行原弹道重定时' }}</button>
    <section v-if="result && submitted" data-test="retime-success" aria-label="本次原重定时结果">
      <h3>原重定时已成功 · {{ result.response.taskName }} · {{ result.response.targetCount }} 个目标</h3>
      <p v-if="changed" role="status">输入已修改：结果与可选引用仍绑定上次实际提交快照，请重新重定时获得新输入结果。</p>
      <p>登记成功后可在本页再次选择，或到“可视窗口计算”显式选入窗口计算；目标沿新CSV UTC，不再次平移。卫星epochUtc需明确同日。</p>
      <div class="ballistic-actions"><button type="button" @click="exportInput">下载实际提交 JSON</button><button type="button" @click="exportResult">下载原重定时响应 JSON</button></div>
      <details><summary>不可变实际提交快照</summary><pre data-test="retime-submitted">{{ JSON.stringify(submitted, null, 2) }}</pre></details>
      <BallisticFitFileResult v-for="(file, index) in result.response.files" :key="`${runIdentity}/${index}`" :file="file" :task-name="result.response.taskName" result-label="原重定时" @reference="updateReference(index, $event)" />
    </section>
  </section>
</template>

<style scoped>
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 380px; overflow: auto; }
</style>
