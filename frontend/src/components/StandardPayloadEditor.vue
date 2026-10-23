<script>
export default { name: 'StandardPayloadEditor' }
</script>

<script setup>
/**
 * 空间长期 profile 与弹道多载荷共用的十字段编辑器。父级持有数据与配置身份；
 * 本组件只派发十字段更新/类型选择，不保存 HTTP、不修改已保存响应。
 * 类型选择交由父级回填对应 profile 或当前弹道载荷，避免复用另一份身份/长期配置。
 */
import { computed } from 'vue'
import { standardPayloadFields, validateStandardPayload } from '../utils/standardPayload'
const props = defineProps({
  modelValue: { type: null, required: true },
  disabled: { type: Boolean },
  compact: { type: Boolean },
})
const emit = defineEmits(['update:modelValue', 'selectType'])
/** 原空间字段词汇和单位保持一致；不设控件 min/max，非法跨字段原值由完整校验明确展示。 */
const fields = [
  { key: 'minDistanceKm', label: '距离下限（公里）' },
  { key: 'maxDistanceKm', label: '距离上限（公里）' },
  { key: 'azimuthMinDeg', label: '方位角下限（度，双端留空表示不限）' },
  { key: 'azimuthMaxDeg', label: '方位角上限（度，双端留空表示不限）' },
  { key: 'elevationMinDeg', label: '俯仰角下限（度）' },
  { key: 'elevationMaxDeg', label: '俯仰角上限（度）' },
  { key: 'angleRateDegPerSec', label: '跟踪角速度（度/秒）', step: 0.01 },
  { key: 'sunAvoidHalfAngleDeg', label: '太阳回避半角（度）' },
  { key: 'startupAngleRateDegPerSec', label: '启动角速度（度/秒，留空或 -1 表示不启用)', step: 0.01 },
]
const errors = computed(() => validateStandardPayload(props.modelValue))
/** ElementUI 清空返回 undefined/null；只有清空映射 null，NaN 等原值保留以阻止提交。 */
function updateNumber(field, value) {
  emit('update:modelValue', { ...standardPayloadFields(props.modelValue), [field]: value == null ? null : value })
}
/** 下拉选项仅接受两种标准类型，避免通过断言把任意字符串写进配置。 */
function selectType(value) {
  if (value === 'SPACE' || value === 'GROUND') emit('selectType', value)
}
</script>

<template>
  <div class="standard-payload-editor" :class="{ 'payload-compact': compact }">
    <el-form label-position="top" class="payload-form" :disabled="disabled">
      <el-form-item label="载荷类型" data-field="payloadType">
        <el-select :value="modelValue.payloadType" @input="selectType">
          <el-option label="对空载荷" value="SPACE" /><el-option label="对地载荷" value="GROUND" />
        </el-select>
      </el-form-item>
      <el-form-item v-for="field in fields" :key="field.key" :data-field="field.key"
        :label="compact ? field.label.replace(/（.*/, '') + (field.key.includes('Distance') ? ' (km)' : field.key.includes('Rate') ? ' (°/s)' : ' (°)') : field.key === 'angleRateDegPerSec' && modelValue.payloadType === 'SPACE' ? '跟踪角速度（度/秒，0 或 -1 表示不限制）' : field.label">
        <!-- Element UI 2挂载时也会派发input；使用change接收步进操作，避免初始化反写业务数据。
             原生input即时同步未失焦输入；空白仍传null，null仅在控件展示边界映射undefined。
             不夹断协议允许的有限double；非法跨字段组合和非有限数仍由原校验阻止提交。 -->
        <el-input-number :key="`${field.key}-${Boolean(disabled)}`" :value="modelValue[field.key] == null ? undefined : modelValue[field.key]" :step="field.step ?? 1" :min="-Infinity" :max="Infinity" controls-position="right" :aria-disabled="Boolean(disabled)"
          @change="!disabled && updateNumber(field.key, $event)" @input.native="!disabled && updateNumber(field.key, $event.target.value === '' ? null : Number($event.target.value))" />
      </el-form-item>
    </el-form>
    <details v-if="compact" class="ballistic-help"><summary>单位与约束说明</summary><p>距离上限 0 不限；方位/俯仰双端留空不限。LVLH 方位 0° 沿速度方向，俯仰正向背离地球；SPACE 俯仰下限 −90°，GROUND 下限 −91°。</p><p>对空跟踪角速度 0/−1 不限；对地 0 为真实零上限，不支持 −1。太阳回避 0 关闭；启动角速度留空/−1 禁用，其余非负值仅为元数据。</p></details>
    <p v-if="!compact" class="payload-explanation">距离上限 0 不限；姿态双端留空不限。LVLH 方位 0° 沿速度方向，俯仰正向背离地球；这是矩形姿态窗口。SPACE 俯仰下限 -90°，GROUND 下限 -91°。</p>
    <p v-if="!compact" class="payload-explanation">对空跟踪角速度 0/-1 不限；对地 0 为真实零上限，不支持 -1。太阳回避半角 0 关闭；启动角速度留空/-1 禁用，其余非负值仅为元数据，当前不参与计算。</p>
    <el-alert v-if="errors.length" type="error" :closable="false" title="载荷参数需要修正" show-icon>
      <ul class="payload-validation-errors"><li v-for="error in errors" :key="error">{{ error }}</li></ul>
    </el-alert>
  </div>
</template>
