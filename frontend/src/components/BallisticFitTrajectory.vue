<script setup lang="ts">
/**
 * 全部真实CSV行的ECI XY投影与XYZ/相对UTC秒曲线；直接连接相邻导出点，不平滑或生成插值。
 * 四图红色标记仅isOriginal=1，原点表保持CSV文本。dt拟合/模拟为相邻毫秒，重定时为累计毫秒且可缺省；timeIntervalMs为相邻毫秒，UTC原文本不转换。
 */
import { computed } from 'vue'
import type { FitCsvPoint } from '../api/ballisticFitTypes'
const props = defineProps<{ points: FitCsvPoint[]; identity: string; retimed?: boolean }>()
type Axis = 'eciX' | 'eciY' | 'eciZ' | 'relativeTimeSec'
const originals = computed(() => props.points.filter((point) => point.original))
const graphs = computed(() => ([['eciX', 'eciY', 'ECI XY投影', 'X m', 'Y m'], ['relativeTimeSec', 'eciX', 'ECI X / 相对UTC时间', '相对UTC s', 'X m'], ['relativeTimeSec', 'eciY', 'ECI Y / 相对UTC时间', '相对UTC s', 'Y m'], ['relativeTimeSec', 'eciZ', 'ECI Z / 相对UTC时间', '相对UTC s', 'Z m']] as const).map(([horizontal, vertical, label, xUnit, yUnit]) => {
  const bounds = (axis: Axis) => {
    let min = Infinity, max = -Infinity
    for (const point of props.points) { min = Math.min(min, point[axis]); max = Math.max(max, point[axis]) }
    return { min, max }
  }
  const x = bounds(horizontal), y = bounds(vertical)
  const project = (point: FitCsvPoint) => ({ x: 45 + (point[horizontal] - x.min) / (x.max - x.min || 1) * 670, y: 20 + (y.max - point[vertical]) / (y.max - y.min || 1) * 220 })
  return { label, xUnit, yUnit, x, y, path: props.points.map((point, index) => { const position = project(point); return `${index ? 'L' : 'M'}${position.x},${position.y}` }).join(' '), markers: originals.value.map(project) }
}))
</script>

<template>
  <section data-test="fit-trajectory" class="ballistic-trajectory">
    <h4>目标 {{ identity }} · 完整CSV {{ points.length }} 行 · {{ originals.length }} 个 isOriginal=1 原约束点</h4>
    <p>ECI位置为米，速度为m/s，{{ retimed ? '重定时 dt 为累计相对首行毫秒，timeIntervalMs 为相邻步长毫秒（dt可缺省）' : '拟合/模拟 dt 为相邻步长毫秒' }}；曲线横轴为原分列UTC相对首行的秒数，UTC显示保持原文本。图形仅显示原导出位置3位小数、速度9位小数精度，不重拟合、不再坐标转换。红点为原始约束标记。</p>
    <p>完整输出 UTC：{{ points[0]?.utc }} → {{ points[points.length - 1]?.utc }}</p>
    <figure v-for="graph in graphs" :key="graph.label"><figcaption>{{ graph.label }} · 横轴 {{ graph.x.min }}…{{ graph.x.max }} {{ graph.xUnit }} · 纵轴 {{ graph.y.min }}…{{ graph.y.max }} {{ graph.yUnit }}</figcaption>
      <svg viewBox="0 0 760 280" role="img" :aria-label="`${identity} ${graph.label}`"><path d="M45,20V240H715" fill="none" stroke="#8094a5"/><path :d="graph.path" fill="none" stroke="#126399" stroke-width="2"/><circle v-for="(point, index) in graph.markers" :key="index" data-test="fit-original-marker" :cx="point.x" :cy="point.y" r="4" fill="#bd3131"/><text x="45" y="265">{{ graph.xUnit }} / {{ graph.yUnit }}</text></svg>
    </figure>
    <h4>原始约束点（完整CSV中 isOriginal=1）</h4>
    <div class="ballistic-table-scroll"><table><thead><tr><th>UTC</th><th>{{ retimed ? "dt 累计 ms（可缺）" : "dt 相邻 ms" }}</th><th>ECI_X m</th><th>ECI_Y m</th><th>ECI_Z m</th><th>ECI_VX m/s</th><th>ECI_VY m/s</th><th>ECI_VZ m/s</th><th>isOriginal</th></tr></thead><tbody><tr v-for="(point, index) in originals" :key="index"><td>{{ point.utc }}</td><td>{{ point.fields.dt }}</td><td>{{ point.fields.ECI_X }}</td><td>{{ point.fields.ECI_Y }}</td><td>{{ point.fields.ECI_Z }}</td><td>{{ point.fields.ECI_VX }}</td><td>{{ point.fields.ECI_VY }}</td><td>{{ point.fields.ECI_VZ }}</td><td>{{ point.fields.isOriginal }}</td></tr></tbody></table></div>
  </section>
</template>
