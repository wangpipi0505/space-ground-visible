<script>
export default { name: 'SatellitePayloadPanel' }
</script>

<script setup>
/** 按卫星编辑长期载荷；加载与保存状态来自父级，对空与对地字段按原接口回显。 */
import { ref, watch } from 'vue'
import StandardPayloadEditor from './StandardPayloadEditor.vue'
import { validateStandardPayload } from '../utils/standardPayload'
const props = defineProps({
  satellites: { type: Array, required: true },
  configurations: { type: null, required: true },
  forms: { type: null, required: true },
  loadingSatelliteIds: { type: Array, required: true },
  savingSatelliteId: { type: null, required: true },
})
const emit = defineEmits(['save', 'selectType'])
/** 当前展开的卫星载荷配置项；未配置的卫星默认展开，便于直接补全参数。 */
const activeSatelliteIds = ref([])
watch(
  () => [props.satellites, props.configurations],
  () => {
    const validIds = new Set(props.satellites.map((satellite) => satellite.id))
    const kept = activeSatelliteIds.value.filter((id) => validIds.has(id))
    const unconfigured = props.satellites
      .filter((satellite) => !isConfigured(satellite.id))
      .map((satellite) => satellite.id)
    activeSatelliteIds.value = [...new Set([...kept, ...unconfigured])]
  },
  { immediate: true, deep: true },
)
/** 返回某颗任务卫星的当前载荷配置状态；尚未读取完成时视为未配置。 */
function configurationFor(satelliteId) {
  return props.configurations[satelliteId] ?? { activePayloadType: null, profiles: [] }
}
/** 活动类型代表当前参与规划的配置；只要存在任一 profile 即表示卫星已完成至少一类长期配置。 */
function isConfigured(satelliteId) {
  return configurationFor(satelliteId).profiles.length > 0
}
/** 判断当前卫星的持久化载荷配置是否仍在读取。 */
function isLoading(satelliteId) {
  return props.loadingSatelliteIds.includes(satelliteId)
}
/**
 * 旧版本的对地记录只有天底锥角，无法映射为统一姿态窗口。它仍可查看但不能静默沿用，
 * 因此前端在用户切换到该配置时明确提示重新核对并保存完整参数。
 */
function isLegacyGroundProfile(satelliteId) {
  const form = props.forms[satelliteId]
  return (
    form?.payloadType === 'GROUND' &&
    configurationFor(satelliteId).profiles.some(
      (profile) => profile.payloadType === 'GROUND' && profile.legacyGroundProfile === true,
    )
  )
}
</script>

<template>
  <el-card shadow="never" class="planning-card payload-panel">
    <template #header><div class="compact-card-header"><h2>卫星载荷</h2><el-tag type="info">长期配置</el-tag></div></template>
    <el-empty v-if="satellites.length === 0" :image-size="42" description="选择任务卫星后配置载荷" />
    <el-collapse v-else v-model="activeSatelliteIds" class="payload-config-list">
      <el-collapse-item v-for="satellite in satellites" :key="satellite.id" :name="satellite.id" class="payload-config-item">
        <template #title>
          <div class="payload-config-title">
            <strong>{{ satellite.name }}</strong>
            <el-tag v-if="isLoading(satellite.id)" type="info" effect="plain">读取中</el-tag>
            <el-tag v-else :type="isConfigured(satellite.id) ? 'success' : 'warning'" effect="plain">
              {{ isConfigured(satellite.id) ? `已配置，活动：${configurationFor(satellite.id).activePayloadType === 'GROUND' ? '对地' : '对空'}` : '未配置' }}
            </el-tag>
          </div>
        </template>
        <el-alert
          v-if="isLegacyGroundProfile(satellite.id)"
          title="该对地载荷为历史天底锥角格式，请按完整统一参数核对后重新保存。"
          type="warning"
          :closable="false"
          show-icon
        />
        <StandardPayloadEditor
          :model-value="forms[satellite.id]"
          :disabled="isLoading(satellite.id) || savingSatelliteId === satellite.id"
          @update:modelValue="forms[satellite.id] = $event"
          @selectType="emit('selectType', satellite.id, $event)"
        />
        <div class="payload-action"><span>对空与对地配置独立保存；保存的类型会成为后续规划的活动载荷</span><el-button :data-test="`save-payload-${satellite.id}`" type="primary" size="small" :loading="savingSatelliteId === satellite.id" :disabled="isLoading(satellite.id) || validateStandardPayload(forms[satellite.id]).length > 0" @click="emit('save', satellite.id)">保存并启用</el-button></div>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>
