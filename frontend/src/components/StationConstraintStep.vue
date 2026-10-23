<script>
export default { name: 'StationConstraintStep' }
</script>

<script setup>
/** 地面站选择及仰角约束编辑；选择标识通过 stationsChange 交由工作台维护。 */
import { computed } from 'vue'
const props = defineProps({ stations: { type: Array, required: true }, constraints: { type: Array, required: true } })
const emit = defineEmits(['stationsChange'])
const selectedStationIds = computed({
  get: () => props.constraints.map((constraint) => constraint.stationId),
  set: (stationIds) => emit('stationsChange', stationIds),
})
/** 将地面站经纬高整理为核对用的紧凑文本。 */
function stationLocation(stationId) {
  const station = props.stations.find((item) => item.id === stationId)
  return `${station?.longitude ?? '-'}，${station?.latitude ?? '-'}，${station?.height ?? '-'}`
}
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header><div class="compact-card-header"><h2>地面站</h2><el-tag>{{ constraints.length }} 个站点</el-tag></div></template>
    <el-checkbox-group v-model="selectedStationIds" class="station-checks">
      <el-checkbox-button v-for="station in stations" :key="station.id" :label="station.id">{{ station.name }}（{{ station.id }}）</el-checkbox-button>
    </el-checkbox-group>
    <div v-if="constraints.length" class="station-constraints compact-station-constraints">
      <el-card v-for="constraint in constraints" :key="constraint.stationId" class="constraint-card" shadow="never">
        <div class="constraint-title"><strong>{{ stations.find((station) => station.id === constraint.stationId)?.name || constraint.stationId }}</strong><span>{{ stationLocation(constraint.stationId) }}</span></div>
        <el-form label-position="top" class="constraint-form">
          <el-form-item label="最小仰角（度）"><el-input-number v-model="constraint.minElevationDeg" :min="0" :max="90" controls-position="right" /></el-form-item>
          <el-form-item label="最大仰角（度）"><el-input-number v-model="constraint.maxElevationDeg" :min="0" :max="91" controls-position="right" /></el-form-item>
        </el-form>
      </el-card>
    </div>
  </el-card>
</template>
