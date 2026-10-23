<script>
export default { name: 'TaskResultRecordsPanel' }
</script>

<script setup>
/** 查询后台任务历史记录；分页、状态、备注与明细均通过服务端结果接口维护。 */
import { onMounted } from 'vue'
import { useVisibilityTaskRecords } from '../composables/useVisibilityTaskRecords'
import TaskResultDetailDrawer from './TaskResultDetailDrawer.vue'
const props = defineProps({ formatTime: { type: Function, required: true } })
const {
  closeDetail,
  detail,
  detailLoading,
  detailVisible,
  editRemark,
  filters,
  loadRecords,
  loadedOnce,
  loading,
  openDetail,
  pageNumber,
  pageSize,
  queryRecords,
  records,
  removeRecord,
  savingRemarkId,
  total,
} = useVisibilityTaskRecords()
onMounted(() => {
  void loadRecords(1)
})
/** 任务状态对应的中文标签与标签类型。 */
const statusMeta = {
  PENDING: { text: '排队中', type: 'warning' },
  RUNNING: { text: '执行中', type: 'warning' },
  SUCCEEDED: { text: '已完成', type: 'success' },
  FAILED: { text: '执行失败', type: 'danger' },
  CANCELLED: { text: '已取消', type: 'info' },
}
/** 供筛选下拉框使用的任务状态选项。 */
const statusOptions = [
  { value: 'PENDING', label: '排队中' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCEEDED', label: '已完成' },
  { value: 'FAILED', label: '执行失败' },
  { value: 'CANCELLED', label: '已取消' },
]
/** 抽屉内的时间字段允许为空，空值显示占位符。 */
function formatNullableTime(time) {
  return time ? props.formatTime(time) : '—'
}
</script>

<template>
  <el-card shadow="never" class="planning-card task-result-records" data-test="task-result-records">
    <template #header>
      <div class="compact-card-header">
        <h2>任务结果记录</h2>
        <el-tag type="info">后台规划任务的历史结果</el-tag>
      </div>
    </template>
    <div class="task-result-filter-row">
      <el-select v-model="filters.status" clearable placeholder="全部状态" class="task-result-status-filter" data-test="task-result-status-filter">
        <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-input
        v-model="filters.keyword"
        clearable
        placeholder="按任务标识或备注搜索"
        data-test="task-result-keyword"
        @keyup.enter.native="queryRecords"
      />
      <el-button type="primary" :loading="loading" data-test="query-task-results" @click="queryRecords">查询</el-button>
      <el-button :loading="loading" @click="loadRecords()">刷新</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="records"
      row-key="id"
      size="small"
      show-overflow-tooltip
      :empty-text="loadedOnce ? '没有符合条件的任务结果记录' : '正在加载任务结果记录'"
    >
      <el-table-column prop="taskId" label="任务标识" min-width="220" />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="statusMeta[(scope.row).status].type" effect="plain">
            {{ statusMeta[(scope.row).status].text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="110">
        <template #default="scope">{{ scope.row.completedPairs ?? 0 }} / {{ scope.row.totalPairs ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="提交时间" width="180">
        <template #default="scope">{{ formatNullableTime(scope.row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="完成时间" width="180">
        <template #default="scope">{{ formatNullableTime(scope.row.completeTime) }}</template>
      </el-table-column>
      <el-table-column prop="errorMessage" label="错误信息" min-width="140" />
      <el-table-column prop="remark" label="备注" min-width="140" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="scope">
          <el-button type="text" data-text-type="primary" data-test="open-task-result-detail" @click="openDetail(scope.row.id)">明细</el-button>
          <el-button type="text" data-text-type="primary" :loading="savingRemarkId === scope.row.id" data-test="edit-task-result-remark" @click="editRemark(scope.row)">备注</el-button>
          <el-button type="text" data-text-type="danger" data-test="delete-task-result" @click="removeRecord(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="total > 0"
      class="table-pagination"
      background
      layout="total, prev, pager, next"
      :current-page="pageNumber"
      :page-size="pageSize"
      :total="total"
      @current-change="loadRecords"
    />
    <TaskResultDetailDrawer
      :visible="detailVisible"
      :loading="detailLoading"
      :detail="detail"
      :format-time="formatNullableTime"
      @close="closeDetail"
    />
  </el-card>
</template>
