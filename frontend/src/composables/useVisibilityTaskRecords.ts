import { reactive, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiRequestError } from '../api/client'
import {
  deleteVisibilityTaskResultRecord,
  getVisibilityTaskResultRecord,
  pageVisibilityTaskResults,
  updateVisibilityTaskResultRemark,
} from '../api/visibilityTaskResults'
import type {
  PlanningTaskStatus,
  VisibilityTaskResultDetail,
  VisibilityTaskResultSummary,
} from '../api/types'

/** 任务结果记录列表的默认页大小。 */
const DEFAULT_PAGE_SIZE = 20

/** 从统一接口异常或普通异常中提取可直接展示的错误信息。 */
function failureMessage(error: unknown): string {
  if (error instanceof ApiRequestError) {
    return error.message
  }
  return error instanceof Error ? error.message : '未知异常'
}

/**
 * 管理任务结果记录视图的状态和操作：分页查询、状态与关键字筛选、明细查看、备注维护与删除。
 *
 * @returns 组件使用的响应式数据与接口调用方法
 */
export function useVisibilityTaskRecords() {
  const records = shallowRef<VisibilityTaskResultSummary[]>([])
  const total = ref(0)
  const pageNumber = ref(1)
  const pageSize = ref(DEFAULT_PAGE_SIZE)
  const loading = ref(false)
  const loadedOnce = ref(false)
  const filters = reactive<{ status: PlanningTaskStatus | ''; keyword: string }>({ status: '', keyword: '' })

  const detail = shallowRef<VisibilityTaskResultDetail | null>(null)
  const detailVisible = ref(false)
  const detailLoading = ref(false)
  // 与接口约定保持为字符串，避免超出安全整数范围的雪花 ID 在比较或请求时被舍入。
  const savingRemarkId = ref<string | null>(null)

  /** 按当前筛选条件加载指定页的任务结果记录。 */
  async function loadRecords(targetPage = pageNumber.value): Promise<void> {
    loading.value = true
    try {
      const response = await pageVisibilityTaskResults({
        pageNumber: targetPage,
        pageSize: pageSize.value,
        status: filters.status || undefined,
        keyword: filters.keyword,
      })
      records.value = response.records
      total.value = response.total
      pageNumber.value = response.pageNumber
      pageSize.value = response.pageSize
      loadedOnce.value = true
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      loading.value = false
    }
  }

  /** 从第一页执行筛选查询。 */
  async function queryRecords(): Promise<void> {
    await loadRecords(1)
  }

  /** 打开任务结果记录明细抽屉，并加载请求与结果 JSON 原文。 */
  async function openDetail(id: string): Promise<void> {
    detailVisible.value = true
    detailLoading.value = true
    detail.value = null
    try {
      detail.value = await getVisibilityTaskResultRecord(id)
    } catch (error) {
      ElMessage.error(failureMessage(error))
      detailVisible.value = false
    } finally {
      detailLoading.value = false
    }
  }

  /** 关闭明细抽屉并丢弃已加载的明细内容。 */
  function closeDetail(): void {
    detailVisible.value = false
    detail.value = null
  }

  /** 弹出备注编辑框，保存后同步列表中的对应行。 */
  async function editRemark(record: VisibilityTaskResultSummary): Promise<void> {
    let inputValue: string
    try {
      const result = await ElMessageBox.prompt('填写本次规划任务的备注，留空表示清除备注。', '编辑备注', {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValue: record.remark ?? '',
        inputPlaceholder: '备注内容',
      })
      inputValue = result.value ?? ''
    } catch {
      return
    }
    savingRemarkId.value = record.id
    try {
      const remark = inputValue.trim()
      const updated = await updateVisibilityTaskResultRemark(record.id, remark || null)
      records.value = records.value.map((item) => (item.id === record.id ? { ...item, remark: updated.remark } : item))
      if (detail.value?.id === record.id) {
        detail.value = { ...detail.value, remark: updated.remark }
      }
      ElMessage.success('备注已保存。')
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      savingRemarkId.value = null
    }
  }

  /** 确认后删除任务结果记录，并刷新当前页。 */
  async function removeRecord(record: VisibilityTaskResultSummary): Promise<void> {
    try {
      await ElMessageBox.confirm(`确定删除任务 ${record.taskId} 的结果记录吗？`, '删除确认', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      await deleteVisibilityTaskResultRecord(record.id)
      if (detail.value?.id === record.id) {
        closeDetail()
      }
      ElMessage.success('记录已删除。')
      await loadRecords()
    } catch (error) {
      ElMessage.error(failureMessage(error))
    }
  }

  return {
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
  }
}
