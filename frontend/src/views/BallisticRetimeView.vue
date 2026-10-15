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
  <section class="ballistic-retime-view ballistic-workspace" aria-label="弹道重定时">
    <header class="ballistic-workspace-header"><h2>弹道重定时</h2><span class="ballistic-source-badge">调整发射时间</span></header>
    <div class="ballistic-workbench-grid">
      <div class="ballistic-input-column">
        <div class="ballistic-input-scroll">
        <fieldset class="ballistic-native-form ballistic-panel" :disabled="computing || uploading">
          <legend>轨迹来源</legend>
          <label>来源<select v-model="sourceMode" data-test="retime-source-mode"><option value="upload">上传CSV</option><option value="generated">已生成结果</option></select></label>
          <label v-if="sourceMode === 'upload'">选择轨迹CSV<input type="file" accept=".csv,text/csv" @change="chooseFile" /></label>
          <label v-else>选择结果<select :value="generatedId" data-test="retime-result-select" @change="chooseGenerated"><option value="">请选择拟合、模拟或重定时结果</option><option v-for="item in generatedResults || []" :key="item.reference.fileId" :value="item.reference.fileId">{{ item.targetName }} · {{ item.reference.originalName }}</option></select></label>
          <p v-if="source" data-test="retime-source-reference">{{ source.originalName }} · {{ source.sizeBytes }} 字节<span v-if="preview"> · {{ preview.totalRows }} 行</span></p>
          <p v-if="uploading" role="status">正在上传CSV…</p>
          <p v-if="previewing" role="status">正在读取来源预览…</p>
          <p v-if="previewError" role="alert" class="ballistic-error">来源预览失败，可重试或继续计算：{{ previewError }}</p>
          <button v-if="previewError && source" type="button" :disabled="previewing" @click="loadSourcePreview(source, sourceIdentity)">重试来源预览</button>
          <details v-if="preview"><summary>数据预览 · 共 {{ preview.totalRows }} 行</summary><div class="ballistic-table-scroll"><table><thead><tr><th v-for="(cell, index) in preview.header" :key="index">{{ cell }}</th></tr></thead><tbody><tr v-for="(row, index) in preview.rows" :key="index"><td v-for="(cell, column) in row" :key="column">{{ cell }}</td></tr></tbody></table></div></details>
          <details v-if="source"><summary>文件详情</summary><p>引用 {{ source.fileId }}</p><p>服务路径 {{ source.serverPath }}</p></details>
        </fieldset>
        <fieldset class="ballistic-native-form ballistic-panel" :disabled="computing || uploading"><legend>任务与新发射时间</legend>
          <button type="button" data-test="load-default-retime" @click="taskName = 'retimeTrajectory'; targetId = 'RET-001'; launchTimeUtc = '2026-07-01 09:00:00.000'; nullTask = false; nullTarget = false">加载默认参数</button>
          <div class="ballistic-field-grid">
            <label>任务名称<input v-model="taskName" data-retime-field="taskName" :disabled="nullTask" /></label>
            <label>目标ID<input v-model="targetId" data-retime-field="targetId" :disabled="nullTarget" /></label>
            <label>新发射 UTC<input v-model="launchTimeUtc" data-retime-field="launchTimeUtc" placeholder="2026-07-02 07:57:00.000" /></label>
          </div>
          <p class="ballistic-field-hint">保持地固轨迹，按新UTC更新惯性系位置与速度。时间不转换本地时区。</p>
          <details><summary>高级设置 · 空值与输入快照</summary>
            <label><input v-model="nullTask" type="checkbox" data-retime-null="taskName" /> 任务名称使用 null</label>
            <label><input v-model="nullTarget" type="checkbox" data-retime-null="targetId" /> 目标ID使用 null</label>
            <pre>{{ JSON.stringify(draft(), null, 2) }}</pre>
          </details>
          <details><summary>CSV格式与时间说明</summary>
            <p>支持 YYYY/MM/dd/HH/mm/ss.SSS 及 ECEF_X/Y/Z、ECEF_Vx/Vy/Vz、ECI_X/Y/Z、ECI_VX/VY/VZ 命名列。20列 ECEF_X(m) 格式不支持重定时；HTTP、MinIO及Excel来源用于窗口计算。</p>
            <p>dt 为相对首行累计毫秒，timeIntervalMs 为相邻间隔毫秒。结果用于窗口计算时沿新CSV时间，不再次平移，请核对卫星历元日期。</p>
            <p>已生成结果仅保留当前会话登记的文件，刷新后需重新上传。outputDir由服务管理。</p>
          </details>
        </fieldset>
        </div>
        <div class="ballistic-compute-bar">
          <p v-if="error" class="ballistic-error" role="alert">{{ error }}</p>
          <button type="button" class="ballistic-primary-action" data-test="retime-compute" :disabled="computing || uploading" @click="calculate">{{ computing ? '正在重定时…' : '开始重定时' }}</button>
        </div>
      </div>
      <aside class="ballistic-results-column ballistic-panel" aria-label="重定时结果" :aria-busy="computing">
        <h3>重定时结果</h3>
        <div v-if="!result" class="ballistic-empty-state" role="status"><p>{{ computing ? '正在重定时，请等待结果。' : '完成左侧轨迹选择与新发射时间设置，点击“开始重定时”。' }}</p></div>
        <section v-if="result && submitted" data-test="retime-success" aria-label="本次重定时结果">
          <h3>{{ result.response.taskName }} · {{ result.response.targetCount }} 个目标</h3>
          <p v-if="changed" role="status">输入已修改，当前结果来自上次计算。重新重定时以更新结果。</p>
          <p class="ballistic-field-hint">结果可再次重定时，或用于可视窗口计算。</p>
          <div class="ballistic-actions"><button type="button" @click="exportInput">下载输入 JSON</button><button type="button" @click="exportResult">下载结果 JSON</button></div>
          <details><summary>计算输入快照</summary><pre data-test="retime-submitted">{{ JSON.stringify(submitted, null, 2) }}</pre></details>
          <BallisticFitFileResult v-for="(file, index) in result.response.files" :key="`${runIdentity}/${index}`" :file="file" :task-name="result.response.taskName" result-label="重定时" @reference="updateReference(index, $event)" />
        </section>
      </aside>
    </div>
  </section>
</template>

<style scoped>
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 380px; overflow: auto; }
</style>
