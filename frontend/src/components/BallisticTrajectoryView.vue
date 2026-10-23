<script>
export default { name: 'BallisticTrajectoryView' }
</script>

<script setup>
/**
 * 真实窗口内点的轻量SVG与分页表；props.points保留完整原数据，分页只影响显示。
 * 经纬图跨±180°断线，避免假连线；高度图横轴原relativeTimeSec，纵轴米，不重新采样或插值。
 */
import { computed, ref, watch } from 'vue'
const props = defineProps({
  points: { type: Array, required: true },
  identity: { type: String, required: true },
  pointOutput: { type: null, required: true },
})
const page = ref(1)
const pageSize = 100
watch(
  () => props.points,
  () => {
    page.value = 1
  },
)
const displayed = computed(() => props.points.slice((page.value - 1) * pageSize, page.value * pageSize))
const longitudePath = computed(() =>
  props.points
    .map((point, index) => {
      const previous = props.points[index - 1]
      const command = !previous || Math.abs(point.longitudeDeg - previous.longitudeDeg) > 180 ? 'M' : 'L'
      return `${command}${30 + ((point.longitudeDeg + 180) / 360) * 720},${20 + ((90 - point.latitudeDeg) / 180) * 360}`
    })
    .join(' '),
)
const bounds = computed(() => {
  let minTime = Infinity,
    maxTime = -Infinity,
    minHeight = Infinity,
    maxHeight = -Infinity
  for (const point of props.points) {
    minTime = Math.min(minTime, point.relativeTimeSec)
    maxTime = Math.max(maxTime, point.relativeTimeSec)
    minHeight = Math.min(minHeight, point.heightM)
    maxHeight = Math.max(maxHeight, point.heightM)
  }
  return { minTime, maxTime, minHeight, maxHeight }
})
const heightPath = computed(() =>
  props.points
    .map((point, index) => {
      const { minTime, maxTime, minHeight, maxHeight } = bounds.value
      return `${index ? 'L' : 'M'}${40 + ((point.relativeTimeSec - minTime) / (maxTime - minTime || 1)) * 700},${
        20 + ((maxHeight - point.heightM) / (maxHeight - minHeight || 1)) * 220
      }`
    })
    .join(' '),
)
</script>

<template>
  <section data-test="trajectory-view" class="ballistic-trajectory">
    <h3>窗口轨迹 · {{ identity }}</h3>
    <p v-if="pointOutput === 0">本次轨迹点输出为0，仅显示窗口摘要。</p>
    <p v-else-if="!points.length">该弧段未返回轨迹点，无法绘图。</p>
    <template v-if="points.length">
      <p>{{ points.length }} 个点 · 经度/纬度 (°) · 高度 (m)</p>
      <details><summary>时间与图例说明</summary><p>时间为 UTC/J2000 秒；源轨迹 UTC 与发射偏移后的绝对 UTC 分列显示。经纬图在跨日期变更线处断线。</p></details>
      <figure><figcaption>经纬轨迹 · 经度 −180°…180° / 纬度 −90°…90°</figcaption>
        <svg viewBox="0 0 780 420" role="img" :aria-label="`${identity}经纬度轨迹`"><rect x="30" y="20" width="720" height="360" fill="#f1f6fa" stroke="#8094a5"/><path d="M390,20V380 M30,200H750" stroke="#ccd7df"/><path data-test="longitude-path" :d="longitudePath" fill="none" stroke="#126399" stroke-width="2"/><text x="30" y="405">−180°</text><text x="370" y="405">0°</text><text x="710" y="405">180°</text></svg>
      </figure>
      <figure><figcaption :title="`高度 ${bounds.minHeight}…${bounds.maxHeight} 米；时间 ${bounds.minTime}…${bounds.maxTime} 秒`">高度 / 相对时间 · m / s</figcaption>
        <svg viewBox="0 0 780 280" role="img" :aria-label="`${identity}高度时间轨迹`"><path d="M40,20V240H740" fill="none" stroke="#8094a5"/><path :d="heightPath" fill="none" stroke="#8b4d0b" stroke-width="2"/><text x="40" y="270">{{ bounds.minTime }} s</text><text x="650" y="270">{{ bounds.maxTime }} s</text></svg>
      </figure>
      <details><summary>轨迹点数据 · {{ points.length }} 点</summary><div class="ballistic-table-scroll"><el-table :data="displayed" border size="small" table-layout="auto"><el-table-column prop="longitudeDeg" label="经度 °" min-width="100" /><el-table-column prop="latitudeDeg" label="纬度 °" min-width="100" /><el-table-column prop="heightM" label="高度 m" min-width="120" /><el-table-column prop="absoluteTimeUtc" label="绝对UTC" min-width="190" /><el-table-column prop="absoluteTimeJ2000Sec" label="J2000 s" min-width="150" /><el-table-column prop="relativeTimeSec" label="相对时间 s" min-width="120" /><el-table-column prop="sourceTrajectoryUtc" label="源轨迹UTC" min-width="190" /></el-table></div>
      <div class="ballistic-actions"><el-button :disabled="page <= 1" @click="page--">轨迹上一页</el-button><span>第 {{ page }} / {{ Math.ceil(points.length / pageSize) }} 页 · 每页 {{ pageSize }} 点</span><el-button :disabled="page * pageSize >= points.length" @click="page++">轨迹下一页</el-button></div>
      </details>
    </template>
  </section>
</template>
