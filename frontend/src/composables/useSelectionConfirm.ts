/**
 * 目标观测“确认选择”状态与提交逻辑。
 *
 * 职责:管理按目标勾选的状态、计算勾选目标的合并完整时段(并集)、
 * 并把勾选明细提交到任务结果记录的确认选择接口。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  formatIsoToUtcText,
  mergeTimeSegments,
  type MergedTimeSegment,
} from '../utils/winToolSummary'
import type {
  SelectionConfirmRequest,
  SelectionMergedTimeRange,
  SelectionTarget,
} from '../api/types'

export interface SelectionConfirmDeps {
  /** 当前后台规划任务标识;用于在任务结果记录里定位确认落库的记录。 */
  taskId: () => string | null | undefined
  /** 分页查询任务结果记录(按 keyword 匹配任务标识定位记录)。 */
  findRecordPage: (taskId: string) => Promise<{ records: Array<{ id: string; taskId: string }> }>
  /** 保存确认选择(含合并完整时段与逐目标明细)。 */
  putSelection: (recordId: string, request: SelectionConfirmRequest) => Promise<{ selectionCount: number; mergedSegments: number }>
  /** 规划时间段(即当前观测计算窗口)。 */
  planningWindow: () => { startTime: string; endTime: string }
  /** 收集全部弧段(带目标 ID),供确认选择计算勾选目标的合并完整时段。 */
  collectArcs: () => Array<{ targetId: string; startTime: string; endTime: string; durationSec: number }>
  /** 构建逐目标提交明细(含 NORAD/重量/TLE 与逐星统计)。 */
  buildSelections: (targetIds: string[]) => SelectionTarget[]
}

export function useSelectionConfirm(deps: SelectionConfirmDeps) {
  const selectedTargetIds = ref<string[]>([])
  const confirmingSelection = ref(false)

  function toggleTargetSelection(targetId: string, checked: boolean) {
    const next = new Set(selectedTargetIds.value)
    if (checked) {
      next.add(targetId)
    } else {
      next.delete(targetId)
    }
    selectedTargetIds.value = [...next]
  }

  function isTargetSelected(targetId: string): boolean {
    return selectedTargetIds.value.includes(targetId)
  }

  /** 勾选目标的全部弧段并集:即“确认选择”要提交的合并完整时段。 */
  const selectedMergedTimeRange = computed<SelectionMergedTimeRange>(() => {
    const selected = new Set(selectedTargetIds.value)
    const raw: MergedTimeSegment[] = []
    for (const arc of deps.collectArcs()) {
      if (!selected.has(arc.targetId)) continue
      raw.push({ startTime: arc.startTime, endTime: arc.endTime, durationSec: arc.durationSec })
    }
    const merged = mergeTimeSegments(raw)
    return {
      segments: merged,
      totalDurationSec: merged.reduce((total, segment) => total + segment.durationSec, 0),
    }
  })

  const selectedMergedTimeRangeText = computed(() => {
    const segments = selectedMergedTimeRange.value.segments
    if (!segments.length) {
      return '—'
    }
    return segments
      .map((segment) => `${formatIsoToUtcText(segment.startTime)} ~ ${formatIsoToUtcText(segment.endTime)}`)
      .join('；') + `（累计 ${selectedMergedTimeRange.value.totalDurationSec.toFixed(3)} 秒）`
  })

  async function confirmSelection(): Promise<boolean> {
    if (!selectedTargetIds.value.length) {
      ElMessage.warning('请先在列表中勾选目标')
      return false
    }
    const taskId = deps.taskId()
    if (!taskId) {
      ElMessage.error('缺少本次规划任务标识,无法确认选择')
      return false
    }
    confirmingSelection.value = true
    try {
      const page = await deps.findRecordPage(taskId)
      const record = (page.records ?? []).find((item) => item.taskId === taskId)
      if (!record) {
        ElMessage.error('未找到本次规划的结果记录,无法确认选择')
        return false
      }
      const request: SelectionConfirmRequest = {
        planningWindow: deps.planningWindow(),
        mergedTimeRange: {
          segments: selectedMergedTimeRange.value.segments,
          totalDurationSec: selectedMergedTimeRange.value.totalDurationSec,
        },
        selections: deps.buildSelections(selectedTargetIds.value),
      }
      const receipt = await deps.putSelection(record.id, request)
      ElMessage.success(`确认选择已保存:${receipt.selectionCount} 个目标 / ${receipt.mergedSegments} 段`)
      return true
    } catch {
      ElMessage.error('确认选择保存失败,请重试')
      return false
    } finally {
      confirmingSelection.value = false
    }
  }

  return {
    selectedTargetIds,
    confirmingSelection,
    toggleTargetSelection,
    isTargetSelected,
    selectedMergedTimeRange,
    selectedMergedTimeRangeText,
    confirmSelection,
  }
}
