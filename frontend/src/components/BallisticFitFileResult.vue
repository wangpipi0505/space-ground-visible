<script setup lang="ts">
/**
 * 一个实际FileResult对应一个独立状态：登记失败不伪报计算失败；预览/完整CSV/下载分开可重试。
 * props.csvPath仅来自原响应，HTTP辅助返回的fileId负责访问；不读取浏览器本机路径。
 */
import { onMounted, ref, shallowRef } from 'vue'
import type { BallisticFitFile, FitCsvPoint } from '../api/ballisticFitTypes'
import type { BallisticFilePreview, BallisticFileReference } from '../api/ballisticFiles'
import { registerBallisticResult, previewBallisticFile, downloadBallisticFile } from '../api/ballisticFiles'
import { saveBallisticBlob } from '../api/ballisticHttp'
import { parseFitCsv } from '../utils/ballisticFit'
import BallisticFitTrajectory from './BallisticFitTrajectory.vue'
const props = defineProps<{ file: BallisticFitFile; taskName: string }>()
const reference = shallowRef<BallisticFileReference>()
const preview = shallowRef<BallisticFilePreview>()
const points = shallowRef<FitCsvPoint[]>([])
const registering = ref(false), previewing = ref(false), trajectoryLoading = ref(false), downloading = ref(false)
const registerError = ref(''), previewError = ref(''), trajectoryError = ref(''), downloadError = ref('')
const requestedPage = ref(1)
function message(failure: unknown): string { return failure instanceof Error ? failure.message : '文件辅助请求失败，请检查服务连接' }

/** 登记只在真实201后开放操作；原服务在文件缺失时注销ID，文件恢复后允许重新登记获得实际新引用。 */
async function register(): Promise<void> {
  if (registering.value) return
  registering.value = true
  registerError.value = ''
  try {
    reference.value = await registerBallisticResult(props.file.csvPath, props.taskName, props.file.targetId)
    await Promise.all([loadPreview(1), loadTrajectory()])
  } catch (failure) { registerError.value = message(failure) }
  finally { registering.value = false }
}
/** 保留原文本与服务分页；失败显示原页仍在，重试请求失败页，不改轨迹或计算。 */
async function loadPreview(page: number): Promise<void> {
  if (!reference.value || previewing.value) return
  requestedPage.value = page
  previewing.value = true
  previewError.value = ''
  try { preview.value = await previewBallisticFile(reference.value.fileId, page) }
  catch (failure) { previewError.value = message(failure) }
  finally { previewing.value = false }
}
/** 完整CSV通过真实HTTP Blob获取，仅严格识别原21列；页预览不能冒充完整轨迹。 */
async function loadTrajectory(): Promise<void> {
  if (!reference.value || trajectoryLoading.value) return
  trajectoryLoading.value = true
  trajectoryError.value = ''
  points.value = []
  try {
    const download = await downloadBallisticFile(reference.value.fileId)
    const parsed = parseFitCsv(await download.blob.text())
    if (parsed.length !== props.file.pointCount) throw new Error(`完整CSV行数${parsed.length}与原结果点数${props.file.pointCount}不一致，暂停轨迹展示`)
    points.value = parsed
  } catch (failure) { trajectoryError.value = message(failure) }
  finally { trajectoryLoading.value = false }
}
/** 每次下载取当前服务原字节，失败不保存错误JSON；不使用图形数据重导CSV。 */
async function download(): Promise<void> {
  if (!reference.value || downloading.value) return
  downloading.value = true
  downloadError.value = ''
  try { const data = await downloadBallisticFile(reference.value.fileId); saveBallisticBlob(data.blob, data.filename) }
  catch (failure) { downloadError.value = message(failure) }
  finally { downloading.value = false }
}
onMounted(register)
</script>

<template>
  <article class="ballistic-result-target" :data-fit-target="file.targetId">
    <h3>目标 {{ file.targetId }} · {{ file.model }} · 原结果 {{ file.pointCount }} 点</h3>
    <p>原结果服务文件：{{ file.csvPath }}</p>
    <ol v-if="file.warnings.length" aria-label="原服务全部告警"><li v-for="(warning, index) in file.warnings" :key="index">{{ warning }}</li></ol>
    <p v-else>原服务未返回告警。</p>
    <p v-if="registering" role="status">正在登记原CSV并读取文件…</p>
    <p v-if="registerError" class="ballistic-error" role="alert">原拟合已成功，文件登记失败：{{ registerError }}</p>
    <button v-if="!reference" data-test="fit-register" type="button" :disabled="registering" @click="register">重试结果文件登记</button>
    <template v-if="reference">
      <p>已登记 {{ reference.originalName }} · 引用 {{ reference.fileId }} · {{ reference.sizeBytes }} 字节</p>
      <button type="button" data-test="fit-download" :disabled="downloading" @click="download">{{ downloading ? '正在下载原CSV…' : 'HTTP 下载原CSV' }}</button>
      <p v-if="previewError || trajectoryError || downloadError">文件辅助读取失败时可独立重试；若服务已注销旧引用、原CSV已恢复，可重新登记本次结果，计算不重跑。</p>
      <button v-if="previewError || trajectoryError || downloadError" type="button" data-test="fit-reregister" :disabled="registering || previewing || trajectoryLoading || downloading" @click="register">重新登记结果文件</button>
      <p v-if="downloadError" class="ballistic-error" role="alert">下载失败：{{ downloadError }}（可重试下载）</p>
      <p v-if="previewing" role="status">正在读取第 {{ requestedPage }} 页预览…</p>
      <p v-if="previewError" class="ballistic-error" role="alert">预览失败：{{ previewError }}；已显示内容仍为此前成功页。</p>
      <button v-if="previewError" type="button" data-test="fit-preview-retry" :disabled="previewing" @click="loadPreview(requestedPage)">重试文件预览</button>
      <details v-if="preview" open><summary>服务原文本预览 · 共 {{ preview.totalRows }} 行 · 第 {{ preview.page }} 页</summary>
        <pre>{{ JSON.stringify(preview.metadata, null, 2) }}</pre>
        <div class="ballistic-table-scroll"><table><thead><tr><th v-for="(cell, index) in preview.header" :key="index">{{ cell }}</th></tr></thead><tbody><tr v-for="(row, index) in preview.rows" :key="index"><td v-for="(cell, column) in row" :key="column">{{ cell }}</td></tr></tbody></table></div>
        <div class="ballistic-actions"><button type="button" :disabled="previewing || preview.page <= 1" @click="loadPreview(preview.page - 1)">预览上一页</button><button type="button" data-test="fit-preview-next" :disabled="previewing || preview.page * preview.limit >= preview.totalRows" @click="loadPreview(preview.page + 1)">预览下一页</button></div>
      </details>
      <p v-if="trajectoryLoading" role="status">正在通过 HTTP 读取完整CSV轨迹…</p>
      <p v-if="trajectoryError" role="alert" class="ballistic-error">完整轨迹读取失败：{{ trajectoryError }}</p>
      <button v-if="trajectoryError" type="button" :disabled="trajectoryLoading" @click="loadTrajectory">重试完整CSV轨迹</button>
      <BallisticFitTrajectory v-if="points.length" :points="points" :identity="file.targetId" />
    </template>
  </article>
</template>
