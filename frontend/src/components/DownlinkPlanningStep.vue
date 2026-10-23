<script>
export default { name: 'DownlinkPlanningStep' }
</script>

<script setup>
defineProps({
  timeRange: { type: Array, required: true },
  form: { type: null, required: true },
  loading: { type: Boolean, required: true },
})
const emit = defineEmits(['plan', 'update:timeRange'])
</script>

<template>
  <el-card shadow="never" class="planning-card">
    <template #header><div class="compact-card-header"><h2>下传弧段规划</h2><el-button type="primary" :loading="loading" @click="emit('plan')">计算下传窗口</el-button></div></template>
    <el-form label-position="top" class="planning-form">
      <el-form-item label="规划时间段" class="time-range-item"><el-date-picker :value="timeRange" type="datetimerange" range-separator="至" start-placeholder="开始" end-placeholder="结束" @input="$emit('update:timeRange', $event)" /></el-form-item>
      <el-form-item label="粗选步长（秒）"><el-input-number v-model="form.coarseStepSec" :min="1" controls-position="right" /></el-form-item>
      <el-form-item label="精选步长（秒）"><el-input-number v-model="form.fineStepSec" :min="0.1" :max="form.coarseStepSec" :step="0.5" controls-position="right" /></el-form-item>
      <el-form-item label="卫星光照"><el-select v-model="form.satelliteIllumination"><el-option label="不限" value="ANY" /><el-option label="阳照区" value="SUNLIT" /><el-option label="阴影区" value="SHADOW" /></el-select></el-form-item>
      <el-form-item label="前保护（秒）"><el-input-number v-model="form.preGuardSec" :min="0" controls-position="right" /></el-form-item>
      <el-form-item label="后保护（秒）"><el-input-number v-model="form.postGuardSec" :min="0" controls-position="right" /></el-form-item>
      <el-form-item label="允许断链（秒）"><el-input-number v-model="form.allowedGapSec" :min="0" controls-position="right" /></el-form-item>
    </el-form>
  </el-card>
</template>
