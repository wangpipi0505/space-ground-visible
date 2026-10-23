<script>
export default { name: 'VisibilityPlanningStep' }
</script>

<script setup>
/** 展示后台观测任务状态和目标弧段；参数由工作台维护，支持筛选、分页与确认选择。 */
import { computed, ref, watch } from 'vue'
import { pageVisibilityTaskResults, putVisibilityTaskResultSelection } from '../api/visibilityTaskResults'
import {
  collectSatelliteObservationStats,
  formatAzimuthRange,
  mergeTimeSegments,
  toWinToolAzimuth,
} from '../utils/winToolSummary'
import { useSelectionConfirm } from '../composables/useSelectionConfirm'
const props = defineProps({
  satellites: { type: Array, required: true },
  selectedWindowLabel: { type: String, required: true },
  selectedWindowStartTime: { type: String, required: true },
  selectedWindowEndTime: { type: String, required: true },
  timeRange: { type: Array, required: true },
  form: { type: null, required: true },
  plan: { type: null, required: true },
  loading: { type: Boolean, required: true },
  mode: { type: null, required: true },
  task: { type: null, required: true },
  taskRunning: { type: Boolean, required: true },
  taskProgressPercent: { type: Number, required: true },
  formatTime: { type: Function, required: true },
  formatDuration: { type: Function, required: true },
})
const emit = defineEmits(['plan', 'update:mode', 'cancelTask'])
/** 供自定义光照组合复选框使用的三种精确状态。 */
const illuminationStates = [
  { value: 'SUNLIT', label: '阳照' },
  { value: 'PENUMBRA', label: '半影' },
  { value: 'UMBRA', label: '本影' },
]
/** 后台规划任务状态对应的中文标签。 */
const taskStatusText = {
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '已完成',
  FAILED: '执行失败',
  CANCELLED: '已取消',
}
/**
 * 仅多星组合弧段可展开查看各星独立观测时段；单星弧段通过行样式隐藏展开按钮。
 */
function arcRowClassName({ row }) {
  return (row.satelliteSegments?.length ?? 0) > 1 ? 'combination-arc-row' : 'single-arc-row'
}
/** 每页只渲染有限目标组，避免全量规划结果使浏览器一次创建过多表格节点。 */
const TARGET_RESULT_PAGE_SIZES = [10, 20, 50, 100]
const targetResultTargetId = ref('ALL')
const combinationFilter = ref('ALL')
const targetResultPage = ref(1)
const targetResultPageSize = ref(10)
const expandedTargetIds = ref([])
const skippedPairsDialogVisible = ref(false)
/** 组合成员的顺序不影响身份；用于让配置约束和接口返回稳定匹配。 */
function combinationKey(satelliteIds) {
  return satelliteIds
    .map(String)
    .sort((left, right) => left.localeCompare(right, 'zh-CN'))
    .join('|')
}
/** 按参与卫星数量生成用户可读的组合类别。 */
function combinationType(satelliteCount) {
  if (satelliteCount === 1) return '单星观测'
  if (satelliteCount === 2) return '两星共同观测'
  if (satelliteCount === 3) return '三星共同观测'
  return `${satelliteCount}星共同观测`
}
/** 用当前任务卫星名称补全没有结果的配置组合，保证零命中约束也可识别。 */
const satelliteNameById = computed(
  () => new Map(props.satellites.map((satellite) => [String(satellite.id), satellite.name])),
)
/**
 * 保留后端的目标和组合边界，禁止再把弧段展平成一张列表。
 * 后端会为同一目标的不同组合分别返回结果；这里仅合并同一目标、同一无序组合的弧段。
 */
const targetResultGroups = computed(() => {
  const targets = new Map()
  for (const result of props.plan?.results ?? []) {
    const target = targets.get(result.targetId) ?? {
      targetId: result.targetId,
      targetName: result.targetName,
      combinations: [],
      targetNorad: result.targetNorad ?? undefined,
      targetWeightKg: result.targetWeightKg ?? undefined,
      firstOrbitLine: result.firstOrbitLine ?? undefined,
      secondOrbitLine: result.secondOrbitLine ?? undefined,
    }
    if (!targets.has(result.targetId)) targets.set(result.targetId, target)
    const key = combinationKey(result.observerSatelliteIds)
    let combination = target.combinations.find((item) => item.key === key)
    if (!combination) {
      combination = {
        key,
        satelliteIds: [...result.observerSatelliteIds],
        satelliteNames: [...result.observerSatelliteNames],
        label: result.observerSatelliteNames.join(' + '),
        observationTypeName: combinationType(result.observerSatelliteIds.length),
        arcs: [],
        totalDurationSec: 0,
      }
      target.combinations.push(combination)
    }
    const arcs = result.arcs.map((arc) => ({
      ...arc,
      key: `${key}-${result.targetId}-${arc.startTime}`,
      observerSatelliteNames: combination.label,
      targetName: result.targetName,
      observationTypeName: combination.observationTypeName,
      observerIlluminationText: arc.observerIlluminations
        .map((item) => `${item.satelliteName}：${item.stateName}`)
        .join('；'),
      targetIlluminationName: arc.targetIllumination.stateName,
    }))
    combination.arcs.push(...arcs)
    combination.totalDurationSec += arcs.reduce((total, arc) => total + arc.durationSec, 0)
  }
  return [...targets.values()]
    .map((target) => ({
      ...target,
      combinations: [...target.combinations].sort(
        (left, right) =>
          right.satelliteIds.length - left.satelliteIds.length || left.label.localeCompare(right.label, 'zh-CN'),
      ),
    }))
    .sort((left, right) => left.targetName.localeCompare(right.targetName, 'zh-CN'))
})
/** 本次已命中的目标直接生成下拉选项，避免输入框搜索与页面结果不一致。 */
const targetResultOptions = computed(() =>
  targetResultGroups.value.map((target) => ({
    value: target.targetId,
    label: `${target.targetName}（${target.targetId}）`,
  })),
)
/** 组合筛选严格复用用户本次配置，不再以单星、两星、三星等类别推断。 */
const configuredCombinationOptions = computed(() => {
  const options = new Map()
  for (const constraint of props.form.observationConstraints ?? []) {
    const key = combinationKey(constraint.satelliteIds)
    if (options.has(key)) continue
    const label = constraint.satelliteIds
      .map((satelliteId) => satelliteNameById.value.get(String(satelliteId)) ?? String(satelliteId))
      .join(' + ')
    options.set(key, { value: key, label: `${label} ≥ ${props.formatDuration(constraint.minDurationSec)}` })
  }
  return [...options.values()]
})
/** 两个下拉筛选器只影响当前结果的展示，不会修改已完成规划的原始结果。 */
const filteredTargetResultGroups = computed(() => {
  return targetResultGroups.value
    .map((target) => ({
      ...target,
      combinations: target.combinations.filter(
        (combination) => combinationFilter.value === 'ALL' || combination.key === combinationFilter.value,
      ),
    }))
    .filter(
      (target) =>
        target.combinations.length > 0 &&
        (targetResultTargetId.value === 'ALL' || target.targetId === targetResultTargetId.value),
    )
})
const pagedTargetResultGroups = computed(() => {
  const start = (targetResultPage.value - 1) * targetResultPageSize.value
  return filteredTargetResultGroups.value.slice(start, start + targetResultPageSize.value)
})
/**
 * 按目标勾选与确认选择:勾选状态、合并完整时段(并集)与提交逻辑
 * 统一复用 useSelectionConfirm 组合式函数,组件只提供数据与依赖。
 */
const {
  selectedTargetIds,
  confirmingSelection,
  toggleTargetSelection,
  isTargetSelected,
  selectedMergedTimeRangeText,
  confirmSelection,
} = useSelectionConfirm({
  taskId: () => props.task?.taskId,
  findRecordPage: (taskId) => pageVisibilityTaskResults({ pageNumber: 1, pageSize: 20, keyword: taskId }),
  putSelection: putVisibilityTaskResultSelection,
  planningWindow: () => ({ startTime: props.selectedWindowStartTime, endTime: props.selectedWindowEndTime }),
  collectArcs: collectAllArcInputs,
  buildSelections,
})
/** 收集全部目标的弧段(带目标 ID),供确认选择计算勾选目标的合并完整时段。 */
function collectAllArcInputs() {
  return targetResultGroups.value.flatMap((target) =>
    target.combinations.flatMap((combination) =>
      combination.arcs.map((arc) => ({
        targetId: target.targetId,
        startTime: arc.startTime,
        endTime: arc.endTime,
        durationSec: arc.durationSec,
      })),
    ),
  )
}
/** 构建逐目标提交明细:目标元数据 + 合并完整窗口 + 逐星统计(WinTool 口径方位/天底角俯仰)。 */
function buildSelections(targetIds) {
  return targetIds.map((targetId) => {
    const target = targetResultGroups.value.find((item) => item.targetId === targetId)
    if (!target) {
      throw new Error('勾选目标数据缺失')
    }
    const arcs = target.combinations.flatMap((combination) => combination.arcs)
    const satelliteAggregates = collectSatelliteObservationStats(arcs)
    const mergedWindows = mergeTimeSegments(arcs)
    const satellites = satelliteAggregates.map((aggregate) => ({
      satelliteId: aggregate.satelliteId,
      satelliteName: aggregate.satelliteName,
      startTime: aggregate.firstStart ?? '',
      endTime: aggregate.lastEnd ?? '',
      durationSec: aggregate.totalSec,
      distanceMinKm: Number.isFinite(aggregate.minDistanceKm) ? aggregate.minDistanceKm : null,
      distanceMaxKm: Number.isFinite(aggregate.maxDistanceKm) ? aggregate.maxDistanceKm : null,
      azimuthRelativeMinusXMinDeg: Number.isFinite(aggregate.minAzimuthDeg)
        ? toWinToolAzimuth(aggregate.minAzimuthDeg, aggregate.letter)
        : null,
      azimuthRelativeMinusXMaxDeg: Number.isFinite(aggregate.maxAzimuthDeg)
        ? toWinToolAzimuth(aggregate.maxAzimuthDeg, aggregate.letter)
        : null,
      elevationZenithMinDeg: Number.isFinite(aggregate.minElevationDeg) ? aggregate.minElevationDeg + 90 : null,
      elevationZenithMaxDeg: Number.isFinite(aggregate.maxElevationDeg) ? aggregate.maxElevationDeg + 90 : null,
      maxAngleRateDegPerSec: aggregate.maxAngleRateDegPerSec,
    }))
    return {
      targetId: target.targetId,
      targetName: target.targetName,
      noradCatId: target.targetNorad ?? null,
      weightKg: target.targetWeightKg ?? null,
      firstOrbitLine: target.firstOrbitLine ?? null,
      secondOrbitLine: target.secondOrbitLine ?? null,
      combination: satelliteAggregates.map((item) => item.letter ?? item.satelliteName).join('+'),
      mergedWindows: mergedWindows.map((segment) => ({
        startTime: segment.startTime,
        endTime: segment.endTime,
        durationSec: segment.durationSec,
      })),
      totalDurationSec: mergedWindows.reduce((total, segment) => total + segment.durationSec, 0),
      satellites,
    }
  })
}
function formatNumber(value, digits) {
  return Number.isFinite(value) ? value.toFixed(digits) : '—'
}
/** 目标合并观测时长 = 其全部组合时长之和(与后端任务摘要口径一致)。 */
function sumTargetDurationSec(target) {
  return target.combinations.reduce((total, combination) => total + combination.totalDurationSec, 0)
}
/** 摘要块的渲染视图:权重文本、组合时长与逐星统计瓦片。 */
function getSummaryView(target) {
  const weightText = target.targetWeightKg == null ? '未知重量' : `${target.targetWeightKg.toFixed(2)} kg`
  const combos = target.combinations.map((combination) => ({
    label: combination.label,
    duration: props.formatDuration(combination.totalDurationSec),
  }))
  const sats = collectSatelliteObservationStats(target.combinations.flatMap((combination) => combination.arcs)).map(
    (item) => ({
      letterText: item.letter ?? item.satelliteName,
      satelliteName: item.satelliteName,
      windows: item.windows,
      stats: [
        {
          label: '距离范围',
          value: `${formatNumber(item.minDistanceKm, 2)} ~ ${formatNumber(item.maxDistanceKm, 2)} km`,
        },
        { label: '方位(相对-X)', value: formatAzimuthRange(item.minAzimuthDeg, item.maxAzimuthDeg, item.letter).text },
        {
          label: '俯仰(天底角)',
          value: `${formatNumber(item.minElevationDeg + 90, 4)} ~ ${formatNumber(item.maxElevationDeg + 90, 4)} 度`,
        },
        { label: '最大相对角速度', value: `${formatNumber(item.maxAngleRateDegPerSec, 7)} 度/秒` },
      ],
    }),
  )
  return { weightText, combos, sats }
}
function summaryWeightText(target) {
  return target.targetWeightKg == null ? '未知重量' : `${target.targetWeightKg.toFixed(2)} kg`
}
/** 后端可能仅回传部分跳过明细，但总数始终以 totalSkippedPairs 为准。 */
const skippedPairCount = computed(() => props.plan?.totalSkippedPairs ?? props.plan?.skippedPairs?.length ?? 0)
/** 新的规划结果到达时，默认打开第一项目标，便于直接查看结果。 */
watch(targetResultGroups, (groups) => {
  targetResultPage.value = 1
  expandedTargetIds.value = groups.length ? [groups[0].targetId] : []
})
/** 展示筛选条件或每页条数变化时，回到首页以避免展示空页；不会改变已展开的目标。 */
watch([targetResultTargetId, combinationFilter, targetResultPageSize], () => {
  targetResultPage.value = 1
})
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header>
      <div class="compact-card-header">
        <h2>目标观测规划</h2>
        <div class="planning-actions">
          <!-- 全目标扫描始终走后台任务：HTTP 单次同步响应无法可靠推送批次进度。 -->
          <el-tag type="info" effect="plain" data-test="plan-mode">后台任务（显示进度）</el-tag>
          <el-button type="primary" :loading="loading || taskRunning" @click="emit('plan')">开始规划</el-button>
        </div>
      </div>
    </template>
    <el-alert v-if="!selectedWindowLabel" title="请先选择或校验一个下传窗口" type="warning" :closable="false" show-icon />
    <template v-else>
      <div class="planning-context"><span>观测星：{{ satellites.map((satellite) => satellite.name).join('、') || '未选择' }}</span><span>计算范围：{{ selectedWindowLabel }}</span></div>
      <div data-test="observation-time-range" class="planning-context"><span>{{ formatTime(selectedWindowStartTime) }} 至 {{ formatTime(selectedWindowEndTime) }}</span><span>仅在此下传窗口内计算</span></div>
      <el-form label-position="top" class="visibility-form params-inline">
        <el-form-item label="粗选步长（秒）"><el-input-number v-model="form.coarseStepSec" :min="0.001" :step="0.001" :precision="3" controls-position="right" /></el-form-item>
        <el-form-item label="精选步长（秒）"><el-input-number v-model="form.fineStepSec" :min="0.001" :max="form.coarseStepSec" :step="0.001" :precision="3" controls-position="right" /></el-form-item>
        <el-form-item label="光照组合">
          <el-select v-model="form.illuminationConstraint.mode">
            <el-option label="不限" value="ANY" /><el-option label="仅目标阳照" value="TARGET_SUNLIT" />
            <el-option label="观测星阴影且目标阳照" value="OBSERVER_SHADOW_TARGET_SUNLIT" />
            <el-option label="观测星与目标均阴影" value="BOTH_SHADOW" /><el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="form.illuminationConstraint.mode === 'CUSTOM'" class="illumination-custom-editor">
        <span>观测星允许状态：</span><el-checkbox-group v-model="form.illuminationConstraint.observerStates"><el-checkbox v-for="state in illuminationStates" :key="state.value" :label="state.value">{{ state.label }}</el-checkbox></el-checkbox-group>
        <span>目标允许状态：</span><el-checkbox-group v-model="form.illuminationConstraint.targetStates"><el-checkbox v-for="state in illuminationStates" :key="state.value" :label="state.value">{{ state.label }}</el-checkbox></el-checkbox-group>
      </div>
      <div v-if="task" data-test="task-progress" class="task-progress">
        <div class="planning-context">
          <span>任务状态：{{ taskStatusText[task.status] }}</span>
          <span>已完成目标：{{ task.completedTargets ?? 0 }} / {{ task.totalTargets ?? 0 }}（配对 {{ task.completedPairs }} / {{ task.totalPairs }}）</span>
          <el-button v-if="taskRunning" type="danger" size="small" plain data-test="cancel-task" @click="emit('cancelTask')">取消任务</el-button>
        </div>
        <el-progress :percentage="taskProgressPercent" :status="task.status === 'FAILED' ? 'exception' : undefined" />
      </div>
      <div v-if="plan" class="result-section">
        <div class="result-block">
          <div class="result-block-title">观测时段列表</div>
          <div v-if="selectedTargetIds.length" class="selection-confirm-bar" data-test="selection-confirm-bar">
            <span>已选目标 {{ selectedTargetIds.length }} 个</span>
            <span class="selection-merged-range">合并完整时段：{{ selectedMergedTimeRangeText }}</span>
            <el-button type="primary" size="small" :loading="confirmingSelection" data-test="confirm-selection" @click="confirmSelection">确认选择</el-button>
          </div>
          <div class="target-result-filters">
          <el-select v-model="targetResultTargetId" data-test="target-result-select" filterable class="target-result-select" placeholder="选择目标">
            <el-option label="全部目标" value="ALL" />
            <el-option v-for="option in targetResultOptions" :key="option.value" :label="option.label" :value="option.value" data-test="target-result-option" />
          </el-select>
          <el-select v-model="combinationFilter" data-test="combination-result-filter" class="combination-result-filter" placeholder="选择观测组合">
            <el-option label="全部组合" value="ALL" />
            <el-option v-for="option in configuredCombinationOptions" :key="option.value" :label="option.label" :value="option.value" data-test="combination-result-option" />
          </el-select>
          <span class="target-result-count">{{ filteredTargetResultGroups.length }} 个目标</span>
          <el-button v-if="skippedPairCount" data-test="skipped-pairs-button" size="small" plain @click="skippedPairsDialogVisible = true">查看跳过项（{{ skippedPairCount }}）</el-button>
        </div>
        <el-empty v-if="!pagedTargetResultGroups.length" description="当前条件下没有满足约束的可见弧段" />
        <el-collapse v-else v-model="expandedTargetIds" class="target-result-list">
          <el-collapse-item v-for="target in pagedTargetResultGroups" :key="target.targetId" :name="target.targetId" data-test="target-result-group">
            <template #title>
              <div class="target-result-title"><el-checkbox class="target-select-checkbox" data-test="target-select-checkbox" :value="isTargetSelected(target.targetId)" @click.native.stop @change="(value) => toggleTargetSelection(target.targetId, Boolean(value))" /><strong>{{ target.targetName }}</strong><span>目标 ID：{{ target.targetId }}</span><el-tag size="small">{{ target.combinations.length }} 个观测组合</el-tag></div>
            </template>
            <section v-for="combination in target.combinations" :key="combination.key" class="combination-result-group" data-test="combination-result-group">
              <div class="combination-result-header">
                <div><strong>{{ combination.label }}</strong><el-tag size="small" type="success">{{ combination.observationTypeName }}</el-tag></div>
                <span>共 {{ formatDuration(combination.totalDurationSec) }}，{{ combination.arcs.length }} 条弧段</span>
              </div>
              <el-table :data="combination.arcs" size="small" max-height="260" :row-class-name="arcRowClassName" data-test="arc-table">
                <el-table-column type="expand" width="34">
                  <template #default="scope">
                    <div v-if="scope.row.satelliteSegments?.length" class="satellite-segments" data-test="satellite-segments">
                      <p class="satellite-segment-heading" data-test="satellite-segment-heading">各星原始单星观测窗口（共同窗口由这些窗口取交集形成）</p>
                      <div v-for="segment in scope.row.satelliteSegments" :key="segment.satelliteId" class="satellite-segment" data-test="satellite-segment">
                        <span class="segment-name">{{ segment.satelliteName }}</span><span class="segment-illumination">{{ segment.stateName }}</span>
                        <span class="segment-time">{{ formatTime(segment.startTime) }} 至 {{ formatTime(segment.endTime) }}</span><span class="segment-duration">{{ formatDuration(segment.durationSec) }}</span>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="observerSatelliteNames" label="我方卫星" min-width="140" /><el-table-column prop="targetName" label="目标" min-width="140" />
                <el-table-column prop="observationTypeName" label="观测类型" width="112" /><el-table-column prop="observerIlluminationText" label="观测星光照" min-width="160" /><el-table-column prop="targetIlluminationName" label="目标光照" width="90" />
                <el-table-column label="起止时间" min-width="175"><template #default="scope">{{ formatTime(scope.row.startTime) }}<br>{{ formatTime(scope.row.endTime) }}</template></el-table-column>
                <el-table-column label="时长" width="88"><template #default="scope"><span data-test="qualified-arc-duration">{{ formatDuration(scope.row.durationSec) }}</span></template></el-table-column>
              </el-table>
            </section>
          </el-collapse-item>
        </el-collapse>
        <el-pagination v-if="filteredTargetResultGroups.length" :current-page.sync="targetResultPage" :page-size.sync="targetResultPageSize" class="target-result-pagination" layout="total, sizes, prev, pager, next, jumper" :page-sizes="TARGET_RESULT_PAGE_SIZES" :total="filteredTargetResultGroups.length" />
        </div>
        <div class="result-block">
          <div class="result-block-title">摘要信息</div>
          <el-collapse class="wintool-summary-collapse" data-test="wintool-summary-collapse">
          <el-collapse-item v-for="target in targetResultGroups" :key="'summary-' + target.targetId" :name="target.targetId"
            :title="`${target.targetName}（NORAD ${target.targetNorad ?? '—'}）合并观测时长 ${formatDuration(sumTargetDurationSec(target))}`">
            <div class="wintool-mission">
              <div class="mission-head">
                <span class="mission-title">Mission : 0 {{ target.targetName }}（{{ target.targetName }}，{{ summaryWeightText(target) }}）</span>
              </div>
              <pre v-if="target.firstOrbitLine && target.secondOrbitLine" class="mission-tle">{{ target.firstOrbitLine }}
{{ target.secondOrbitLine }}</pre>
              <div class="mission-combos">
                <span v-for="combination in target.combinations" :key="combination.key" class="mission-combo">
                  合并观测时长（{{ combination.label }}）：{{ formatDuration(combination.totalDurationSec) }}
                </span>
              </div>
              <div v-for="satellite in getSummaryView(target).sats" :key="satellite.satelliteName" class="sat-block">
                <div class="sat-head">{{ satellite.letterText }} 星情况 · {{ satellite.satelliteName }}</div>
                <div v-for="(window, windowIndex) in satellite.windows" :key="windowIndex" class="sat-window">{{ windowIndex + 1 }}:{{ window }}</div>
                <div class="sat-stat-grid">
                  <div v-for="stat in satellite.stats" :key="stat.label" class="sat-stat">
                    <span class="stat-label">{{ stat.label }}</span>
                    <span class="stat-value">{{ stat.value }}</span>
                  </div>
                </div>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
        </div>
        <el-dialog :visible.sync="skippedPairsDialogVisible" title="跳过记录" width="min(900px, 90vw)">
          <p v-if="skippedPairCount > (plan.skippedPairs?.length ?? 0)" class="skipped-detail-note">当前响应展示前 {{ plan.skippedPairs?.length ?? 0 }} 条，跳过总数为 {{ skippedPairCount }} 条。</p>
          <el-table v-if="plan.skippedPairs?.length" :data="plan.skippedPairs" data-test="skipped-pairs-table" size="small" max-height="420">
            <el-table-column prop="observerSatelliteName" label="观测星" min-width="120" />
            <el-table-column prop="targetName" label="目标" min-width="120" />
            <el-table-column prop="stage" label="阶段" width="100" />
            <el-table-column prop="reason" label="跳过原因" min-width="220" show-overflow-tooltip />
          </el-table>
          <el-empty v-else description="当前没有可展示的跳过明细" />
        </el-dialog>
      </div>
    </template>
  </el-card>
</template>

<style scoped>
/* 单星弧段没有可展开内容：隐藏行首展开图标并禁用其点击。 */
:deep(.single-arc-row .el-table__expand-icon) {
  visibility: hidden;
  pointer-events: none;
}

.satellite-segments {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 2px 0 4px 24px;
}

.target-result-filters, .combination-result-header, .target-result-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.target-result-filters { flex-wrap: wrap; margin-bottom: 10px; }
.target-result-select { width: 260px; }
.combination-result-filter { width: 330px; }
.target-result-count { color: var(--el-text-color-secondary); font-size: 13px; }
.target-result-list { border-top: 1px solid var(--el-border-color-lighter); }
.target-result-title { width: 100%; min-width: 0; }
.target-result-title strong { min-width: 140px; }
.target-result-title span { color: var(--el-text-color-secondary); font-size: 12px; }
.target-concise-summary-block { margin: 8px 0 12px; padding: 10px; background: var(--el-fill-color-lighter); }
.target-concise-summary-block p { margin: 0 0 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.target-concise-summary { margin: 0; overflow: auto; white-space: pre-wrap; font: 12px/1.55 Consolas, 'Microsoft YaHei', monospace; }
.combination-result-group { margin: 12px 0; padding: 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; }
.combination-result-header { justify-content: space-between; margin-bottom: 8px; }
.combination-result-header > div { display: flex; align-items: center; gap: 8px; }
.combination-result-header > span { color: var(--el-text-color-secondary); font-size: 13px; }
.target-result-pagination { margin-top: 12px; justify-content: flex-end; }

.satellite-segment {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.segment-name {
  min-width: 120px;
  font-weight: 600;
}

.segment-illumination {
  min-width: 48px;
  opacity: 0.75;
}

.segment-time {
  min-width: 300px;
}

.segment-duration {
  opacity: 0.75;
}

.satellite-segment-heading {
  margin: 0 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.skipped-detail-note {
  margin: 0 0 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>

<style scoped>
/* ===== 结果区分块:观测时段列表 / 摘要信息 ===== */
.result-section { display: flex; flex-direction: column; gap: 14px; }
.result-block { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 12px 14px; background: var(--el-bg-color); }
.result-block-title { font-weight: 600; font-size: 14px; line-height: 1.3; margin-bottom: 10px; padding-left: 8px; border-left: 3px solid var(--el-color-primary); }
.selection-confirm-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; margin: 0 0 10px; padding: 8px 12px; background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-7); border-radius: 4px; }
.selection-merged-range { color: var(--el-text-color-secondary); }
/* ===== 观测规划三参数一行 ===== */
.params-inline { display: flex; flex-wrap: wrap; column-gap: 16px; }
.params-inline :deep(.el-form-item) { margin-bottom: 8px; flex: 1 1 200px; }
/* ===== 摘要信息块(独立折叠块,winTool 格式结构化展示) ===== */
.wintool-summary-collapse {
  margin-top: 12px;
  border-top: 1px dashed var(--el-border-color);
}
.wintool-summary-collapse :deep(.el-collapse-item__header) {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.wintool-mission {
  padding: 4px 2px 8px;
}
.mission-head {
  margin-bottom: 6px;
}
.mission-title {
  font-weight: 700;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.mission-tle {
  margin: 0 0 8px;
  padding: 8px 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  overflow: auto;
  white-space: pre-wrap;
  font: 12px/1.5 Consolas, 'Microsoft YaHei', monospace;
  color: var(--el-text-color-regular);
}
.mission-combos {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-bottom: 10px;
}
.mission-combo {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sat-block {
  padding: 8px 10px;
  margin-bottom: 8px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}
.sat-block:last-child {
  margin-bottom: 0;
}
.sat-head {
  margin-bottom: 6px;
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.sat-window {
  font: 12px/1.6 Consolas, 'Microsoft YaHei', monospace;
  color: var(--el-text-color-regular);
}
.sat-stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 16px;
  margin-top: 8px;
  padding: 8px 10px;
  background: var(--el-bg-color);
  border-radius: 4px;
}
.sat-stat {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  line-height: 1.6;
}
.stat-label {
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-family: Consolas, 'Microsoft YaHei', monospace;
  color: var(--el-text-color-primary);
}
</style>
