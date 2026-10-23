<script>
export default { name: 'BallisticFitView' }
</script>

<script setup>
/**
 * 原102完整JSON→原拟合→逐目标HTTP文件流。JSON是唯一输入，不另建解码后的观测草稿。
 * 提交快照与结果绑定；切换tab保留输入，失败清旧结果，忙时冻结编辑，无虚假进度。
 */
import { computed, ref, shallowRef } from 'vue'
import { fitBallisticPackage102 } from '../api/ballisticFit'
import { saveBallisticBlob } from '../api/ballisticHttp'
import { parseFitInput } from '../utils/ballisticFit'
import {
  swingFitDefaults,
  swingNew102Row,
  swing102Columns,
  swing102CellOwner,
  swing102CellKey,
} from '../utils/ballisticSwingDefaults'
import BallisticFitFileResult from '../components/BallisticFitFileResult.vue'
const emit = defineEmits(['references'])
const references = shallowRef({})
const inputText = ref(JSON.stringify(swingFitDefaults(), null, 2))
const inputName = ref('')
const loadingInput = ref(false)
const computing = ref(false)
const error = ref('')
const result = shallowRef()
const submitted = shallowRef()
const submittedText = ref('')
let runSequence = 0
const runIdentity = ref(0)
/** 校验不发HTTP；提交预览反映outputDir明确省略，错误由用户点击计算后或导入时显示。 */
const parsed = computed(() => {
  try {
    return { value: parseFitInput(inputText.value), error: '' }
  } catch (failure) {
    return { value: undefined, error: message(failure) }
  }
})
/** 类型/步长错误与空表仍可在主表单修正；危险数字或损坏JSON仅在高级原文中修正。 */
const editable = computed(() => {
  if (parsed.value.error.includes('JSON数字') || parsed.value.error.includes('JSON格式')) return undefined
  try {
    const value = JSON.parse(inputText.value)
    return value &&
      Array.isArray(value.package102) &&
      value.package102.every((row) => {
        if (typeof row !== 'object' || row === null || Array.isArray(row)) return false
        return ['coordinateInfo', 'coordinate', 'coords', 'targetInfo', 'target'].every((key) => {
          const alias = row[key]
          return alias == null || (typeof alias === 'object' && !Array.isArray(alias))
        })
      })
      ? value
      : undefined
  } catch {
    return undefined
  }
})
const changed = computed(() => submitted.value && inputText.value !== submittedText.value)
const taskFields = [
  { key: 'taskName', label: '任务名称' },
  { key: 'taskStartTime', label: '任务起始UTC' },
  { key: 'taskEndTime', label: '任务结束UTC' },
  { key: 'interpolationStepSec', label: '插值步长（秒）' },
]
/** 仅用于表头与辅助标签；保留28列原协议键及编码单位，单元格读写仍使用原键。 */
const columnLabels = {
  track_tgt_id: '目标ID',
  tack3d_time_code: '观测时间码',
  tack3d_tgt_pos_x: '位置 X（0.1米）',
  tack3d_tgt_pos_y: '位置 Y（0.1米）',
  tack3d_tgt_pos_z: '位置 Z（0.1米）',
  tack3d_tgt_vel_x: '速度 X（0.0001米/秒）',
  tack3d_tgt_vel_y: '速度 Y（0.0001米/秒）',
  tack3d_tgt_vel_z: '速度 Z（0.0001米/秒）',
  tack3d_tgt_acc_x: '加速度 X（0.01米/秒²）',
  tack3d_tgt_acc_y: '加速度 Y（0.01米/秒²）',
  tack3d_tgt_acc_z: '加速度 Z（0.01米/秒²）',
  prop_tgt_type_8: '目标类型码',
  prop_tgt_shape_8: '目标形状码',
  prop_tgt_flight_phase_8: '飞行阶段码',
  prob_tgt_critical_incident: '关键事件码',
  prob_tgt_critical_incident_time_code: '事件时间码',
  launch_time_code: '发射时间码',
  launch_coord_frame: '发射坐标系',
  launch_pos_x: '发射位置 X',
  launch_pos_y: '发射位置 Y',
  launch_pos_z: '发射位置 Z',
  launch_pos_unit: '发射位置单位',
  impact_time_code: '落点时间码',
  impact_coord_frame: '落点坐标系',
  impact_pos_x: '落点位置 X',
  impact_pos_y: '落点位置 Y',
  impact_pos_z: '落点位置 Z',
  impact_pos_unit: '落点位置单位',
}
/** 表格只修改唯一JSON草稿的生效字段；旧嵌套别名、17位编码和未知字符串始终原值保留。 */
function editInput(value, key, rowIndex) {
  if (!editable.value) return
  const text = String(value)
  const draft = JSON.parse(inputText.value)
  if (rowIndex === undefined)
    draft[key] = key === 'interpolationStepSec' && text.trim() && Number.isFinite(Number(text)) ? Number(text) : text
  else {
    const owner = swing102CellOwner(draft.package102[rowIndex], key)
    owner[swing102CellKey(owner, key)] = text
  }
  inputText.value = JSON.stringify(draft, null, 2)
}
function cellValue(row, key) {
  const owner = swing102CellOwner(row, key)
  return owner[swing102CellKey(owner, key)] ?? ''
}
/** 新行沿原defaultNewRow(rowCount+1)提供字符串默认；既有记录不摊平重建，保留别名/未知JSON。 */
function changeRows(index) {
  if (!editable.value) return
  const draft = JSON.parse(inputText.value)
  if (index === undefined) draft.package102.push(swingNew102Row(draft.package102.length + 1))
  else draft.package102.splice(index, 1)
  inputText.value = JSON.stringify(draft, null, 2)
}
function loadDefault() {
  inputText.value = JSON.stringify(swingFitDefaults(), null, 2)
  inputName.value = ''
  error.value = ''
}
function message(failure) {
  return failure instanceof Error ? failure.message : '请求失败，请检查服务连接'
}
/** Element UI 上传组件仅在浏览器读取 UTF-8 JSON，不把本机路径当服务输入；失败时保留待纠正的原文本。 */
async function chooseJson(uploadFile) {
  const file = uploadFile.raw
  if (!file) return
  loadingInput.value = true
  error.value = ''
  try {
    inputText.value = await file.text()
    inputName.value = file.name
    parseFitInput(inputText.value)
  } catch (failure) {
    error.value = message(failure)
  } finally {
    loadingInput.value = false
  }
}
/** 提交JSON经过边界校验且独立于编辑文本；开始新尝试立即清旧成功，响应只绑定本次快照。 */
async function calculate() {
  if (computing.value || loadingInput.value) return
  references.value = {}
  emit('references', [])
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
  } catch (failure) {
    error.value = message(failure)
  } finally {
    computing.value = false
  }
}
/** 共享文件真实201事件逐文件更新；清自身拟合候选不影响App其它来源。 */
function updateReference(index, reference) {
  const file = result.value?.response.files[index]
  if (!file) return
  const next = { ...references.value }
  if (reference)
    next[index] = {
      targetId: file.targetId,
      targetName: `拟合/${result.value?.response.taskName}/${file.targetId}`,
      reference,
    }
  else delete next[index]
  references.value = next
  emit('references', Object.values(next))
}
/** 输入快照仅适配outputDir；计算结果JSON下载保持原HTTP字节，不与图表数据混合。 */
function exportInput() {
  if (submitted.value)
    saveBallisticBlob(
      new Blob([JSON.stringify(submitted.value, null, 2)], { type: 'application/json;charset=utf-8' }),
      '102包实际提交.json',
    )
}
function exportResult() {
  if (result.value)
    saveBallisticBlob(
      new Blob([result.value.rawBytes], { type: 'application/json;charset=utf-8' }),
      '102包原拟合响应.json',
    )
}
</script>

<template>
  <section class="ballistic-fit-view ballistic-workspace" aria-label="102包轨迹拟合">
    <header class="ballistic-workspace-header"><h2>102包轨迹拟合</h2><span v-if="inputName" class="ballistic-source-badge">{{ inputName }}</span></header>
    <div class="ballistic-workbench-grid">
      <div class="ballistic-input-column">
        <div class="ballistic-input-scroll">
        <fieldset class="ballistic-native-form ballistic-panel" :disabled="computing || loadingInput">
          <legend>任务与观测</legend>
          <div class="ballistic-actions"><el-button data-test="load-default-fit" @click="loadDefault">加载默认数据</el-button><el-button @click="changeRows()">新增102记录</el-button></div>
          <el-form v-if="editable" label-position="top" class="ballistic-field-grid"><el-form-item v-for="field in taskFields" :key="field.key" :label="field.label"><el-input :value="editable[field.key] ?? ''" :data-fit-field="field.key" :inputmode="field.key === 'interpolationStepSec' ? 'decimal' : undefined" @input="editInput($event, field.key)" /></el-form-item></el-form>
          <p class="ballistic-field-hint">每行一个观测点 · 共28列，可横向滚动。UTC不转换本地时区。</p>
          <div v-if="editable" class="ballistic-table-scroll"><el-table :data="editable.package102" border size="small" table-layout="auto">
            <el-table-column label="操作" width="128" fixed="left"><template #default="{ $index }"><el-button size="small" @click="changeRows($index)">删除第{{ $index + 1 }}行</el-button></template></el-table-column>
            <el-table-column v-for="key in swing102Columns" :key="key" :label="columnLabels[key]" :prop="key" :min-width="key.endsWith('time_code') ? 220 : 150"><template #header><span :title="key">{{ columnLabels[key] }}</span></template><template #default="{ row, $index }"><el-input :value="cellValue(row, key)" :label="`第${$index + 1}行 ${columnLabels[key]}`" :title="key" :style="key.endsWith('time_code') ? { minWidth: '220px' } : undefined" :data-fit-cell="`${$index}/${key}`" @input="editInput($event, key, $index)" /></template></el-table-column>
          </el-table></div>
          <details><summary>高级设置 · JSON导入与编辑</summary>
            <el-form label-position="top"><el-form-item label="导入请求 JSON"><el-upload action="" :auto-upload="false" :show-file-list="false" accept=".json,application/json" :disabled="computing || loadingInput" :on-change="chooseJson"><el-button>选择 JSON 文件</el-button></el-upload></el-form-item></el-form>
            <p v-if="inputName">已读取 {{ inputName }}</p>
            <el-form label-position="top" class="ballistic-json-label"><el-form-item label="完整请求 JSON"><el-input v-model="inputText" data-test="fit-input" type="textarea" :rows="18" spellcheck="false" /></el-form-item></el-form>
            <p v-if="parsed.value?.omittedOutputDir" data-test="fit-storage-notice">导入的 outputDir 将省略，结果保存到服务管理目录。</p>
          </details>
          <details><summary>编码、单位与兼容字段</summary>
            <p>位置编码为0.1米，速度为0.0001米/秒，加速度为0.01米/秒²。时间码、ID、代码和观测值须加引号，避免长数字精度丢失。</p>
            <p>任务起止时间按字面UTC提交，仅用于任务区间；插值步长单位为秒，省略时为1，最小0.001。</p>
            <p>JSON保留 tack3d / track3d 时间别名及 coordinateInfo / coordinate / coords、targetInfo / target。别名优先级和模型选择由服务处理；结果目录统一管理，不使用 outputDir。</p>
          </details>
          <p v-if="parsed.error" role="alert" class="ballistic-error">输入待纠正：{{ parsed.error }}；可在高级JSON中修正。</p>
          <p v-if="parsed.value" class="ballistic-field-hint">{{ parsed.value.request.package102.length }} 条观测 · 步长 {{ parsed.value.request.interpolationStepSec ?? 1 }} 秒</p>
        </fieldset>
        </div>
        <div class="ballistic-compute-bar">
          <p v-if="loadingInput" role="status">正在读取 JSON…</p>
          <p v-if="error" role="alert" class="ballistic-error">{{ error }}</p>
          <el-button type="primary" class="ballistic-primary-action" data-test="fit-compute" :disabled="computing || loadingInput" @click="calculate">{{ computing ? '正在拟合…' : '开始拟合' }}</el-button>
        </div>
      </div>
      <aside class="ballistic-results-column ballistic-panel" aria-label="拟合结果" :aria-busy="String(computing)">
        <h3>拟合结果</h3>
        <div v-if="!result" class="ballistic-empty-state" role="status"><p>{{ computing ? '正在拟合，请等待结果。' : '完成左侧任务与观测设置，点击“开始拟合”。' }}</p></div>
        <section v-if="result && submitted" data-test="fit-success" aria-label="本次拟合结果">
          <h3>{{ result.response.taskName }} · {{ result.response.targetCount }} 个目标</h3>
          <p v-if="changed" role="status">输入已修改，当前结果来自上次计算。重新拟合以更新结果。</p>
          <div class="ballistic-actions"><el-button @click="exportInput">下载输入 JSON</el-button><el-button @click="exportResult">下载结果 JSON</el-button></div>
          <details><summary>计算输入快照</summary><pre data-test="fit-submitted">{{ JSON.stringify(submitted, null, 2) }}</pre></details>
          <details><summary>全部102观测数据</summary><pre>{{ JSON.stringify(submitted.package102, null, 2) }}</pre></details>
          <BallisticFitFileResult v-for="(file, index) in result.response.files" :key="`${runIdentity}/${index}/${file.targetId}`" :file="file" :task-name="result.response.taskName" @reference="updateReference(index, $event)" />
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
