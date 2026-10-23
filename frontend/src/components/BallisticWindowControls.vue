<script>
export default { name: 'BallisticWindowControls' }
</script>

<script setup>
/**
 * 文件→原计算→快照结果的实际工作流。父级传入 T21 唯一卫星/载荷草稿，slot 在文件和扫描之间编辑它。
 * busy 只表示真实计算请求进行中，父级据此冻结编辑；无伪进度、无未实现取消。
 */
import { computed, nextTick, reactive, ref, shallowRef, watch } from 'vue'
import { uploadBallisticFile, previewBallisticFile, downloadBallisticFile } from '../api/ballisticFiles'
import { searchBallisticWindows } from '../api/ballisticWindow'
import { saveBallisticBlob } from '../api/ballisticHttp'
import { isRemoteBallisticSource, validateWindowRequest } from '../utils/ballisticWindow'
import BallisticWindowResults from './BallisticWindowResults.vue'
const props = defineProps({ satellites: { type: Array, required: true }, generatedResults: { type: Array } })
const emit = defineEmits(['busy', 'editPayloads'])
const searchSection = ref()
const errorSummary = ref()
/** 空态动作把焦点送回真实输入，不复制草稿或自动放宽约束。 */
function editConfiguration(section) {
  if (section === 'payloads') emit('editPayloads')
  else {
    searchSection.value?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    searchSection.value?.querySelector('input')?.focus()
  }
}
const targets = ref([])
let targetSequence = 0
const loading = ref(false)
const errors = ref([])
const result = shallowRef()
const submitted = shallowRef()
/** 恢复Swing扫描361；后端HTTP原缺省101不变。默认UTC仅在目标自身未填写时应用。 */
const defaultBaseLaunchUTC = ref('')
const options = reactive({
  startOffsetSec: -500,
  stepSizeSec: 10,
  numCalculations: 361,
  targetSampleStepSec: 1,
  trajectoryPointOutput: 1,
  orbitModel: 'J2_NUMERICAL',
})
const changedSinceSubmission = computed(
  () => submitted.value && JSON.stringify(currentRequest()) !== JSON.stringify(submitted.value),
)
/** 单调递增身份：删除/上传失败不复用旧目标，避免并发响应绑定其他目标。 */
function addTarget() {
  targetSequence += 1
  targets.value.push({
    identity: targetSequence,
    targetId: `TGT-${targetSequence}`,
    targetName: `目标${targetSequence}`,
    baseLaunchUTC: '',
    mode: 'file',
    remoteDraft: '',
    remoteReference: '',
    error: '',
    busy: false,
    downloadBusy: false,
    file: undefined,
    preview: undefined,
    epochUtc: undefined,
    generatedId: undefined,
  })
}
addTarget()
/** 用户换来源时即作废旧引用；失败绝不回退到上一次成功文件。 */
function clearSource(target) {
  target.file = undefined
  target.preview = undefined
  target.epochUtc = undefined
  target.remoteReference = ''
  target.generatedId = undefined
  target.error = ''
}
/** 服务生成来源只允许显式选择父级真实201引用；清旧文件/预览，时间沿CSV历元避免自动重定时。 */
async function selectGenerated(target, id) {
  clearSource(target)
  const selected = props.generatedResults?.find((item) => item.reference.fileId === id)
  if (!selected) {
    target.error = '生成结果选择已失效，请重新登记或选择'
    return
  }
  target.generatedId = id
  target.file = selected.reference
  target.targetId = selected.targetId
  target.targetName = selected.targetName
  target.baseLaunchUTC = ''
  await loadPreview(target, 1)
}
/** 新模拟/撤销登记导致候选消失：立即撤销旧成功引用和预览，不回退此前上传/生成路径。 */
watch(
  () => props.generatedResults,
  () => {
    for (const target of targets.value) {
      if (target.mode !== 'generated' || !target.generatedId) continue
      if (
        !props.generatedResults?.some(
          (item) =>
            item.reference.fileId === target.generatedId && item.reference.serverPath === target.file?.serverPath,
        )
      ) {
        clearSource(target)
        target.error = '已选生成结果已失效，请重新登记并显式选择'
      }
    }
  },
)
/** Element UI 上传回调仅取浏览器 File；上传/预览错误独立显示，serverPath 仅来自真实服务端引用。 */
async function chooseFile(target, uploadFile) {
  const file = uploadFile.raw
  if (!file) return
  clearSource(target)
  target.busy = true
  try {
    const reference = await uploadBallisticFile(file)
    if (!targets.value.includes(target)) return
    target.file = reference
    await loadPreview(target, 1)
  } catch (error) {
    target.error = error instanceof Error ? error.message : '文件上传失败，请检查服务连接'
  } finally {
    target.busy = false
  }
}
/** 为每个目标提供独立上传回调，避免不同卡片上传时串用目标身份。 */
function targetFileChange(target) {
  return (file) => {
    void chooseFile(target, file)
  }
}
/** 默认演示CSV亦经真实上传取得服务引用；不把源码路径/本机路径交给窗口接口。 */
async function uploadDefault(target) {
  clearSource(target)
  target.busy = true
  try {
    const response = await fetch('/samples/trajectory-0.1s-standard.csv')
    if (!response.ok) throw new Error('默认演示弹道读取失败')
    target.file = await uploadBallisticFile(
      new File([await response.arrayBuffer()], 'trajectory-0.1s-standard.csv', { type: 'text/csv' }),
    )
    await loadPreview(target, 1)
  } catch (error) {
    target.error = error instanceof Error ? error.message : '默认弹道上传失败'
  } finally {
    target.busy = false
  }
}
/** 保持服务原表头与文本，不猜未知单位；分页请求不修改当前计算文件引用。 */
async function loadPreview(target, page) {
  if (!target.file) return
  const reference = target.file
  target.error = ''
  target.busy = true
  try {
    const preview = await previewBallisticFile(reference.fileId, page)
    if (target.file === reference && targets.value.includes(target)) {
      target.preview = preview
      // 仅从首页原始分列UTC展示文件首行时间；未知表头不猜测历元，翻页不改首行摘要。
      if (
        page === 1 &&
        ['YYYY', 'MM', 'dd', 'HH', 'mm', 'ss.SSS'].every((key, index) => preview.header[index] === key)
      ) {
        const row = preview.rows[0]
        if (row) target.epochUtc = `${row[0]}/${row[1]}/${row[2]} ${row[3]}:${row[4]}:${row[5]}`
      }
    }
  } catch (error) {
    target.error = error instanceof Error ? error.message : '文件预览失败'
  } finally {
    target.busy = false
  }
}
/** 只登记原服务支持的远程语法；服务实际下载/解析发生在计算请求，失败在该次计算显示。 */
function importRemote(target) {
  clearSource(target)
  if (!isRemoteBallisticSource(target.remoteDraft)) {
    target.error = '请输入 HTTP/HTTPS 直链或 minio://host[:port]/bucket/object[?secure=true]；本机路径请使用上传'
    return
  }
  target.remoteReference = target.remoteDraft.trim()
}
/** 原文件HTTP下载成功才创建Blob，错误保持在所属目标，不保存错误JSON。 */
async function downloadOriginal(target) {
  if (!target.file) return
  target.downloadBusy = true
  target.error = ''
  try {
    const download = await downloadBallisticFile(target.file.fileId)
    saveBallisticBlob(download.blob, download.filename)
  } catch (error) {
    target.error = error instanceof Error ? error.message : '原文件下载失败'
  } finally {
    target.downloadBusy = false
  }
}
/** 原DTO白名单：源引用、独立目标UTC、同一卫星载荷草稿；不发送浏览器路径/文件对象。 */
function currentRequest() {
  return {
    ...options,
    satellites: props.satellites,
    ballistics: targets.value.map((target) => {
      const generated =
        target.mode === 'generated'
          ? props.generatedResults?.find((item) => item.reference.fileId === target.generatedId)
          : undefined
      const utc = target.baseLaunchUTC.trim() || defaultBaseLaunchUTC.value.trim()
      return {
        targetId: target.targetId,
        targetName: target.targetName,
        csvPath:
          target.mode === 'remote'
            ? target.remoteReference
            : target.mode === 'generated'
            ? generated?.reference.serverPath || ''
            : target.file?.serverPath || '',
        ...(utc ? { baseLaunchUTC: utc } : {}),
      }
    }),
  }
}
/** 开始任何新尝试清旧成功；HTTP只绑定校验后的不可变提交快照，不随输入变化重标结果。 */
async function calculate() {
  if (loading.value || targets.value.some((target) => target.busy)) return
  result.value = undefined
  submitted.value = undefined
  const request = currentRequest()
  // Windows服务路径可能与本机路径同形；本页上传和父级已登记结果都只信任真实HTTP返回的精确引用。
  errors.value = validateWindowRequest(
    request,
    new Set(targets.value.flatMap((target) => (target.file ? [target.file.serverPath] : []))),
  )
  if (errors.value.length) {
    await nextTick()
    errorSummary.value?.focus()
    return
  }
  const snapshot = JSON.parse(JSON.stringify(request))
  loading.value = true
  emit('busy', true)
  try {
    const response = await searchBallisticWindows(snapshot)
    submitted.value = snapshot
    result.value = response
  } catch (error) {
    errors.value = [error instanceof Error ? error.message : '窗口计算失败，请检查服务连接']
  } finally {
    loading.value = false
    emit('busy', false)
  }
}
/** 原HTTP字节保留数值格式、字段顺序/空白/时间；不从展示对象重新序列化。 */
function downloadJson() {
  if (result.value)
    saveBallisticBlob(
      new Blob([result.value.rawBytes], { type: 'application/json;charset=utf-8' }),
      '弹道窗口原始响应.json',
    )
}
</script>

<template>
  <section class="ballistic-window-controls ballistic-workbench-grid" aria-label="弹道文件与窗口计算">
    <div class="ballistic-input-column">
      <div class="ballistic-input-scroll">
    <slot name="satellites" />
    <fieldset :disabled="loading" class="ballistic-native-form ballistic-panel">
      <legend>弹道目标</legend>
      <div class="ballistic-section-heading"><span class="ballistic-muted">CSV / Excel</span><el-button @click="addTarget">新增目标</el-button></div>
      <article v-for="target in targets" :key="target.identity" :data-target-id="target.targetId" class="ballistic-target">
        <fieldset :disabled="target.busy">
          <el-form label-position="top" class="ballistic-field-grid">
            <el-form-item label="目标名称"><el-input v-model="target.targetName" /></el-form-item>
            <el-form-item label="文件来源"><el-select v-model="target.mode" data-test="window-source" @input="clearSource(target)"><el-option value="file" label="本地上传" /><el-option value="remote" label="网络地址" /><el-option value="generated" label="已生成弹道" /></el-select></el-form-item>
          </el-form>
          <div v-if="target.mode === 'file'" class="ballistic-file-picker"><el-form label-position="top"><el-form-item label="选择文件"><el-upload action="" :auto-upload="false" :show-file-list="false" accept=".csv,.xlsx,.xls,.xlsm" :disabled="target.busy" :on-change="targetFileChange(target)"><el-button>选择弹道文件</el-button></el-upload></el-form-item></el-form><el-button data-test="upload-default-window" @click="uploadDefault(target)">使用示例弹道</el-button></div>
          <el-form v-else-if="target.mode === 'remote'" label-position="top" class="ballistic-field-grid"><el-form-item label="网络地址"><el-input v-model="target.remoteDraft" placeholder="https://host/path/trajectory.csv" @input="clearSource(target)" /></el-form-item><el-button class="remote-import" @click="importRemote(target)">导入网络地址</el-button></el-form>
          <el-form v-else label-position="top"><el-form-item label="已生成弹道"><el-select :value="target.generatedId || ''" data-test="generated-result-select" @input="selectGenerated(target, $event)"><el-option value="" label="请选择拟合、模拟或重定时结果" /><el-option v-for="item in generatedResults || []" :key="item.reference.fileId" :value="item.reference.fileId" :label="`${item.targetName} · ${item.reference.originalName}`" /></el-select></el-form-item></el-form>
          <div v-if="target.file" class="ballistic-file-summary"><strong>{{ target.file.originalName }}</strong><span>{{ target.preview ? `${target.preview.totalRows} 行` : '正在读取' }} · {{ target.file.sizeBytes }} 字节</span><span v-if="target.epochUtc">历元 {{ target.epochUtc }} UTC</span></div>
          <p v-if="target.remoteReference" class="ballistic-muted">已导入：{{ target.remoteReference }}</p>
          <div class="ballistic-actions"><el-button v-if="target.file" :disabled="target.downloadBusy" @click="downloadOriginal(target)">{{ target.downloadBusy ? '正在下载…' : '下载原始文件' }}</el-button><el-button @click="targets.splice(targets.indexOf(target), 1)">删除目标</el-button></div>
          <details><summary>目标时间与来源详情</summary><el-form label-position="top" class="ballistic-field-grid"><el-form-item label="目标 ID"><el-input v-model="target.targetId" /></el-form-item><el-form-item label="基准发射 UTC"><el-input v-model="target.baseLaunchUTC" data-test="target-base-utc" placeholder="留空沿文件历元" /></el-form-item></el-form><p class="ballistic-muted">目标时间优先于默认时间；均留空时采用文件历元。输入按 UTC 字面值处理。</p><p v-if="target.mode === 'remote'">支持 HTTP/HTTPS 直链与 minio://host[:port]/bucket/object[?secure=true]；私有文件可用预签名 HTTPS 地址。</p><pre v-if="target.file">{{ JSON.stringify(target.file, null, 2) }}</pre><pre v-if="target.preview">{{ JSON.stringify(target.preview.metadata, null, 2) }}</pre></details>
        </fieldset>
        <p v-if="target.busy" role="status">正在上传或读取文件…</p>
        <p v-if="target.error" class="ballistic-error" role="alert">{{ target.targetName }}：{{ target.error }}</p>
        <details v-if="target.preview"><summary>数据预览 · {{ target.preview.totalRows }} 行 · 第 {{ target.preview.page }} 页</summary>
          <div class="ballistic-table-scroll"><el-table :data="target.preview.rows.map((row, rowIndex) => ({ rowIndex, cells: row }))" border size="small" table-layout="auto"><el-table-column v-for="(header, index) in target.preview.header" :key="index" :label="header" :min-width="130"><template #default="{ row }">{{ row.cells[index] }}</template></el-table-column></el-table></div>
          <div class="ballistic-actions"><el-button :disabled="target.busy || target.preview.page <= 1" @click="loadPreview(target, target.preview.page - 1)">预览上一页</el-button><el-button :disabled="target.busy || target.preview.page * target.preview.limit >= target.preview.totalRows" @click="loadPreview(target, target.preview.page + 1)">预览下一页</el-button></div>
        </details>
      </article>
      <details><summary>多目标默认时间</summary><el-form label-position="top"><el-form-item label="默认基准发射 UTC"><el-input v-model="defaultBaseLaunchUTC" data-test="default-base-utc" placeholder="目标留空时采用；再留空沿文件历元" /></el-form-item></el-form></details>
    </fieldset>
    <fieldset ref="searchSection" :disabled="loading" class="ballistic-native-form ballistic-panel">
      <legend>发射时间搜索</legend>
      <el-form label-position="top" class="ballistic-field-grid ballistic-search-fields">
        <el-form-item label="起始偏移 (s)"><el-input v-model.number="options.startOffsetSec" data-option="startOffsetSec" type="number" step="1" /></el-form-item>
        <el-form-item label="步长 (s)"><el-input v-model.number="options.stepSizeSec" data-option="stepSizeSec" type="number" step="1" /></el-form-item>
        <el-form-item label="搜索次数"><el-input v-model.number="options.numCalculations" data-option="numCalculations" type="number" step="1" /></el-form-item>
      </el-form>
      <p class="ballistic-search-summary">{{ options.startOffsetSec }} → {{ options.startOffsetSec + options.stepSizeSec * (options.numCalculations - 1) }} 秒 · 每 {{ options.stepSizeSec }} 秒一次 · 共 {{ options.numCalculations }} 次</p>
      <details><summary>模型与采样</summary><el-form label-position="top" class="ballistic-field-grid">
        <el-form-item label="目标采样步长 (s，≤0 自动)"><el-input v-model.number="options.targetSampleStepSec" data-option="targetSampleStepSec" type="number" step="any" /></el-form-item>
        <el-form-item label="轨道模型"><el-select v-model="options.orbitModel"><el-option label="TWO_BODY" value="TWO_BODY" /><el-option label="J2_SECULAR" value="J2_SECULAR" /><el-option label="J2_NUMERICAL" value="J2_NUMERICAL" /></el-select></el-form-item>
        <el-form-item label="轨迹点输出"><el-select v-model="options.trajectoryPointOutput" data-option="trajectoryPointOutput"><el-option :value="1" label="输出窗口内轨迹点" /><el-option :value="0" label="仅窗口摘要" /></el-select></el-form-item>
      </el-form><p class="ballistic-muted">偏移相对于各目标基准发射时间；起点、步长、次数与末次偏移须为安全整数。卫星历元留空采用 2026/07/01 10:00:00 UTC。</p></details>
    </fieldset>
    <slot />
      </div>
      <div class="ballistic-compute-bar"><span>{{ loading ? '计算中，请稍候' : `${satellites.length} 颗卫星 · ${satellites.reduce((sum, satellite) => sum + satellite.payloads.length, 0)} 个载荷 · ${targets.length} 个目标` }}</span><div class="ballistic-compute-actions"><el-button data-test="open-payload-settings" :disabled="loading" @click="editConfiguration('payloads')">载荷设置</el-button><el-button type="primary" class="ballistic-primary-action" data-test="compute-window" :disabled="loading || targets.some((target) => target.busy)" @click="calculate">{{ loading ? '正在计算…' : '计算可见窗口' }}</el-button></div></div>
    </div>
    <aside class="ballistic-results-column ballistic-panel" data-test="window-result-panel" aria-label="窗口计算结果" :aria-busy="loading">
      <div class="ballistic-section-heading"><h3>窗口结果</h3><span class="ballistic-badge">{{ loading ? '计算中' : result ? '已完成' : '待计算' }}</span></div>
      <div v-if="errors.length" ref="errorSummary" tabindex="-1" class="ballistic-error" role="alert"><strong>请检查输入</strong><ul><li v-for="error in errors" :key="error">{{ error }}</li></ul><div class="ballistic-actions"><el-button @click="editConfiguration('payloads')">检查载荷</el-button><el-button @click="editConfiguration('search')">检查搜索时间</el-button></div></div>
      <div v-if="loading" class="ballistic-empty-state" role="status"><span class="ballistic-state-mark">…</span><h3>正在搜索可见窗口</h3><p>{{ options.numCalculations }} 个发射时刻 · {{ satellites.length }} 颗卫星</p></div>
      <section v-else-if="result && submitted" data-test="window-success">
        <p v-if="changedSinceSubmission" role="status" class="ballistic-change-notice">参数已修改，当前仍显示上次计算结果。</p>
        <BallisticWindowResults :result="result.response" :point-output="submitted.trajectoryPointOutput" @edit="editConfiguration" />
        <details class="ballistic-result-details"><summary>计算记录与导出</summary><el-button @click="downloadJson">下载原始响应 JSON</el-button><pre>{{ JSON.stringify(submitted, null, 2) }}</pre></details>
      </section>
      <div v-else-if="!errors.length" class="ballistic-empty-state"><span class="ballistic-state-mark">↗</span><h3>等待计算</h3><p>选择弹道文件后，点击“计算可见窗口”。</p></div>
    </aside>
  </section>
</template>
