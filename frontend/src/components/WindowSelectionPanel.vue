<script setup lang="ts">
/** 展示下传窗口与自选范围；选择和校验事件确定后续观测规划的时间边界。 */
import type { TimeRange } from '../composables/useVisibilityWorkbench'

defineOptions({ name: 'WindowSelectionPanel' })

defineProps<{
  rows: Array<{ key: string; sourceLabel: string; sourceCount: number; startTime: string; endTime: string; durationSec: number }>
  singleWindows?: Array<{ key: string; satelliteName: string; stationName: string; startTime: string; endTime: string; durationSec: number }>
  skippedLinks?: Array<{ satelliteName: string; stationName: string; stage: string; reason: string }>
  totalSkippedLinks?: number
  customWindowRange: TimeRange
  loading: boolean
  selectedWindowLabel: string
  selectedWindowStartTime: string
  selectedWindowEndTime: string
  formatTime: (time: string) => string
  formatDuration: (durationSec: number) => string
}>()

const emit = defineEmits<{
  selectPlanned: [startTime: string, endTime: string, label: string]
  validateCustom: []
  'update:customWindowRange': [timeRange: TimeRange]
}>()
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header><div class="compact-card-header"><h2>下传窗口</h2><el-tag v-if="selectedWindowLabel" type="success">已选择</el-tag></div></template>
    <div v-if="selectedWindowLabel" data-test="selected-observation-window" class="planning-context">
      <span>观测计算范围：{{ selectedWindowLabel }}</span>
      <span>{{ formatTime(selectedWindowStartTime) }} 至 {{ formatTime(selectedWindowEndTime) }}</span>
    </div>
    <el-table :data="rows" size="small" max-height="230" empty-text="尚未规划下传窗口">
      <el-table-column prop="sourceLabel" label="连续覆盖链路" min-width="190" /><el-table-column prop="sourceCount" label="链路数" width="70" />
      <el-table-column label="起止时间" min-width="190"><template #default="scope">{{ formatTime(scope.row.startTime) }}<br>{{ formatTime(scope.row.endTime) }}</template></el-table-column>
      <el-table-column label="时长" width="78"><template #default="scope">{{ formatDuration(scope.row.durationSec) }}</template></el-table-column>
      <el-table-column label="使用" width="62"><template #default="scope"><el-button text type="primary" @click="emit('selectPlanned', scope.row.startTime, scope.row.endTime, `连续下传覆盖：${scope.row.sourceLabel}`)">选择</el-button></template></el-table-column>
    </el-table>
    <el-table v-if="singleWindows?.length" :data="singleWindows" data-test="single-window-table" size="small" max-height="200" class="single-window-table" empty-text="暂无单星窗口">
      <el-table-column prop="satelliteName" label="卫星" min-width="130" />
      <el-table-column prop="stationName" label="地面站" min-width="110" />
      <el-table-column label="起止时间" min-width="190"><template #default="scope">{{ formatTime(scope.row.startTime) }}<br>{{ formatTime(scope.row.endTime) }}</template></el-table-column>
      <el-table-column label="时长" width="78"><template #default="scope">{{ formatDuration(scope.row.durationSec) }}</template></el-table-column>
      <el-table-column label="使用" width="62"><template #default="scope"><el-button text type="primary" @click="emit('selectPlanned', scope.row.startTime, scope.row.endTime, `单星下传：${scope.row.satelliteName}—${scope.row.stationName}`)">选择</el-button></template></el-table-column>
    </el-table>
    <el-alert v-if="(totalSkippedLinks ?? 0) > 0" data-test="skipped-links-summary" type="warning" :closable="false" show-icon>
      <template #title>下传规划跳过 {{ totalSkippedLinks }} 条站星链路；未跳过的链路已正常完成。</template>
      <template #default>请核对链路轨道数据或地面站配置后重试。</template>
    </el-alert>
    <el-table v-if="skippedLinks?.length" :data="skippedLinks" data-test="skipped-links-table" size="small" max-height="180" class="skipped-result-table">
      <el-table-column prop="satelliteName" label="卫星" min-width="120" />
      <el-table-column prop="stationName" label="地面站" min-width="120" />
      <el-table-column prop="stage" label="阶段" width="90" />
      <el-table-column prop="reason" label="跳过原因" min-width="200" show-overflow-tooltip />
    </el-table>
    <div class="custom-window-editor">
      <el-date-picker :model-value="customWindowRange" type="datetimerange" range-separator="至" start-placeholder="开始" end-placeholder="结束" @update:model-value="$emit('update:customWindowRange', $event as TimeRange)" />
      <el-button :loading="loading" @click="emit('validateCustom')">校验自选</el-button>
    </div>
  </el-card>
</template>
