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
import { ApiRequestError } from '../api/client'
import { parseFitCsv } from '../utils/ballisticFit'
import BallisticFitTrajectory from './BallisticFitTrajectory.vue'
const props = withDefaults(defineProps<{ file: BallisticFitFile; taskName: string; resultLabel?: string }>(), { resultLabel: '拟合' })
/** 共享文件能力只在真实登记201后告知父级引用，重新登记开始先撤销旧引用以防失效路径被继续选择。 */
const emit = defineEmits<{ reference: [value: BallisticFileReference | undefined] }>()
const reference = shallowRef<BallisticFileReference>()
const preview = shallowRef<BallisticFilePreview>()
const points = shallowRef<FitCsvPoint[]>([])
const registering = ref(false), previewing = ref(false), trajectoryLoading = ref(false), downloading = ref(false)
const registerError = ref(''), previewError = ref(''), trajectoryError = ref(''), downloadError = ref('')
const requestedPage = ref(1)
function message(failure: unknown): string { return failure instanceof Error ? failure.message : '文件辅助请求失败，请检查服务连接' }
/** 404表示服务引用已失效/注销；撤销父级候选，但保留本页原计算成功及独立错误供恢复登记。
 * 普通400格式或500读取错误不证明引用注销，不能因此删另一来源的可用结果。
 */
function revokeMissingReference(failure: unknown): void {
  if (failure instanceof ApiRequestError && failure.status === 404) emit('reference', undefined)
}

/** 登记只在真实201后开放操作；原服务在文件缺失时注销ID，文件恢复后允许重新登记获得实际新引用。 */
async function register(): Promise<void> {
  if (registering.value) return
  registering.value = true
  registerError.value = ''
  reference.value = undefined
  preview.value = undefined
  points.value = []
  emit('reference', undefined)
  try {
    reference.value = await registerBallisticResult(props.file.csvPath, props.taskName, props.file.targetId)
    emit('reference', reference.value)
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
  catch (failure) { previewError.value = message(failure); revokeMissingReference(failure) }
  finally { previewing.value = false }
}
/** 完整CSV通过真实HTTP Blob获取，拟合/模拟严格21列，重定时按必要命名列解析；页预览不能冒充完整轨迹。 */
async function loadTrajectory(): Promise<void> {
  if (!reference.value || trajectoryLoading.value) return
  trajectoryLoading.value = true
  trajectoryError.value = ''
  points.value = []
  try {
    const download = await downloadBallisticFile(reference.value.fileId)
    const parsed = parseFitCsv(await download.blob.text(), props.file.model === 'RETIMED_TRAJECTORY' ? 'retime' : 'fit')
    if (parsed.length !== props.file.pointCount) throw new Error(`完整CSV行数${parsed.length}与原结果点数${props.file.pointCount}不一致，暂停轨迹展示`)
    points.value = parsed
  } catch (failure) { trajectoryError.value = message(failure); revokeMissingReference(failure) }
  finally { trajectoryLoading.value = false }
}
/** 每次下载取当前服务原字节，失败不保存错误JSON；不使用图形数据重导CSV。 */
async function download(): Promise<void> {
  if (!reference.value || downloading.value) return
  downloading.value = true
  downloadError.value = ''
  try { const data = await downloadBallisticFile(reference.value.fileId); saveBallisticBlob(data.blob, data.filename) }
  catch (failure) { downloadError.value = message(failure); revokeMissingReference(failure) }
  finally { downloading.value = false }
}
onMounted(register)
</script>

<template>
  <article class="ballistic-result-target" :data-fit-target="file.targetId">
    <h3>目标 {{ file.targetId }} · {{ file.pointCount }} 点</h3>
    <details><summary>模型与文件详情</summary><p>模型 {{ file.model }}</p><p>服务文件 {{ file.csvPath }}</p><p v-if="reference">引用 {{ reference.fileId }} · {{ reference.sizeBytes }} 字节</p></details>
    <!-- 告警默认收起以优先呈现下载和轨迹；原生折叠入口保留数量提示、键盘操作及服务原文顺序。 -->
    <details v-if="file.warnings.length"><summary>计算告警（{{ file.warnings.length }}条）</summary><ol aria-label="计算告警"><li v-for="(warning, index) in file.warnings" :key="index">{{ warning }}</li></ol></details>
    <p v-if="registering" role="status">正在准备结果文件…</p>
    <p v-if="registerError" class="ballistic-error" role="alert">{{ resultLabel }}已成功，文件登记失败：{{ registerError }}</p>
    <el-button v-if="!reference" data-test="fit-register" :disabled="registering" @click="register">重试准备结果文件</el-button>
    <template v-if="reference">
      <p>{{ reference.originalName }} · {{ reference.sizeBytes }} 字节</p>
      <el-button data-test="fit-download" :disabled="downloading" @click="download">{{ downloading ? '正在下载CSV…' : '下载CSV' }}</el-button>
      <p v-if="previewError || trajectoryError || downloadError">文件读取失败可重试；文件恢复后可重新获取结果文件。</p>
      <el-button v-if="previewError || trajectoryError || downloadError" data-test="fit-reregister" :disabled="registering || previewing || trajectoryLoading || downloading" @click="register">重新获取结果文件</el-button>
      <p v-if="downloadError" class="ballistic-error" role="alert">下载失败：{{ downloadError }}（可重试下载）</p>
      <p v-if="previewing" role="status">正在读取第 {{ requestedPage }} 页预览…</p>
      <p v-if="previewError" class="ballistic-error" role="alert">预览失败：{{ previewError }}；已显示内容仍为此前成功页。</p>
      <el-button v-if="previewError" data-test="fit-preview-retry" :disabled="previewing" @click="loadPreview(requestedPage)">重试文件预览</el-button>
      <details v-if="preview"><summary>数据预览 · 共 {{ preview.totalRows }} 行 · 第 {{ preview.page }} 页</summary>
        <details><summary>文件元数据</summary><pre>{{ JSON.stringify(preview.metadata, null, 2) }}</pre></details>
        <div class="ballistic-table-scroll"><el-table :data="preview.rows.map((row, rowIndex) => ({ rowIndex, cells: row }))" border size="small" table-layout="auto"><el-table-column v-for="(cell, index) in preview.header" :key="index" :label="cell" :min-width="130"><template #default="{ row }">{{ row.cells[index] }}</template></el-table-column></el-table></div>
        <div class="ballistic-actions"><el-button :disabled="previewing || preview.page <= 1" @click="loadPreview(preview.page - 1)">预览上一页</el-button><el-button data-test="fit-preview-next" :disabled="previewing || preview.page * preview.limit >= preview.totalRows" @click="loadPreview(preview.page + 1)">预览下一页</el-button></div>
      </details>
      <p v-if="trajectoryLoading" role="status">正在读取轨迹…</p>
      <p v-if="trajectoryError" role="alert" class="ballistic-error">轨迹读取失败：{{ trajectoryError }}</p>
      <el-button v-if="trajectoryError" :disabled="trajectoryLoading" @click="loadTrajectory">重试读取轨迹</el-button>
      <BallisticFitTrajectory v-if="points.length" :points="points" :identity="file.targetId" :retimed="file.model === 'RETIMED_TRAJECTORY'" />
    </template>
  </article>
</template>
