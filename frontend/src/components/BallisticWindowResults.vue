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
      <label>候选发射偏移<select v-model="selectedIndex" data-test="offset-select"><option v-for="(offset, index) in offsets" :key="index" :value="index">{{ offset.offsetSecond }} 秒 · {{ offset.visibleArcCount }} 弧 · {{ offset.visibleDurationSec }} 秒{{ offset.offsetSecond === result.best?.offsetSecond ? ' · 最优' : '' }}</option></select></label>
      <p class="ballistic-muted">{{ selectedOffset.visiblePointCount }} 个轨迹点 · 累计 {{ selectedOffset.visibleDurationSec }} 秒</p>
      <p v-if="selectedOffset.finalVisibleWindow" class="ballistic-window-range">{{ selectedOffset.finalVisibleWindow.startTime }}<br /><span>至 {{ selectedOffset.finalVisibleWindow.endTime }} UTC</span></p>
      <div class="ballistic-table-scroll" v-if="selectedOffset.visibleSatellites.length"><table class="ballistic-arc-table"><thead><tr><th>卫星 / 载荷</th><th>目标 / 时段 (UTC)</th><th>时长</th><th></th></tr></thead><tbody>
        <template v-for="satellite in selectedOffset.visibleSatellites" :key="satellite.satelliteIndex"><template v-for="payload in satellite.payloadResults" :key="payload.payloadId"><template v-for="target in payload.targets" :key="target.targetId">
          <tr v-for="arc in target.visibleArcs" :key="arc.arcIndex"><td><strong>{{ satellite.satelliteName }}</strong><br /><span>{{ payload.payloadName }} · 弧 {{ arc.arcIndex }}</span></td><td><strong>{{ target.targetName }}</strong><br />{{ arc.startTime }}<br />{{ arc.endTime }}</td><td>{{ arc.durationSeconds }} s<br /><span>{{ arc.pointCount }} 点</span></td><td><button data-test="show-trajectory" type="button" @click="showArc(arc, `${satellite.satelliteName}(${satellite.satelliteId}) / ${payload.payloadName}(${payload.payloadId}) / ${target.targetName}(${target.targetId})`)">轨迹</button></td></tr>
        </template></template></template>
      </tbody></table></div>
      <BallisticTrajectoryView v-if="selectedArc" :points="selectedArc.arc.trajectoryPoints" :identity="selectedArc.identity" :point-output="pointOutput" />
      <details v-if="selectedOffset.visibleSatellites.length"><summary>时刻、目标与约束详情</summary><p>首目标发射 UTC {{ selectedOffset.launchTimeUtc }} · J2000 {{ selectedOffset.launchTimeJ2000Sec }} 秒</p><p>累计时长按卫星与载荷相加，不对重叠时段去重。</p><pre>{{ JSON.stringify({ targets: selectedOffset.targets, constraints: selectedOffset.visibleSatellites.map(s => ({ satelliteId: s.satelliteId, payloads: s.payloadResults.map(p => ({ payloadId: p.payloadId, constraint: p.visibilityConstraint })) })) }, null, 2) }}</pre></details>
    </template>
    <div v-if="!selectedOffset || !selectedOffset.visibleArcCount" class="ballistic-empty-state" data-test="window-empty"><span class="ballistic-state-mark">∅</span><h3>未找到可见窗口</h3><p>该偏移没有可见卫星或弧段。</p><p class="ballistic-muted">可检查载荷约束与发射时间范围后重新计算；当前结果未提供逐项淘汰原因。</p><div class="ballistic-actions"><button type="button" data-test="edit-payloads" @click="emit('edit', 'payloads')">调整载荷约束</button><button type="button" data-test="edit-search" @click="emit('edit', 'search')">调整搜索时间</button></div></div>
  </section>
</template>
