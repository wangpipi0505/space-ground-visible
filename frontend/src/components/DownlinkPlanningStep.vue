<script setup lang="ts">
/** 下传规划参数编辑；时间段和步长由父级维护，plan 事件提交独立后端计算。 */
import type { TimeRange } from '../composables/useVisibilityWorkbench'

defineOptions({ name: 'DownlinkPlanningStep' })

defineProps<{
  timeRange: TimeRange
  form: {
    coarseStepSec: number
    fineStepSec: number
    preGuardSec: number
    postGuardSec: number
    allowedGapSec: number
    satelliteIllumination: 'ANY' | 'SUNLIT' | 'SHADOW'
  }
  loading: boolean
}>()

const emit = defineEmits<{
  plan: []
  'update:timeRange': [timeRange: TimeRange]
}>()
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header><div class="compact-card-header"><h2>下传弧段规划</h2><el-button type="primary" :loading="loading" @click="emit('plan')">计算下传窗口</el-button></div></template>
    <el-form label-position="top" class="planning-form">
      <el-form-item label="规划时间段" class="time-range-item"><el-date-picker :model-value="timeRange" type="datetimerange" range-separator="至" start-placeholder="开始" end-placeholder="结束" @update:model-value="$emit('update:timeRange', $event as TimeRange)" /></el-form-item>
      <el-form-item label="粗选步长（秒）"><el-input-number v-model="form.coarseStepSec" :min="1" controls-position="right" /></el-form-item>
      <el-form-item label="精选步长（秒）"><el-input-number v-model="form.fineStepSec" :min="0.1" :max="form.coarseStepSec" :step="0.5" controls-position="right" /></el-form-item>
      <el-form-item label="卫星光照"><el-select v-model="form.satelliteIllumination"><el-option label="不限" value="ANY" /><el-option label="阳照区" value="SUNLIT" /><el-option label="阴影区" value="SHADOW" /></el-select></el-form-item>
      <el-form-item label="前保护（秒）"><el-input-number v-model="form.preGuardSec" :min="0" controls-position="right" /></el-form-item>
      <el-form-item label="后保护（秒）"><el-input-number v-model="form.postGuardSec" :min="0" controls-position="right" /></el-form-item>
      <el-form-item label="允许断链（秒）"><el-input-number v-model="form.allowedGapSec" :min="0" controls-position="right" /></el-form-item>
    </el-form>
  </el-card>
</template>
