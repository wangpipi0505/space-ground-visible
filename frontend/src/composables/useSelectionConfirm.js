/**
 * 目标观测“确认选择”状态与提交逻辑。
 *
 * 职责:管理按目标勾选的状态、计算勾选目标的合并完整时段(并集)、
 * 并把勾选明细提交到任务结果记录的确认选择接口。
 */
import { computed, ref } from 'vue'
import { Message as ElMessage } from 'element-ui'
import { formatIsoToUtcText, mergeTimeSegments } from '../utils/winToolSummary'
export function useSelectionConfirm(deps) {
  const selectedTargetIds = ref([])
  const confirmingSelection = ref(false)
  function toggleTargetSelection(targetId, checked) {
    const next = new Set(selectedTargetIds.value)
    if (checked) {
      next.add(targetId)
    } else {
      next.delete(targetId)
    }
    selectedTargetIds.value = [...next]
  }
  function isTargetSelected(targetId) {
    return selectedTargetIds.value.includes(targetId)
  }
  /** 勾选目标的全部弧段并集:即“确认选择”要提交的合并完整时段。 */
  const selectedMergedTimeRange = computed(() => {
    const selected = new Set(selectedTargetIds.value)
    const raw = []
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
    return (
      segments
        .map((segment) => `${formatIsoToUtcText(segment.startTime)} ~ ${formatIsoToUtcText(segment.endTime)}`)
        .join('；') + `（累计 ${selectedMergedTimeRange.value.totalDurationSec.toFixed(3)} 秒）`
    )
  })
  async function confirmSelection() {
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
      const request = {
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

/**
 * SelectionConfirmDeps
 * @typedef {Object} SelectionConfirmDeps
 * @property {() => string | null | undefined} taskId 当前后台规划任务标识;用于在任务结果记录里定位确认落库的记录。
 * @property {(taskId: string) => Promise<{ records: Array<{ id: string; taskId: string; }>; }>} findRecordPage 分页查询任务结果记录(按 keyword 匹配任务标识定位记录)。
 * @property {(recordId: string, request: SelectionConfirmRequest) => Promise<{ selectionCount: number; mergedSegments: number; }>} putSelection 保存确认选择(含合并完整时段与逐目标明细)。
 * @property {() => { startTime: string; endTime: string; }} planningWindow 规划时间段(即当前观测计算窗口)。
 * @property {() => Array<{ targetId: string; startTime: string; endTime: string; durationSec: number; }>} collectArcs 收集全部弧段(带目标 ID),供确认选择计算勾选目标的合并完整时段。
 * @property {(targetIds: string[]) => SelectionTarget[]} buildSelections 构建逐目标提交明细(含 NORAD/重量/TLE 与逐星统计)。
 */
