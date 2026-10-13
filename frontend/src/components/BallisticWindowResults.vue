<script setup lang="ts">
/** 原格式器结果按偏移→卫星→载荷→目标→弧段呈现；只展示返回层级，不补不可见星。 */
import { computed, ref, shallowRef, watch } from 'vue'
import type { BallisticVisibleArc, BallisticWindowResponse } from '../api/ballisticWindowTypes'
import BallisticTrajectoryView from './BallisticTrajectoryView.vue'
const props = defineProps<{ result: BallisticWindowResponse; pointOutput: 0 | 1 }>()
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
    <p>参考UTC：{{ result.referenceUtc }} · 根载荷类型：{{ result.payloadType }}</p>
    <p v-if="result.best" data-test="best-summary">服务 best：偏移 {{ result.best.offsetSecond }} 秒 · {{ result.best.visibleArcCount }} 弧 · {{ result.best.visiblePointCount }} 点 · 累计 {{ result.best.visibleDurationSec }} 秒（逐星逐载荷累计，不去重）</p>
    <p v-else>服务未返回 best。</p>
    <p v-if="!offsets.length">服务未返回候选偏移。</p>
    <template v-if="selectedOffset">
      <label>查看全部候选偏移<select v-model="selectedIndex" data-test="offset-select"><option v-for="(offset, index) in offsets" :key="index" :value="index">{{ offset.offsetSecond }} 秒 · {{ offset.visibleArcCount }} 弧 · {{ offset.visibleDurationSec }} 秒{{ offset.offsetSecond === result.best?.offsetSecond ? ' · 服务best' : '' }}</option></select></label>
      <h3>偏移 {{ selectedOffset.offsetSecond }} 秒的真实层级</h3>
      <p>{{ selectedOffset.visibleArcCount }} 弧 · {{ selectedOffset.visiblePointCount }} 点 · 累计 {{ selectedOffset.visibleDurationSec }} 秒 · 首目标发射UTC {{ selectedOffset.launchTimeUtc }} · J2000 {{ selectedOffset.launchTimeJ2000Sec }} 秒</p>
      <p v-if="selectedOffset.finalVisibleWindow">总窗口：{{ selectedOffset.finalVisibleWindow.startTime }} → {{ selectedOffset.finalVisibleWindow.endTime }} UTC（{{ selectedOffset.finalVisibleWindow.durationSeconds }} 秒）</p><p v-else>总窗口为空。</p>
      <details v-for="target in selectedOffset.targets" :key="target.targetId" class="ballistic-result-target"><summary>目标 {{ target.targetName }} · {{ target.targetId }} · {{ target.visibleWindows.length }} 窗口</summary><p>源 {{ target.csvPath }} · 基准UTC {{ target.baseLaunchUTC }} · 本次发射UTC {{ target.launchUTC }}</p>
        <div class="ballistic-table-scroll"><table><thead><tr><th>星/载荷/弧段</th><th>开始UTC</th><th>结束UTC</th><th>时长 s</th><th>点数</th></tr></thead><tbody><tr v-for="(window, index) in target.visibleWindows" :key="index"><td>{{ window.satelliteName }} / {{ window.payloadName }} / {{ window.arcIndex }}</td><td>{{ window.startTime }}</td><td>{{ window.endTime }}</td><td>{{ window.durationSeconds }}</td><td>{{ window.pointCount }}</td></tr></tbody></table></div>
      </details>
      <p v-if="!selectedOffset.visibleSatellites.length">该偏移没有可见卫星或弧段。</p>
      <article v-for="satellite in selectedOffset.visibleSatellites" :key="satellite.satelliteIndex" class="ballistic-result-satellite">
        <h3>卫星 {{ satellite.satelliteName }} · {{ satellite.satelliteId }} · 原索引 {{ satellite.satelliteIndex }}</h3>
        <section v-for="payload in satellite.payloadResults" :key="payload.payloadId" class="ballistic-result-payload">
          <h4>载荷 {{ payload.payloadName }} · {{ payload.payloadId }} · {{ payload.payloadType }}</h4>
          <details><summary>原服务约束摘要（标准完整配置见提交快照）</summary><pre>{{ JSON.stringify(payload.visibilityConstraint, null, 2) }}</pre></details>
          <section v-for="target in payload.targets" :key="target.targetId">
            <h4>目标 {{ target.targetName }} · {{ target.targetId }}</h4>
            <div class="ballistic-table-scroll"><table><thead><tr><th>弧段</th><th>开始UTC</th><th>结束UTC</th><th>开始/结束 J2000 s</th><th>时长 s</th><th>真实点数</th><th>轨迹</th></tr></thead><tbody><tr v-for="arc in target.visibleArcs" :key="arc.arcIndex"><td>{{ arc.arcIndex }}</td><td>{{ arc.startTime }}</td><td>{{ arc.endTime }}</td><td>{{ arc.startJ2000Sec }} / {{ arc.endJ2000Sec }}</td><td>{{ arc.durationSeconds }}</td><td>{{ arc.pointCount }}</td><td><button data-test="show-trajectory" type="button" @click="showArc(arc, `${satellite.satelliteName}(${satellite.satelliteId}) / ${payload.payloadName}(${payload.payloadId}) / ${target.targetName}(${target.targetId})`)">查看轨迹</button></td></tr></tbody></table></div>
          </section>
        </section>
      </article>
      <BallisticTrajectoryView v-if="selectedArc" :points="selectedArc.arc.trajectoryPoints" :identity="selectedArc.identity" :point-output="pointOutput" />
    </template>
  </section>
</template>
