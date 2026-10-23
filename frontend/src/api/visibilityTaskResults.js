import { deleteApi, getApi, putApi } from './client'
/**
 * 分页查询异步可见性规划任务的持久化结果记录，按提交时刻倒序，不含请求与结果 JSON 大字段。
 *
 * @param query 页码、页大小、状态筛选与关键字（匹配任务标识或备注）
 * @returns 任务结果记录分页结果
 */
export async function pageVisibilityTaskResults(query = {}) {
  const params = new URLSearchParams()
  params.set('pageNumber', String(query.pageNumber ?? 1))
  params.set('pageSize', String(query.pageSize ?? 20))
  if (query.status) {
    params.set('status', query.status)
  }
  if (query.keyword?.trim()) {
    params.set('keyword', query.keyword.trim())
  }
  return getApi(`/visibility/task-results?${params.toString()}`)
}
/**
 * 读取单条任务结果记录全字段，包含请求与结果 JSON 原文。
 *
 * @param id 任务结果记录主键的十进制文本，必须原样透传以避免雪花 ID 精度丢失
 * @returns 任务结果记录明细
 */
export async function getVisibilityTaskResultRecord(id) {
  return getApi(`/visibility/task-results/${encodeURIComponent(id)}`)
}
/**
 * 更新任务结果记录的用户备注；传入 null 表示清除备注。
 *
 * @param id 任务结果记录主键的十进制文本，必须原样透传以避免雪花 ID 精度丢失
 * @param remark 备注内容
 * @returns 更新后的任务结果记录明细
 */
export async function updateVisibilityTaskResultRemark(id, remark) {
  return putApi(`/visibility/task-results/${encodeURIComponent(id)}`, { remark })
}
/**
 * 删除任务结果记录。
 *
 * @param id 任务结果记录主键的十进制文本，必须原样透传以避免雪花 ID 精度丢失
 */
export async function deleteVisibilityTaskResultRecord(id) {
  return deleteApi(`/visibility/task-results/${encodeURIComponent(id)}`)
}
/**
 * 保存观测结果“确认选择”：勾选目标的明细与前端合并出的完整时段原文存入记录。
 * 重复提交覆盖上一份（以最后一次为准）；记录不存在时后端返回 404。
 *
 * @param id 任务结果记录主键的十进制文本，必须原样透传以避免雪花 ID 精度丢失
 * @param request 确认选择请求体
 * @returns 确认选择回执
 */
export async function putVisibilityTaskResultSelection(id, request) {
  return putApi(`/visibility/task-results/${encodeURIComponent(id)}/selection`, request)
}
