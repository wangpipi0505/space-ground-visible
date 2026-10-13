import { deleteApi, getApi, postApi } from './client'
import type {
  VisibilityPlanRequest,
  VisibilityPlanResponse,
  VisibilityPlanningTaskCreateResponse,
  VisibilityPlanningTaskResponse,
} from './types'

/**
 * 提交异步可见性规划任务，请求体与同步规划完全一致。
 *
 * @param payload 与同步可见性规划相同的请求参数
 * @returns 后端分配的任务标识
 */
export async function createVisibilityTask(payload: VisibilityPlanRequest): Promise<VisibilityPlanningTaskCreateResponse> {
  return postApi<VisibilityPlanningTaskCreateResponse>('/visibility/tasks', payload)
}

/**
 * 查询异步可见性规划任务的当前状态与进度。
 *
 * @param taskId 提交任务时返回的任务标识
 * @returns 任务状态与已完成配对进度
 */
export async function getVisibilityTask(taskId: string): Promise<VisibilityPlanningTaskResponse> {
  return getApi<VisibilityPlanningTaskResponse>(`/visibility/tasks/${encodeURIComponent(taskId)}`)
}

/**
 * 获取已成功完成的异步可见性规划任务结果，结构与同步规划响应一致。
 *
 * @param taskId 提交任务时返回的任务标识
 * @returns 与同步规划相同的可见弧段结果
 */
export async function getVisibilityTaskResult(taskId: string): Promise<VisibilityPlanResponse> {
  return getApi<VisibilityPlanResponse>(`/visibility/tasks/${encodeURIComponent(taskId)}/result`)
}

/**
 * 取消仍在排队或执行中的异步可见性规划任务。
 *
 * @param taskId 提交任务时返回的任务标识
 */
export async function cancelVisibilityTask(taskId: string): Promise<void> {
  return deleteApi<void>(`/visibility/tasks/${encodeURIComponent(taskId)}`)
}
