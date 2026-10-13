<script setup lang="ts">
/**
 * 文件→原计算→快照结果的实际工作流。父级传入 T21 唯一卫星/载荷草稿，slot 在文件和扫描之间编辑它。
 * busy 只表示真实计算请求进行中，父级据此冻结编辑；无伪进度、无未实现取消。
 */
import { computed, reactive, ref, shallowRef } from 'vue'
import type { BallisticSatelliteDraft, BallisticWindowRequest } from '../api/ballisticWindowTypes'
import { uploadBallisticFile, previewBallisticFile, downloadBallisticFile } from '../api/ballisticFiles'
import type { BallisticFilePreview, BallisticFileReference } from '../api/ballisticFiles'
import { searchBallisticWindows } from '../api/ballisticWindow'
import type { BallisticWindowHttpResult } from '../api/ballisticWindow'
import { saveBallisticBlob } from '../api/ballisticHttp'
import { isRemoteBallisticSource, validateWindowRequest } from '../utils/ballisticWindow'
import BallisticWindowResults from './BallisticWindowResults.vue'

const props = defineProps<{ satellites: BallisticSatelliteDraft[] }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
/** 文件引用只在成功HTTP后写入；远程草稿只有显式导入才生效，两者不共享假路径。 */
interface TargetDraft {
  targetId: string
  targetName: string
  baseLaunchUTC: string
  mode: 'file' | 'remote'
  remoteDraft: string
  remoteReference: string
  file?: BallisticFileReference
  preview?: BallisticFilePreview
  error: string
  busy: boolean
  downloadBusy: boolean
}
const targets = reactive<TargetDraft[]>([])
let targetSequence = 0
const loading = ref(false)
const errors = ref<string[]>([])
const result = shallowRef<BallisticWindowHttpResult>()
const submitted = shallowRef<BallisticWindowRequest>()
const options = reactive({ startOffsetSec: -500, stepSizeSec: 10, numCalculations: 101, targetSampleStepSec: 1, trajectoryPointOutput: 1 as 0 | 1, orbitModel: 'J2_NUMERICAL' as BallisticWindowRequest['orbitModel'] })
const changedSinceSubmission = computed(() => submitted.value && JSON.stringify(currentRequest()) !== JSON.stringify(submitted.value))

/** 单调递增身份：删除/上传失败不复用旧目标，避免并发响应绑定其他目标。 */
function addTarget(): void {
  targetSequence += 1
  targets.push({ targetId: `TARGET-${targetSequence}`, targetName: `目标${targetSequence}`, baseLaunchUTC: '', mode: 'file', remoteDraft: '', remoteReference: '', error: '', busy: false, downloadBusy: false })
}
addTarget()

/** 用户换来源时即作废旧引用；失败绝不回退到上一次成功文件。 */
function clearSource(target: TargetDraft): void {
  target.file = undefined
  target.preview = undefined
  target.remoteReference = ''
  target.error = ''
}

/** 原生文件选择事件仅用于取File；上传/预览错误独立显示，serverPath仅来自真实引用。 */
async function chooseFile(target: TargetDraft, event: Event): Promise<void> {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  const file = input.files?.[0]
  if (!file) return
  clearSource(target)
  target.busy = true
  try {
    const reference = await uploadBallisticFile(file)
    if (!targets.includes(target)) return
    target.file = reference
    await loadPreview(target, 1)
  } catch (error) {
    target.error = error instanceof Error ? error.message : '文件上传失败，请检查服务连接'
  } finally {
    target.busy = false
    input.value = ''
  }
}

/** 保持服务原表头与文本，不猜未知单位；分页请求不修改当前计算文件引用。 */
async function loadPreview(target: TargetDraft, page: number): Promise<void> {
  if (!target.file) return
  const reference = target.file
  target.error = ''
  target.busy = true
  try {
    const preview = await previewBallisticFile(reference.fileId, page)
    if (target.file === reference && targets.includes(target)) target.preview = preview
  } catch (error) {
    target.error = error instanceof Error ? error.message : '文件预览失败'
  } finally { target.busy = false }
}

/** 只登记原服务支持的远程语法；服务实际下载/解析发生在计算请求，失败在该次计算显示。 */
function importRemote(target: TargetDraft): void {
  clearSource(target)
  if (!isRemoteBallisticSource(target.remoteDraft)) { target.error = '请输入 HTTP/HTTPS 直链或 minio://host[:port]/bucket/object[?secure=true]；本机路径请使用上传'; return }
  target.remoteReference = target.remoteDraft.trim()
}

/** 原文件HTTP下载成功才创建Blob，错误保持在所属目标，不保存错误JSON。 */
async function downloadOriginal(target: TargetDraft): Promise<void> {
  if (!target.file) return
  target.downloadBusy = true
  target.error = ''
  try {
    const download = await downloadBallisticFile(target.file.fileId)
    saveBallisticBlob(download.blob, download.filename)
  } catch (error) { target.error = error instanceof Error ? error.message : '原文件下载失败' }
  finally { target.downloadBusy = false }
}

/** 原DTO白名单：源引用、独立目标UTC、同一卫星载荷草稿；不发送浏览器路径/文件对象。 */
function currentRequest(): BallisticWindowRequest {
  return { ...options, satellites: props.satellites, ballistics: targets.map((target) => ({ targetId: target.targetId, targetName: target.targetName, csvPath: target.mode === 'file' ? target.file?.serverPath || '' : target.remoteReference, ...(target.baseLaunchUTC.trim() ? { baseLaunchUTC: target.baseLaunchUTC.trim() } : {}) })) }
}

/** 开始任何新尝试清旧成功；HTTP只绑定校验后的不可变提交快照，不随输入变化重标结果。 */
async function calculate(): Promise<void> {
  if (loading.value || targets.some((target) => target.busy)) return
  result.value = undefined
  submitted.value = undefined
  const request = currentRequest()
  // Windows服务路径可能与浏览器本机路径同形；只信任本页实际HTTP上传返回的精确引用，不能按目录词猜测。
  errors.value = validateWindowRequest(request, new Set(targets.flatMap((target) => target.file ? [target.file.serverPath] : [])))
  if (errors.value.length) return
  const snapshot = JSON.parse(JSON.stringify(request)) as BallisticWindowRequest
  loading.value = true
  emit('busy', true)
  try {
    const response = await searchBallisticWindows(snapshot)
    submitted.value = snapshot
    result.value = response
  } catch (error) { errors.value = [error instanceof Error ? error.message : '窗口计算失败，请检查服务连接'] }
  finally { loading.value = false; emit('busy', false) }
}

/** 原HTTP字节保留数值格式、字段顺序/空白/时间；不从展示对象重新序列化。 */
function downloadJson(): void {
  if (result.value) saveBallisticBlob(new Blob([result.value.rawBytes], { type: 'application/json;charset=utf-8' }), '弹道窗口原始响应.json')
}
</script>

<template>
  <section class="ballistic-window-controls" aria-label="弹道文件与窗口计算">
    <fieldset :disabled="loading" class="ballistic-native-form">
      <legend>1. 弹道目标与源文件</legend>
      <p>本地文件通过 HTTP 上传；服务器引用与本机路径分离。网络支持 HTTP/HTTPS 直链、minio://host[:port]/bucket/object[?secure=true]（凭据由服务环境提供）。</p>
      <button type="button" @click="addTarget">新增弹道目标</button>
      <article v-for="target in targets" :key="target.targetId" :data-target-id="target.targetId" class="ballistic-target">
        <fieldset :disabled="target.busy">
          <legend>{{ target.targetName }} · {{ target.targetId }}</legend>
          <div class="ballistic-field-grid">
            <label>目标名称<input v-model="target.targetName" type="text" /></label>
            <label>基准发射 UTC（留空沿文件历元）<input v-model="target.baseLaunchUTC" placeholder="2026/07/01 07:57:10.000" type="text" /></label>
            <label>文件来源<select v-model="target.mode" @change="clearSource(target)"><option value="file">本地上传</option><option value="remote">网络地址导入</option></select></label>
          </div>
          <label v-if="target.mode === 'file'">选择弹道 CSV / Excel（.csv/.xlsx/.xls/.xlsm）<input type="file" accept=".csv,.xlsx,.xls,.xlsm" @change="chooseFile(target, $event)" /></label>
          <div v-else class="ballistic-field-grid"><label>网络地址<input v-model="target.remoteDraft" type="text" placeholder="https://host/path/trajectory.csv" @input="clearSource(target)" /></label><button type="button" @click="importRemote(target)">导入网络地址</button></div>
          <p v-if="target.file">已上传：{{ target.file.originalName }} · {{ target.file.sizeBytes }} 字节 · 引用 {{ target.file.fileId }}</p>
          <p v-if="target.remoteReference">已导入：{{ target.remoteReference }}（待计算时由服务下载并解析）</p>
          <div class="ballistic-actions"><button v-if="target.file" type="button" :disabled="target.downloadBusy" @click="downloadOriginal(target)">{{ target.downloadBusy ? '正在下载…' : 'HTTP 下载原文件' }}</button><button type="button" @click="targets.splice(targets.indexOf(target), 1)">删除目标</button></div>
        </fieldset>
        <p v-if="target.busy" role="status">正在上传或读取原文件预览…</p>
        <p v-if="target.error" class="ballistic-error" role="alert">{{ target.targetName }}：{{ target.error }}</p>
        <details v-if="target.preview" open><summary>原数据预览 · 共 {{ target.preview.totalRows }} 行 · 第 {{ target.preview.page }} 页</summary>
          <pre>{{ JSON.stringify(target.preview.metadata, null, 2) }}</pre>
          <div class="ballistic-table-scroll"><table><thead><tr><th v-for="(header, index) in target.preview.header" :key="index">{{ header }}</th></tr></thead><tbody><tr v-for="(row, index) in target.preview.rows" :key="index"><td v-for="(cell, column) in row" :key="column">{{ cell }}</td></tr></tbody></table></div>
          <div class="ballistic-actions"><button type="button" :disabled="target.busy || target.preview.page <= 1" @click="loadPreview(target, target.preview.page - 1)">预览上一页</button><button type="button" :disabled="target.busy || target.preview.page * target.preview.limit >= target.preview.totalRows" @click="loadPreview(target, target.preview.page + 1)">预览下一页</button></div>
        </details>
      </article>
    </fieldset>
    <slot />
    <fieldset :disabled="loading" class="ballistic-native-form">
      <legend>3. 原窗口扫描条件</legend>
      <p>UTC 输入仅为 UTC 字面时间，浏览器时区不加减8小时。卫星历元留空使用原服务缺省 2026/07/01 10:00:00；目标基准发射时间留空沿各文件参考历元。</p>
      <div class="ballistic-field-grid">
        <label>偏移起点（秒，可负）<input v-model.number="options.startOffsetSec" data-option="startOffsetSec" type="number" step="1" /></label>
        <label>偏移步长（秒，正整数）<input v-model.number="options.stepSizeSec" data-option="stepSizeSec" type="number" step="1" /></label>
        <label>计算次数（正整数）<input v-model.number="options.numCalculations" data-option="numCalculations" type="number" step="1" /></label>
        <label>目标采样步长（秒，≤0 自动）<input v-model.number="options.targetSampleStepSec" data-option="targetSampleStepSec" type="number" step="any" /></label>
        <label>轨道模型<select v-model="options.orbitModel"><option>TWO_BODY</option><option>J2_SECULAR</option><option>J2_NUMERICAL</option></select></label>
        <label>轨迹点输出<select v-model="options.trajectoryPointOutput" data-option="trajectoryPointOutput"><option :value="1">1 · 输出真实窗口内点</option><option :value="0">0 · 仅窗口摘要</option></select></label>
      </div>
      <p>起点、步长、次数及末次偏移必须在浏览器安全整数范围内；不降低原采样或重新计算服务 best。</p>
      <button type="button" data-test="compute-window" :disabled="targets.some((target) => target.busy)" @click="calculate">{{ loading ? '正在计算真实窗口…' : '执行弹道窗口计算' }}</button>
    </fieldset>
    <ul v-if="errors.length" class="ballistic-error" role="alert"><li v-for="error in errors" :key="error">{{ error }}</li></ul>
    <section v-if="result && submitted" data-test="window-success">
      <h2>本次实际提交的计算结果</h2>
      <p v-if="changedSinceSubmission" role="status">输入已修改。以下结果仍属于上一次实际提交快照；重新计算后才更新。</p>
      <button type="button" @click="downloadJson">下载原始响应 JSON</button>
      <details><summary>本次提交快照（完整轨道、目标、标准载荷）</summary><pre>{{ JSON.stringify(submitted, null, 2) }}</pre></details>
      <BallisticWindowResults :result="result.response" :point-output="submitted.trajectoryPointOutput" />
    </section>
  </section>
</template>
