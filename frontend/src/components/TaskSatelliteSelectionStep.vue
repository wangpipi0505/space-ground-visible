<script setup lang="ts">
/** 任务卫星筛选与分页选择；列表由工作台提供，查询、翻页和勾选通过事件回传。 */
import type { Satellite } from '../api/types'

defineOptions({ name: 'TaskSatelliteSelectionStep' })

const props = defineProps<{
  keyword: string
  loading: boolean
  satellites: Satellite[]
  selectedSatellites: Satellite[]
  total: number
  pageNumber: number
  pageSize: number
}>()

const emit = defineEmits<{
  'update:keyword': [keyword: string]
  query: []
  pageChange: [pageNumber: number]
  toggle: [satellite: Satellite, selected: boolean]
}>()

/** 判断当前卫星是否已加入本次任务卫星集合。 */
function isSelected(satelliteId: number): boolean {
  return props.selectedSatellites.some((satellite) => satellite.id === satelliteId)
}

/**
 * 将当前目录行加入或移出任务卫星集合。
 *
 * @param satellite 当前目录行
 * @param selected 勾选状态
 */
function handleToggle(satellite: Satellite, selected: boolean): void {
  emit('toggle', satellite, selected)
}
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header>
      <div class="compact-card-header"><h2>任务卫星</h2><el-tag type="info">{{ selectedSatellites.length }} 颗</el-tag></div>
    </template>
    <div class="task-filter-row">
      <el-input :model-value="keyword" clearable placeholder="输入卫星名称、OBJECT_ID、NORAD_CAT_ID 或 SCID" @update:model-value="emit('update:keyword', String($event))" @keyup.enter="emit('query')" />
      <el-button type="primary" :loading="loading" @click="emit('query')">筛选卫星</el-button>
    </div>
    <el-table :data="satellites" row-key="id" size="small" max-height="330" show-overflow-tooltip empty-text="未查询到可选卫星">
      <el-table-column label="任务选择" width="94"><template #default="scope"><el-checkbox :model-value="isSelected(scope.row.id)" @update:model-value="handleToggle(scope.row, Boolean($event))" /></template></el-table-column>
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="name" label="卫星名称" min-width="170" />
      <el-table-column prop="objectId" label="OBJECT_ID" min-width="135" />
      <el-table-column prop="noradCatId" label="NORAD_CAT_ID" min-width="130" />
      <el-table-column prop="scid" label="SCID" min-width="110" />
      <el-table-column prop="country" label="COUNTRY" min-width="100" />
      <el-table-column prop="weight" label="WEIGHT(kg)" min-width="120" />
      <el-table-column prop="rcsSize" label="RCS_SIZE" min-width="105" />
      <el-table-column prop="launchDate" label="LAUNCH" min-width="120" />
      <el-table-column prop="catalogComment" label="COMMENT" min-width="180" />
      <el-table-column prop="modelType" label="轨道模型" min-width="110" />
    </el-table>
    <el-pagination v-if="total > 0" class="table-pagination" background layout="total, prev, pager, next" :current-page="pageNumber" :page-size="pageSize" :total="total" @current-change="emit('pageChange', $event)" />
    <div class="selected-summary">
      <strong>已选任务卫星：{{ selectedSatellites.length }} 颗</strong>
      <el-tag v-for="satellite in selectedSatellites" :key="satellite.id" closable @close="handleToggle(satellite, false)">{{ satellite.name }}</el-tag>
    </div>
  </el-card>
</template>
