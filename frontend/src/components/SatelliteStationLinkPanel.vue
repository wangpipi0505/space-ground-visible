<script setup lang="ts">
/** 维护已选卫星与地面站的下传链路；仅回传关联变更，不在组件内计算窗口。 */
import type { Satellite, Station } from '../api/types'
import type { StationConstraintForm } from '../composables/useVisibilityWorkbench'

defineOptions({ name: 'SatelliteStationLinkPanel' })

const props = defineProps<{
  satellites: Satellite[]
  stations: Station[]
  stationConstraints: StationConstraintForm[]
  links: Array<{ satelliteId: number; stationId: string }>
}>()

const emit = defineEmits<{
  toggleLink: [satelliteId: number, stationId: string, selected: boolean]
}>()

/** 判断当前卫星与地面站是否已作为本次下传链路被互选。 */
function isLinked(satelliteId: number, stationId: string): boolean {
  return props.links.some((link) => link.satelliteId === satelliteId && link.stationId === stationId)
}

/** 将矩阵复选状态转发给工作台状态管理器。 */
function toggleLink(satelliteId: number, stationId: string, selected: boolean): void {
  emit('toggleLink', satelliteId, stationId, selected)
}

/** 仅展示已经被勾选为本次任务约束的地面站。 */
function isStationSelected(stationId: string): boolean {
  return props.stationConstraints.some((constraint) => constraint.stationId === stationId)
}
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header><div class="compact-card-header"><h2>站星互选</h2><el-tag>{{ links.length }} 条链路</el-tag></div></template>
    <el-empty v-if="satellites.length === 0 || stationConstraints.length === 0" :image-size="54" description="先在左侧选择卫星和地面站" />
    <div v-else class="link-matrix" :style="{ '--satellite-count': String(satellites.length) }" role="table" aria-label="卫星地面站下传互选矩阵">
      <div class="link-matrix-row link-matrix-heading" role="row">
        <span role="columnheader">地面站</span>
        <span v-for="satellite in satellites" :key="satellite.id" role="columnheader" :title="satellite.name">{{ satellite.name }}</span>
      </div>
      <div v-for="station in stations.filter((item) => isStationSelected(item.id))" :key="station.id" class="link-matrix-row" role="row">
        <span role="rowheader"><strong>{{ station.name }}</strong><small>{{ station.id }}</small></span>
        <span v-for="satellite in satellites" :key="satellite.id" role="cell">
          <el-checkbox :model-value="isLinked(satellite.id, station.id)" :aria-label="`${satellite.name} 对 ${station.name} 下传`" @update:model-value="toggleLink(satellite.id, station.id, Boolean($event))" />
        </span>
      </div>
    </div>
  </el-card>
</template>
