<script setup lang="ts">
/** 原格式器结果按偏移→卫星→载荷→目标→弧段呈现；只展示返回层级，不补不可见星。 */
import { computed, ref, shallowRef, watch } from 'vue'
import type { BallisticVisibleArc, BallisticWindowResponse } from '../api/ballisticWindowTypes'
import BallisticTrajectoryView from './BallisticTrajectoryView.vue'
const props = defineProps<{ result: BallisticWindowResponse; pointOutput: 0 | 1 }>()
const emit = defineEmits<{ edit: [section: 'payloads' | 'search'] }>()
const selectedIndex = ref(0)
const selectedArc = shallowRef<{ arc: BallisticVisibleArc; identity: string }>()
const offsets = computed(() => props.result.allResults.length ? props.result.allResults : props.result.best ? [props.result.best] : [])
const selectedOffset = computed(() => offsets.value[selectedIndex.value])
/** 将服务原有层级摊平成展示行，行内保留实际弧段引用以打开原轨迹。 */
const arcRows = computed(() => {
  const offset = selectedOffset.value
  if (!offset) return []
  return offset.visibleSatellites.flatMap((satellite) => satellite.payloadResults.flatMap((payload) => payload.targets.flatMap((target) => target.visibleArcs.map((arc) => ({
    key: `${satellite.satelliteIndex}/${payload.payloadId}/${target.targetId}/${arc.arcIndex}`,
    satellite,
    payload,
    target,
    arc,
  })))))
})
watch(() => props.result, () => {
  const index = offsets.value.findIndex((offset) => offset.offsetSecond === props.result.best?.offsetSecond)
  selectedIndex.value = index < 0 ? 0 : index
  selectedArc.value = undefined
}, { immediate: true })
watch(selectedIndex, () => { selectedArc.value = undefined })

/** 选择对象直接引用服务弧段，不复制/归并点；身份包括当前偏移与四级层次。 */
function showArc(arc: BallisticVisibleArc, identity: string): void {
  selectedArc.value = { arc, identity: `偏移${selectedOffset.value?.offsetSecond}秒 / ${identity} / 弧段${arc.arcIndex}` }
}
</script>

<template>
  <section class="ballistic-window-results" aria-label="真实窗口结果">
    <div v-if="result.best" class="ballistic-result-metrics" data-test="best-summary">
      <div><strong>{{ result.best.visibleArcCount }}</strong><span>可见弧段</span></div><div><strong>{{ result.best.visibleDurationSec }}<small> s</small></strong><span>累计可见时长</span></div><div><strong>{{ result.best.offsetSecond }}<small> s</small></strong><span>最优发射偏移</span></div>
    </div>
    <p class="ballistic-muted">参考 UTC {{ result.referenceUtc }}</p>
    <p v-if="!offsets.length">服务未返回候选偏移。</p>
    <template v-if="selectedOffset">
      <el-form label-position="top"><el-form-item label="候选发射偏移"><el-select v-model="selectedIndex" data-test="offset-select"><el-option v-for="(offset, index) in offsets" :key="index" :value="index" :label="`${offset.offsetSecond} 秒 · ${offset.visibleArcCount} 弧 · ${offset.visibleDurationSec} 秒${offset.offsetSecond === result.best?.offsetSecond ? ' · 最优' : ''}`" /></el-select></el-form-item></el-form>
      <p class="ballistic-muted">{{ selectedOffset.visiblePointCount }} 个轨迹点 · 累计 {{ selectedOffset.visibleDurationSec }} 秒</p>
      <p v-if="selectedOffset.finalVisibleWindow" class="ballistic-window-range">{{ selectedOffset.finalVisibleWindow.startTime }}<br /><span>至 {{ selectedOffset.finalVisibleWindow.endTime }} UTC</span></p>
      <div class="ballistic-table-scroll" v-if="arcRows.length"><el-table :data="arcRows" border size="small" class="ballistic-arc-table" table-layout="auto">
        <el-table-column label="卫星 / 载荷" min-width="180"><template #default="{ row }"><strong>{{ row.satellite.satelliteName }}</strong><br /><span>{{ row.payload.payloadName }} · 弧 {{ row.arc.arcIndex }}</span></template></el-table-column>
        <el-table-column label="目标 / 时段 (UTC)" min-width="220"><template #default="{ row }"><strong>{{ row.target.targetName }}</strong><br />{{ row.arc.startTime }}<br />{{ row.arc.endTime }}</template></el-table-column>
        <el-table-column label="时长" width="100"><template #default="{ row }">{{ row.arc.durationSeconds }} s<br /><span>{{ row.arc.pointCount }} 点</span></template></el-table-column>
        <el-table-column label="轨迹" width="90"><template #default="{ row }"><el-button size="small" data-test="show-trajectory" @click="showArc(row.arc, `${row.satellite.satelliteName}(${row.satellite.satelliteId}) / ${row.payload.payloadName}(${row.payload.payloadId}) / ${row.target.targetName}(${row.target.targetId})`)">轨迹</el-button></template></el-table-column>
      </el-table></div>
      <BallisticTrajectoryView v-if="selectedArc" :points="selectedArc.arc.trajectoryPoints" :identity="selectedArc.identity" :point-output="pointOutput" />
      <details v-if="selectedOffset.visibleSatellites.length"><summary>时刻、目标与约束详情</summary><p>首目标发射 UTC {{ selectedOffset.launchTimeUtc }} · J2000 {{ selectedOffset.launchTimeJ2000Sec }} 秒</p><p>累计时长按卫星与载荷相加，不对重叠时段去重。</p><pre>{{ JSON.stringify({ targets: selectedOffset.targets, constraints: selectedOffset.visibleSatellites.map(s => ({ satelliteId: s.satelliteId, payloads: s.payloadResults.map(p => ({ payloadId: p.payloadId, constraint: p.visibilityConstraint })) })) }, null, 2) }}</pre></details>
    </template>
    <div v-if="!selectedOffset || !selectedOffset.visibleArcCount" class="ballistic-empty-state" data-test="window-empty"><span class="ballistic-state-mark">∅</span><h3>未找到可见窗口</h3><p>该偏移没有可见卫星或弧段。</p><p class="ballistic-muted">可检查载荷约束与发射时间范围后重新计算；当前结果未提供逐项淘汰原因。</p><div class="ballistic-actions"><el-button data-test="edit-payloads" @click="emit('edit', 'payloads')">调整载荷约束</el-button><el-button data-test="edit-search" @click="emit('edit', 'search')">调整搜索时间</el-button></div></div>
  </section>
</template>
