<script setup lang="ts">
/** 编辑同一目标的联合观测卫星及共同可见时长，以事件交给工作台验证和保存。 */
import type { Satellite } from '../api/types'
import type { ObservationConstraintForm } from '../composables/useVisibilityWorkbench'

defineOptions({ name: 'ObservationConstraintPanel' })

const props = defineProps<{
  satellites: Satellite[]
  draft: ObservationConstraintForm
  constraints: ObservationConstraintForm[]
}>()

const emit = defineEmits<{
  add: []
  remove: [index: number]
}>()

/** 将卫星标识转换为便于核对的名称。 */
function satelliteName(satelliteId: number): string {
  return props.satellites.find((satellite) => satellite.id === satelliteId)?.name ?? String(satelliteId)
}
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header><div class="compact-card-header"><h2>目标观测组合</h2><el-tag type="info">同一目标</el-tag></div></template>
    <el-alert v-if="satellites.length === 0" title="先在左侧选择本次任务卫星" type="warning" :closable="false" show-icon />
    <template v-else>
      <div class="constraint-guide">
        <span class="constraint-guide-step">1</span>
        <span>选择需要<strong>同时观测同一目标</strong>的我方卫星</span>
      </div>
      <div class="observation-editor">
        <el-checkbox-group v-model="draft.satelliteIds" class="task-satellite-checks">
          <el-checkbox-button v-for="satellite in satellites" :key="satellite.id" :value="satellite.id">{{ satellite.name }}</el-checkbox-button>
        </el-checkbox-group>
        <div class="constraint-duration-editor"><span class="constraint-guide-step">2</span><span>共同可见时长（秒）</span><el-input-number v-model="draft.minDurationSec" :min="0" controls-position="right" /></div>
        <el-button type="primary" @click="emit('add')">添加组合约束</el-button>
      </div>
      <el-table :data="constraints" size="small" max-height="210" empty-text="未设置组合时，默认分别输出每颗已选卫星的观测结果">
        <el-table-column label="卫星组合" min-width="180"><template #default="scope">{{ scope.row.satelliteIds.map(satelliteName).join(' + ') }}</template></el-table-column>
        <el-table-column label="共同可见时长（秒）" width="170"><template #default="scope"><el-input-number data-test="constraint-duration" v-model="scope.row.minDurationSec" :min="0" controls-position="right" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="scope"><el-button text type="danger" @click="emit('remove', scope.$index)">删除</el-button></template></el-table-column>
      </el-table>
    </template>
  </el-card>
</template>
