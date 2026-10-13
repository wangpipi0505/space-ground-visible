<script setup lang="ts">
/** 展示父级已加载的历史任务请求和结果明细；关闭抽屉时发出 close 事件，由父级维护可见状态。 */
import { computed } from 'vue'
import type { PlanningTaskStatus, VisibilityTaskResultDetail } from '../api/types'

defineOptions({ name: 'TaskResultDetailDrawer' })

const props = defineProps<{
  visible: boolean
  loading: boolean
  detail: VisibilityTaskResultDetail | null
  formatTime: (time?: string | null) => string
}>()

const emit = defineEmits<{
  close: []
}>()

/** 任务状态对应的中文标签与标签类型。 */
const statusMeta: Record<PlanningTaskStatus, { text: string; type: 'info' | 'warning' | 'success' | 'danger' }> = {
  PENDING: { text: '排队中', type: 'warning' },
  RUNNING: { text: '执行中', type: 'warning' },
  SUCCEEDED: { text: '已完成', type: 'success' },
  FAILED: { text: '执行失败', type: 'danger' },
  CANCELLED: { text: '已取消', type: 'info' },
}

/** 将 JSON 原文格式化为便于核对的缩进文本；无法解析时原样展示。 */
function formatJson(raw?: string | null): string {
  if (!raw) {
    return '（无内容）'
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

const requestJsonText = computed(() => formatJson(props.detail?.requestJson))
const resultJsonText = computed(() => formatJson(props.detail?.resultJson))
// 跳过清单独立持久化，不能只从受 500 条响应保护的 resultJson 中读取。
const skippedPairsJsonText = computed(() => formatJson(props.detail?.skippedPairsJson))
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="任务结果记录明细"
    size="52%"
    data-test="task-result-detail-drawer"
    @close="emit('close')"
  >
    <div v-loading="loading" class="task-result-detail">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="任务标识" :span="2">{{ detail.taskId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMeta[detail.status].type" effect="plain">{{ statusMeta[detail.status].text }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="进度">{{ detail.completedPairs ?? 0 }} / {{ detail.totalPairs ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ formatTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatTime(detail.completeTime) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.remark || '（无备注）' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.errorMessage" label="错误信息" :span="2">{{ detail.errorMessage }}</el-descriptions-item>
        </el-descriptions>
        <h3 class="task-result-json-title">请求参数</h3>
        <pre class="task-result-json" data-test="task-result-request-json">{{ requestJsonText }}</pre>
        <h3 class="task-result-json-title">规划结果</h3>
        <pre class="task-result-json" data-test="task-result-result-json">{{ resultJsonText }}</pre>
        <template v-if="detail.skippedPairsJson">
          <h3 class="task-result-json-title">完整跳过组合清单</h3>
          <pre class="task-result-json" data-test="task-result-skipped-pairs-json">{{ skippedPairsJsonText }}</pre>
        </template>
      </template>
      <el-empty v-else-if="!loading" description="未能加载记录明细" />
    </div>
  </el-drawer>
</template>
