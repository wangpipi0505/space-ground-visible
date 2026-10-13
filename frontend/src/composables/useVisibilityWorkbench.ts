import { computed, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiRequestError, getApi, postApi, putApi } from '../api/client'
import { createDefaultPayload, standardPayloadFields, validateStandardPayload } from '../utils/standardPayload'
import {
  cancelVisibilityTask,
  createVisibilityTask,
  getVisibilityTask,
  getVisibilityTaskResult,
} from '../api/visibilityTasks'
import type {
  BelongOption,
  DownlinkPlanRequest,
  DownlinkPlanResponse,
  PageResponse,
  PayloadProfileConfiguration,
  PayloadProfilesResponse,
  PayloadType,
  PlanningDefaults,
  Satellite,
  Station,
  Target,
  TargetFilterBreakdown,
  TargetQueryResponse,
  VisibilityPlanRequest,
  VisibilityPlanResponse,
  VisibilityPlanningTaskResponse,
  WindowValidationResponse,
  IlluminationConstraintRequest,
} from '../api/types'

type ServiceStatus = 'unknown' | 'checking' | 'up' | 'down'
export type TimeRange = [Date, Date]

/** 可见性规划的执行方式：同步直连等待结果，或提交后台任务后轮询进度。 */
export type VisibilityPlanMode = 'sync' | 'task'

/** 后台规划任务状态轮询间隔（毫秒）。 */
const VISIBILITY_TASK_POLL_INTERVAL_MS = 2000

/** 单个地面站在本次规划中临时使用的仰角约束。 */
export interface StationConstraintForm {
  stationId: string
  minElevationDeg?: number
  maxElevationDeg?: number
}

/** 同一目标由一颗或多颗我方卫星共同观测的时长约束。 */
export interface ObservationConstraintForm {
  satelliteIds: number[]
  minDurationSec: number
}

/** 目标目录预览与最终全量观测共用的人工筛选条件；任务时间由已选观测窗口自动提供。 */
export interface TargetQueryForm {
  belongs: string[]
  minWeight: number | undefined
  /** 目录历元下限，格式 YYDDD，例如 23340。 */
  minTleEpochCode: string
  /** 星历超过该建议时长仍计算，但会在结果中标注风险。 */
  maxApproximationDays: number
  excludeNameText: string
  excludeNameMode: 'TEXT' | 'REGEX'
}

const DEFAULT_PAGE_SIZE = 50
/** 与服务端兜底值保持一致，确保界面预览和实际计算不会因空站点参数产生偏差。 */
const DEFAULT_STATION_MIN_ELEVATION_DEG = 10
const DEFAULT_STATION_MAX_ELEVATION_DEG = 90

/** 创建默认六小时规划范围，起点对齐至整点。 */
function createTimeRange(): TimeRange {
  const startTime = new Date()
  startTime.setMinutes(0, 0, 0)
  return [startTime, new Date(startTime.getTime() + 6 * 60 * 60 * 1000)]
}

/** 在服务端默认值加载前提供保护和断链参数的初始值。 */
function createDefaultSettings(): PlanningDefaults {
  return {
    preGuardSec: 300, postGuardSec: 0, allowedGapSec: 0,
    orbitMaxApproximationDays: 14, targetMinTleEpochCode: '23340',
  }
}

/** 提供新载荷参数的典型默认值，供尚未配置过载荷的卫星直接微调。 */
function createDefaultPayloadForm(payloadType: PayloadType = 'SPACE'): PayloadProfileConfiguration {
  return createDefaultPayload(payloadType)
}

/**
 * 从服务端保存的多载荷响应中取出指定类型的表单值。复制对象而非直接绑定响应数组，
 * 防止用户编辑尚未点击保存的值时污染“已持久化配置”的状态提示。
 */
function profileFormFor(configuration: PayloadProfilesResponse | undefined,
                        payloadType: PayloadType): PayloadProfileConfiguration {
  const savedProfile = configuration?.profiles.find((profile) => profile.payloadType === payloadType)
  // 历史锥角配置没有足以还原统一约束的字段。展示可编辑的标准默认表单，
  // 并由面板明确提示用户核对参数后重新保存，避免把旧字段误当成新规则。
  if (!savedProfile || savedProfile.legacyGroundProfile) {
    return createDefaultPayloadForm(payloadType)
  }
  // legacyGroundProfile 是服务端回显状态而不是可保存参数，必须在编辑模型中移除，
  // 否则点击保存时会把只读兼容标记随请求一并发送。
  const { legacyGroundProfile: _legacyGroundProfile, ...editableProfile } = savedProfile
  const result = { ...editableProfile, payloadType, startupAngleRateDegPerSec: savedProfile.startupAngleRateDegPerSec ?? null }
  // 老集成响应仍可能仅提供米；只在公里字段缺失时换算，不删响应兼容字段，不映射旧锥角。
  if (result.minDistanceKm === undefined && savedProfile.minDistanceM !== undefined) result.minDistanceKm = savedProfile.minDistanceM / 1000
  if (result.maxDistanceKm === undefined && savedProfile.maxDistanceM !== undefined) result.maxDistanceKm = savedProfile.maxDistanceM / 1000
  return result
}

/** 将时间选择器返回的本地日期范围转换为接口使用的 UTC 时刻。 */
function toIsoRange(timeRange: TimeRange): { startTime: string; endTime: string } {
  return { startTime: timeRange[0].toISOString(), endTime: timeRange[1].toISOString() }
}

/**
 * 将接口 UTC 时刻固定格式化为北京时间，而不是使用浏览器所在时区。
 * 这样跨地域用户查看同一规划结果时不会把同一条弧段显示成不同的本地钟点。
 */
function formatTime(time: string): string {
  // 旧历史记录 createTime/completeTime 使用无后缀的 UTC LocalDateTime。
  // 仅为这种完整日期时间补 Z；已有 Z 或显式偏移保持原义，避免浏览器本地时区二次换算。
  const utcTime = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(time) ? `${time}Z` : time
  return new Date(utcTime).toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai', hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3,
  }) + '（北京时间）'
}

/** 将秒数格式化为便于比对的持续时间文本。 */
function formatDuration(durationSec: number): string {
  return `${durationSec.toFixed(3)} 秒`
}

/** 从统一接口异常或普通异常中提取可直接展示的错误信息。 */
function failureMessage(error: unknown): string {
  if (error instanceof ApiRequestError) {
    return error.message
  }
  return error instanceof Error ? error.message : '未知异常'
}

/**
 * 管理三栏规划工作台的状态和操作：左侧选择卫星、地面站，中间配置站星下传，右侧筛选目标并规划观测。
 *
 * @returns 组件使用的响应式数据与接口调用方法
 */
export function useVisibilityWorkbench() {
  const serviceStatus = ref<ServiceStatus>('unknown')
  const loadingReference = ref(false)
  const pendingAction = ref('')

  const satelliteKeyword = ref('')
  const satellites = shallowRef<Satellite[]>([])
  const satelliteTotal = ref(0)
  const satellitePageNumber = ref(1)
  const satellitePageSize = ref(DEFAULT_PAGE_SIZE)
  const selectedTaskSatellites = shallowRef<Satellite[]>([])
  const satellitePayloadConfigurations = shallowRef<Record<number, PayloadProfilesResponse | undefined>>({})
  const satellitePayloadForms = reactive<Record<number, PayloadProfileConfiguration>>({})
  const loadingSatellitePayloadIds = shallowRef<number[]>([])
  const savingSatellitePayloadId = ref<number | null>(null)

  const stations = shallowRef<Station[]>([])
  const defaultSettings = ref<PlanningDefaults>(createDefaultSettings())
  const downlinkTimeRange = ref<TimeRange>(createTimeRange())
  const downlinkForm = reactive({
    coarseStepSec: 60,
    fineStepSec: 1,
    preGuardSec: 300,
    postGuardSec: 0,
    allowedGapSec: 0,
    satelliteIllumination: 'ANY' as 'ANY' | 'SUNLIT' | 'SHADOW',
    stations: [] as StationConstraintForm[],
    links: [] as Array<{ satelliteId: number; stationId: string }>,
  })
  const downlinkPlan = shallowRef<DownlinkPlanResponse | null>(null)

  const targetForm = reactive<TargetQueryForm>({
    belongs: [], minWeight: undefined, minTleEpochCode: '23340', maxApproximationDays: 14,
    excludeNameText: '', excludeNameMode: 'TEXT',
  })
  // 只使用后端 BELONG_ 数据字典，数据重导入后可选项和数量会随参考数据一并刷新。
  const belongOptions = shallowRef<BelongOption[]>([])
  const targets = shallowRef<Target[]>([])
  // 与当前目标目录页同源；筛选条件改变时立即清除，不能复用上一次的命中统计。
  const targetFilterBreakdown = shallowRef<TargetFilterBreakdown | undefined>()
  /**
   * 当前一次筛选命中的完整目标集合，不受分页和下传窗口变化影响。
   * 规划请求只回传这组 ID，避免服务端用表单条件再次查询而产生“预览和计算不一致”。
   */
  const selectedTargetIds = shallowRef<number[]>([])
  const targetTotal = ref(0)
  const targetPageNumber = ref(1)
  const targetPageSize = ref(DEFAULT_PAGE_SIZE)

  const selectedWindowLabel = ref('')
  const selectedWindowStartTime = ref('')
  const selectedWindowEndTime = ref('')
  const customWindowRange = ref<TimeRange>(createTimeRange())
  const visibilityTimeRange = ref<TimeRange>(createTimeRange())
  const visibilityForm = reactive({
    coarseStepSec: 60,
    fineStepSec: 0.001,
    illuminationConstraint: {
      // WinTool 基准场景未勾选“目标有光照”，因此初始值不能额外施加光照筛选。
      // 使用者仍可在页面中按任务需要改为“观测星阴影且目标阳照”等更严格条件。
      mode: 'ANY',
      observerStates: [],
      targetStates: [],
    } as IlluminationConstraintRequest,
    observationConstraints: [] as ObservationConstraintForm[],
  })
  const observationConstraintDraft = reactive<ObservationConstraintForm>({ satelliteIds: [], minDurationSec: 400 })
  const visibilityPlan = shallowRef<VisibilityPlanResponse | null>(null)
  // 全目标规划可能包含数万组合，默认后台任务才能持续显示进度并保留失败原因。
  const visibilityPlanMode = ref<VisibilityPlanMode>('task')
  const visibilityTask = shallowRef<VisibilityPlanningTaskResponse | null>(null)
  const visibilityTaskRunning = ref(false)
  let visibilityTaskTimer: ReturnType<typeof setInterval> | null = null

  /**
   * 仅当真正的目录筛选条件变化时作废旧结果。下传窗口和“建议近似时长”都不是目标筛选条件，
   * 改动它们不能把已确认的目标集合清空，否则用户会误以为规划仍会使用页面显示的目标。
   */
  watch(() => [targetForm.belongs, targetForm.minWeight, targetForm.minTleEpochCode,
    targetForm.excludeNameText, targetForm.excludeNameMode], () => {
    targets.value = []
    targetFilterBreakdown.value = undefined
    selectedTargetIds.value = []
    targetTotal.value = 0
    targetPageNumber.value = 1
  }, { deep: true })

  const serviceStatusText = computed(() => ({ unknown: '未检查', checking: '检查中', up: '服务可用', down: '服务不可用' })[serviceStatus.value])
  const serviceStatusType = computed<'info' | 'warning' | 'success' | 'danger'>(() => ({
    unknown: 'info', checking: 'warning', up: 'success', down: 'danger',
  } as const)[serviceStatus.value])
  const selectedTaskSatelliteIds = computed(() => selectedTaskSatellites.value.map((satellite) => satellite.id))

  /**
   * 目标观测只能选择无断档的覆盖窗口，而不是某一条原始站星链路。
   * 后端仍返回原始 pair 窗口用于复核；这里将合并窗口的参与链路转换为用户可读标签。
   */
  const downlinkRows = computed(() => (downlinkPlan.value?.coverageWindows ?? []).map((window, index) => {
    const linkNames = window.links.map((link) => {
      const satelliteName = selectedTaskSatellites.value.find((satellite) => satellite.id === link.satelliteId)?.name ?? '未知卫星'
      const stationName = stations.value.find((station) => station.id === link.stationId)?.name ?? '未知地面站'
      return `${satelliteName}—${stationName}`
    })
    return {
      key: `coverage-${index}-${window.startTime}`,
      sourceLabel: linkNames.join('；'),
      sourceCount: window.links.length,
      ...window,
    }
  }) ?? [])
  /** 下传规划的单星原始窗口：与合并覆盖窗口并列展示，供观察与选择单星链路。 */
  const singleWindowRows = computed(() => (downlinkPlan.value?.satelliteStationWindows ?? []).flatMap((sw) => {
    const satelliteName = selectedTaskSatellites.value.find((satellite) => satellite.id === sw.satelliteId)?.name ?? '未知卫星'
    const stationName = stations.value.find((station) => station.id === sw.stationId)?.name ?? '未知地面站'
    return sw.windows.map((window, index) => ({
      key: `single-${sw.satelliteId}-${sw.stationId}-${index}-${window.startTime}`,
      satelliteName,
      stationName,
      startTime: window.startTime,
      endTime: window.endTime,
      durationSec: window.durationSec,
    }))
  }) ?? [])
  /** 后台规划任务的完成百分比；后端尚未给出总配对数时按 0 展示。 */
  const visibilityTaskProgressPercent = computed(() => {
    const task = visibilityTask.value
    if (!task) {
      return 0
    }
    // 优先按筛选后的目标数量展示进度
    if (task.totalTargets && task.totalTargets > 0) {
      return Math.min(100, Math.round(((task.completedTargets ?? 0) / task.totalTargets) * 100))
    }
    if (task.totalPairs <= 0) {
      return 0
    }
    return Math.min(100, Math.round((task.completedPairs / task.totalPairs) * 100))
  })

  /** 调用健康检查接口并更新服务状态标签。 */
  async function checkHealth(): Promise<void> {
    serviceStatus.value = 'checking'
    try {
      const response = await getApi<{ status?: string }>('/health')
      serviceStatus.value = response.status === 'UP' ? 'up' : 'down'
    } catch (error) {
      serviceStatus.value = 'down'
    }
  }

  /** 加载我方卫星目录的指定页，保留跨页选择结果。 */
  async function loadSatellitePage(pageNumber: number): Promise<void> {
    const keyword = satelliteKeyword.value.trim()
    const suffix = keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''
    const response = await getApi<PageResponse<Satellite>>(
      `/satellites/page?pageNumber=${pageNumber}&pageSize=${satellitePageSize.value}${suffix}`,
    )
    satellites.value = response.records
    satelliteTotal.value = response.total
    satellitePageNumber.value = response.pageNumber
    satellitePageSize.value = response.pageSize
  }

  /** 从第一页执行我方卫星关键字筛选。 */
  async function queryTaskSatellites(): Promise<void> {
    loadingReference.value = true
    try {
      await loadSatellitePage(1)
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      loadingReference.value = false
    }
  }

  /** 切换我方卫星目录页，不影响已经跨页选中的卫星。 */
  async function changeSatellitePage(pageNumber: number): Promise<void> {
    loadingReference.value = true
    try {
      await loadSatellitePage(pageNumber)
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      loadingReference.value = false
    }
  }

  /** 增加或移除本次任务卫星，并清理失效的站星和观测组合关系。 */
  function toggleTaskSatellite(satellite: Satellite, selected: boolean): void {
    const exists = selectedTaskSatellites.value.some((item) => item.id === satellite.id)
    if (selected && !exists) {
      satellitePayloadForms[satellite.id] = createDefaultPayloadForm()
      selectedTaskSatellites.value = [...selectedTaskSatellites.value, satellite]
      void loadSatellitePayload(satellite.id)
      return
    }
    if (!selected && exists) {
      selectedTaskSatellites.value = selectedTaskSatellites.value.filter((item) => item.id !== satellite.id)
      const validIds = new Set(selectedTaskSatelliteIds.value)
      downlinkForm.links = downlinkForm.links.filter((link) => validIds.has(link.satelliteId))
      visibilityForm.observationConstraints = visibilityForm.observationConstraints.filter((constraint) =>
        constraint.satelliteIds.every((id) => validIds.has(id)))
      observationConstraintDraft.satelliteIds = observationConstraintDraft.satelliteIds.filter((id) => validIds.has(id))
      const nextConfigurations = { ...satellitePayloadConfigurations.value }
      delete nextConfigurations[satellite.id]
      satellitePayloadConfigurations.value = nextConfigurations
      delete satellitePayloadForms[satellite.id]
    }
  }

  /**
   * 读取任务卫星全部已保存载荷，并用当前活动类型初始化可编辑表单。
   * 对空和对地配置在服务端独立保存，切换类型不会再覆盖另一类参数。
   *
   * @param satelliteId 当前任务卫星主键
   */
  async function loadSatellitePayload(satelliteId: number): Promise<void> {
    if (loadingSatellitePayloadIds.value.includes(satelliteId)) {
      return
    }
    loadingSatellitePayloadIds.value = [...loadingSatellitePayloadIds.value, satelliteId]
    try {
      const configuration = await getApi<PayloadProfilesResponse>(`/satellites/${satelliteId}/payload-profiles`)
      satellitePayloadConfigurations.value = { ...satellitePayloadConfigurations.value, [satelliteId]: configuration }
      const activeType = configuration.activePayloadType ?? 'SPACE'
      satellitePayloadForms[satelliteId] = profileFormFor(configuration, activeType)
    } catch (error) {
      ElMessage.error(`读取卫星载荷失败：${failureMessage(error)}`)
      satellitePayloadConfigurations.value = {
        ...satellitePayloadConfigurations.value,
        [satelliteId]: { activePayloadType: null, profiles: [] },
      }
      satellitePayloadForms[satelliteId] = createDefaultPayloadForm()
    } finally {
      loadingSatellitePayloadIds.value = loadingSatellitePayloadIds.value.filter((id) => id !== satelliteId)
    }
  }

  /**
   * 保存当前选择的对空或对地载荷，并将它设为后续规划的活动载荷。
   *
   * @param satelliteId 当前任务卫星主键
   */
  async function saveSatellitePayload(satelliteId: number): Promise<void> {
    const form = satellitePayloadForms[satelliteId]
    if (!form) return
    const errors = validateStandardPayload(form)
    if (errors.length) {
      ElMessage.error(errors.join('；'))
      return
    }
    // 白名单保留所有标准原值，既不发旧米/锥角字段，也不把 sentinel 回写为内部计算极大值。
    const request = standardPayloadFields(form)
    savingSatellitePayloadId.value = satelliteId
    try {
      const configuration = await putApi<PayloadProfilesResponse>(`/satellites/${satelliteId}/payload-profiles`, request)
      satellitePayloadConfigurations.value = { ...satellitePayloadConfigurations.value, [satelliteId]: configuration }
      satellitePayloadForms[satelliteId] = profileFormFor(configuration, form.payloadType)
      ElMessage.success(`${form.payloadType === 'SPACE' ? '对空' : '对地'}载荷已保存并设为活动载荷。`)
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      savingSatellitePayloadId.value = null
    }
  }

  /**
   * 切换编辑的载荷类型时回填该类型已保存配置；尚未保存过时使用典型默认值。
   * 这只改变编辑表单，不会改变真正参与计算的活动载荷，避免误点下拉框立即改变规划结果。
   */
  function selectSatellitePayloadType(satelliteId: number, payloadType: PayloadType): void {
    satellitePayloadForms[satelliteId] = profileFormFor(satellitePayloadConfigurations.value[satelliteId], payloadType)
  }

  /** 根据地面站长期参数创建本次规划使用的临时仰角约束。 */
function createStationConstraint(stationId: string): StationConstraintForm {
  const station = stations.value.find((item) => item.id === stationId)
  return {
    stationId,
    minElevationDeg: station?.minElevation ?? DEFAULT_STATION_MIN_ELEVATION_DEG,
    maxElevationDeg: station?.maxElevation ?? DEFAULT_STATION_MAX_ELEVATION_DEG,
  }
}

  /** 更新已选地面站，并删除已取消站点关联的卫星—地面站链路。 */
  function updateSelectedStations(stationIds: string[]): void {
    const existing = new Map(downlinkForm.stations.map((station) => [station.stationId, station]))
    downlinkForm.stations = stationIds.map((stationId) => existing.get(stationId) ?? createStationConstraint(stationId))
    const selectedStationIds = new Set(stationIds)
    downlinkForm.links = downlinkForm.links.filter((link) => selectedStationIds.has(link.stationId))
  }

  /** 增加或移除一条用户明确选择的卫星—地面站下传关系。 */
  function toggleSatelliteStationLink(satelliteId: number, stationId: string, selected: boolean): void {
    const exists = downlinkForm.links.some((link) => link.satelliteId === satelliteId && link.stationId === stationId)
    if (selected && !exists) {
      downlinkForm.links.push({ satelliteId, stationId })
    }
    if (!selected && exists) {
      downlinkForm.links = downlinkForm.links.filter((link) => link.satelliteId !== satelliteId || link.stationId !== stationId)
    }
  }

  /** 添加同一目标的单星或多星共同观测约束。 */
  function addObservationConstraint(): void {
    if (observationConstraintDraft.satelliteIds.length === 0) {
      ElMessage.warning('请先勾选观测卫星。')
      return
    }
    visibilityForm.observationConstraints.push({
      satelliteIds: [...observationConstraintDraft.satelliteIds],
      minDurationSec: observationConstraintDraft.minDurationSec,
    })
    observationConstraintDraft.satelliteIds = []
  }

  /** 删除指定位置的目标观测组合约束。 */
  function removeObservationConstraint(index: number): void {
    visibilityForm.observationConstraints.splice(index, 1)
  }

  /**
   * 将名称过滤输入转换为后端统一执行的正则：文本模式会转义元字符，
   * 原始正则模式保留表达式本身并由后端返回明确的语法校验错误。
   */
  function toExcludedNameRegex(): string | undefined {
    const text = targetForm.excludeNameText.trim()
    if (!text) {
      return undefined
    }
    return targetForm.excludeNameMode === 'REGEX' ? text : text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  }

  /** 校验日期范围是否包含严格递增的起止时刻。 */
  function validateTimeRange(timeRange: TimeRange | undefined): timeRange is TimeRange {
    if (!timeRange || timeRange.length !== 2 || timeRange[0] >= timeRange[1]) {
      ElMessage.warning('请选择有效的起止时间。')
      return false
    }
    return true
  }

  /** 生成仅包含显式站星关系的下传规划请求。 */
  function downlinkPayload(timeRange = downlinkTimeRange.value): DownlinkPlanRequest {
    return {
      ...toIsoRange(timeRange),
      satelliteIds: [...selectedTaskSatelliteIds.value],
      coarseStepSec: downlinkForm.coarseStepSec,
      fineStepSec: downlinkForm.fineStepSec,
      preGuardSec: downlinkForm.preGuardSec,
      postGuardSec: downlinkForm.postGuardSec,
      allowedGapSec: downlinkForm.allowedGapSec,
      satelliteIllumination: downlinkForm.satelliteIllumination,
      stations: downlinkForm.stations.map((station) => ({ ...station })),
      links: downlinkForm.links.map((link) => ({ ...link })),
    }
  }

  /** 对用户互选的每一条站星关系并行规划独立下传窗口。 */
  async function runDownlinkPlan(): Promise<void> {
    if (selectedTaskSatelliteIds.value.length === 0 || downlinkForm.stations.length === 0
      || downlinkForm.links.length === 0 || !validateTimeRange(downlinkTimeRange.value)) {
      ElMessage.warning('请先选择任务卫星、地面站、站星下传关系并设置有效规划时段。')
      return
    }
    pendingAction.value = 'plan-downlink'
    try {
      const response = await postApi<DownlinkPlanResponse>('/downlink/plan', downlinkPayload())
      downlinkPlan.value = response
      clearObservationWindow()
      ElMessage.success('下传窗口规划完成。')
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      pendingAction.value = ''
    }
  }

  /** 将一条已规划的下传窗口用作目标观测的时间范围。 */
  function selectPlannedWindow(startTime: string, endTime: string, label: string): void {
    setObservationWindow(startTime, endTime, label)
  }

  /**
   * 保存唯一的目标观测时间边界，并丢弃不属于新边界的旧观测结果。
   *
   * @param startTime 已选择或已校验窗口的开始时刻
   * @param endTime 已选择或已校验窗口的结束时刻
   * @param label 窗口来源的可读说明
   */
  function setObservationWindow(startTime: string, endTime: string, label: string): void {
    visibilityTimeRange.value = [new Date(startTime), new Date(endTime)]
    selectedWindowLabel.value = label
    selectedWindowStartTime.value = startTime
    selectedWindowEndTime.value = endTime
    visibilityPlan.value = null
    resetVisibilityTask()
  }

  /** 清除已失效的观测窗口和基于该窗口生成的观测结果。 */
  function clearObservationWindow(): void {
    selectedWindowLabel.value = ''
    selectedWindowStartTime.value = ''
    selectedWindowEndTime.value = ''
    visibilityPlan.value = null
    resetVisibilityTask()
  }

  /** 停止后台规划任务轮询并丢弃任务进度，用于观测窗口变化或重新规划前。 */
  function resetVisibilityTask(): void {
    stopVisibilityTaskPolling()
    visibilityTask.value = null
  }

  /** 仅停止后台规划任务轮询计时器，保留最近一次任务状态用于展示。 */
  function stopVisibilityTaskPolling(): void {
    if (visibilityTaskTimer !== null) {
      clearInterval(visibilityTaskTimer)
      visibilityTaskTimer = null
    }
    visibilityTaskRunning.value = false
  }

  /** 校验自选时间段是否被任意一条已选站星稳定下传窗口完整覆盖。 */
  async function validateCustomWindow(): Promise<void> {
    if (!validateTimeRange(customWindowRange.value)) {
      return
    }
    pendingAction.value = 'validate-downlink'
    try {
      const response = await postApi<WindowValidationResponse>('/downlink/validate-window', downlinkPayload(customWindowRange.value))
      downlinkPlan.value = response.plan
      if (!response.satisfied) {
        ElMessage.warning('自选时段不满足当前下传约束。')
        return
      }
      setObservationWindow(customWindowRange.value[0].toISOString(), customWindowRange.value[1].toISOString(), '自选并校验通过的时间段')
      ElMessage.success('自选时段校验通过。')
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      pendingAction.value = ''
    }
  }

  /**
   * 依据当前条件分页显示目标目录，并保存响应携带的完整命中 ID。
   * 分页只影响界面展示；保存的 ID 集合才是后续规划的唯一目标输入。
   */
  async function queryTargets(pageNumber = 1): Promise<void> {
    pendingAction.value = 'query-targets'
    try {
      const response = await postApi<TargetQueryResponse>('/targets/query', {
        belongs: [...targetForm.belongs],
        minWeight: targetForm.minWeight,
        // 历元下限是目标目录条件；建议近似时长仅用于规划风险提示，不参与筛选。
        minTleEpochCode: targetForm.minTleEpochCode.trim(),
        excludeNameRegex: toExcludedNameRegex(),
        pageNumber,
        pageSize: targetPageSize.value,
      })
      targets.value = response.targets
      targetFilterBreakdown.value = response.filterBreakdown
      // 兼容升级期间仍可能连接到旧后端：缺少完整 ID 时禁止规划，绝不退化成全库二次筛选。
      selectedTargetIds.value = Array.isArray(response.targetIds) ? [...response.targetIds] : []
      targetTotal.value = response.total
      targetPageNumber.value = response.pageNumber
      targetPageSize.value = response.pageSize
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      pendingAction.value = ''
    }
  }

  /** 校验可见性规划前置条件（任务卫星、观测窗口、载荷配置与光照组合），不通过时提示原因。 */
  function validateVisibilityInputs(): boolean {
    if (selectedTaskSatelliteIds.value.length === 0 || !selectedWindowLabel.value || !validateTimeRange(visibilityTimeRange.value)) {
      ElMessage.warning('请先选择任务卫星，并选择或校验下传窗口。')
      return false
    }
    if (selectedTargetIds.value.length === 0) {
      ElMessage.warning('请先完成目标筛选，再开始目标观测规划。')
      return false
    }
    const unconfiguredSatellites = selectedTaskSatellites.value.filter((satellite) =>
      !(satellitePayloadConfigurations.value[satellite.id]?.profiles.length))
    if (unconfiguredSatellites.length > 0) {
      ElMessage.warning(`请先配置活动载荷：${unconfiguredSatellites.map((satellite) => satellite.name).join('、')}`)
      return false
    }
    if (visibilityForm.illuminationConstraint.mode === 'CUSTOM'
      && (visibilityForm.illuminationConstraint.observerStates.length === 0
        || visibilityForm.illuminationConstraint.targetStates.length === 0)) {
      ElMessage.warning('自定义光照组合时，请分别选择观测星和目标的允许状态。')
      return false
    }
    return true
  }

  /** 组装同步规划与后台任务共用的可见性规划请求体。 */
  function visibilityPlanPayload(): VisibilityPlanRequest {
    return {
      ...toIsoRange(visibilityTimeRange.value),
      observerSatelliteIds: [...selectedTaskSatelliteIds.value],
      // 只提交筛选接口确认的完整 ID，不重复提交国籍、质量、历元、名称等筛选字段。
      targetIds: [...selectedTargetIds.value],
      // 该阈值仅生成星历时效风险说明，不会从目标集合中剔除数据。
      maxApproximationDays: targetForm.maxApproximationDays,
      coarseStepSec: visibilityForm.coarseStepSec,
      fineStepSec: visibilityForm.fineStepSec,
      illuminationConstraint: {
        mode: visibilityForm.illuminationConstraint.mode,
        observerStates: [...visibilityForm.illuminationConstraint.observerStates],
        targetStates: [...visibilityForm.illuminationConstraint.targetStates],
      },
      observationConstraints: visibilityForm.observationConstraints.map((constraint) => ({
        satelliteIds: [...constraint.satelliteIds], minDurationSec: constraint.minDurationSec,
      })),
    }
  }

  /** 按当前执行模式分发可见性规划：同步直连等待结果，或提交后台任务轮询进度。 */
  async function runVisibilityPlan(): Promise<void> {
    if (visibilityPlanMode.value === 'task') {
      await runVisibilityTaskPlan()
      return
    }
    await runVisibilityPlanSync()
  }

  /** 以当前筛选条件遍历全部目标并同步计算指定卫星组合的可见弧段。 */
  async function runVisibilityPlanSync(): Promise<void> {
    if (!validateVisibilityInputs()) {
      return
    }
    resetVisibilityTask()
    pendingAction.value = 'plan-visibility'
    try {
      const response = await postApi<VisibilityPlanResponse>('/visibility/plan', visibilityPlanPayload())
      visibilityPlan.value = response
      ElMessage.success('目标观测规划完成。')
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      pendingAction.value = ''
    }
  }

  /** 提交异步可见性规划任务，提交成功后立即查询一次状态并按固定间隔轮询。 */
  async function runVisibilityTaskPlan(): Promise<void> {
    if (!validateVisibilityInputs()) {
      return
    }
    resetVisibilityTask()
    pendingAction.value = 'plan-visibility-task'
    try {
      const created = await createVisibilityTask(visibilityPlanPayload())
      visibilityTask.value = {
        taskId: created.taskId, status: 'PENDING', totalPairs: 0, completedPairs: 0,
      }
      visibilityTaskRunning.value = true
      void pollVisibilityTask(created.taskId)
      visibilityTaskTimer = setInterval(() => { void pollVisibilityTask(created.taskId) }, VISIBILITY_TASK_POLL_INTERVAL_MS)
      ElMessage.success('后台规划任务已提交。')
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      pendingAction.value = ''
    }
  }

  /** 查询后台规划任务状态；成功时拉取结果，失败或被取消时停止轮询。 */
  async function pollVisibilityTask(taskId: string): Promise<void> {
    try {
      const task = await getVisibilityTask(taskId)
      await applyVisibilityTaskStatus(task)
    } catch (error) {
      // 旧任务在窗口切换后迟到的响应/错误不能停止新任务的轮询。
      if (visibilityTask.value?.taskId !== taskId) return
      stopVisibilityTaskPolling()
      ElMessage.error(failureMessage(error))
    }
  }

  /** GET与DELETE都只采用服务端真实状态；迟到的非终态响应不能覆盖已确认终态。 */
  async function applyVisibilityTaskStatus(task: VisibilityPlanningTaskResponse): Promise<void> {
    const current = visibilityTask.value
    if (!current || current.taskId !== task.taskId
      || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(current.status)) return
    visibilityTask.value = task
    if (task.status === 'SUCCEEDED') {
      stopVisibilityTaskPolling()
      const result = await getVisibilityTaskResult(task.taskId)
      if (visibilityTask.value?.taskId !== task.taskId) return
      visibilityPlan.value = result
      ElMessage.success('目标观测规划完成。')
      return
    }
    if (task.status === 'FAILED') {
      stopVisibilityTaskPolling()
      ElMessage.error(task.errorMessage || '后台规划任务执行失败。')
      return
    }
    if (task.status === 'CANCELLED') {
      stopVisibilityTaskPolling()
      ElMessage.info('后台规划任务已取消。')
    }
  }

  /** 发出取消请求；服务仍RUNNING时保持轮询，只有服务确认终态后才停止并展示对应状态。 */
  async function cancelVisibilityTaskPlan(): Promise<void> {
    const task = visibilityTask.value
    if (!task) {
      return
    }
    try {
      const response = await cancelVisibilityTask(task.taskId)
      await applyVisibilityTaskStatus(response)
      if (visibilityTask.value?.taskId === task.taskId && visibilityTaskRunning.value) {
        ElMessage.info('取消请求已提交，正在等待后台任务确认。')
      }
    } catch (error) {
      ElMessage.error(failureMessage(error))
    }
  }

  /** 并行加载卫星目录首页、地面站、默认规划参数和目标 BELONG_ 数据字典。 */
  async function refreshReferenceData(): Promise<void> {
    loadingReference.value = true
    try {
      const [satellitePage, stationData, settings, belongOptionData] = await Promise.all([
        getApi<PageResponse<Satellite>>(`/satellites/page?pageNumber=1&pageSize=${satellitePageSize.value}`),
        getApi<Station[]>('/stations'),
        getApi<PlanningDefaults>('/defaults'),
        getApi<BelongOption[]>('/targets/belong-options'),
      ])
      satellites.value = satellitePage.records
      satelliteTotal.value = satellitePage.total
      satellitePageNumber.value = satellitePage.pageNumber
      satellitePageSize.value = satellitePage.pageSize
      stations.value = stationData
      defaultSettings.value = settings
      // 接口响应为空时仍保持数组类型，保证选择组件在降级场景下可安全渲染。
      belongOptions.value = belongOptionData ?? []
      downlinkForm.preGuardSec = settings.preGuardSec
      downlinkForm.postGuardSec = settings.postGuardSec
      downlinkForm.allowedGapSec = settings.allowedGapSec
      // 服务端返回部署默认值；页面保留用户在本次任务中已填写的值，避免刷新基础数据时误覆盖。
      if (targetForm.minTleEpochCode === '23340' && settings.targetMinTleEpochCode) {
        targetForm.minTleEpochCode = settings.targetMinTleEpochCode
      }
      if (targetForm.maxApproximationDays === 14 && settings.orbitMaxApproximationDays !== undefined) {
        targetForm.maxApproximationDays = settings.orbitMaxApproximationDays
      }
    } catch (error) {
      ElMessage.error(failureMessage(error))
    } finally {
      loadingReference.value = false
    }
  }

  /** 初始化健康检查及工作台需要的基础数据。 */
  function initialize(): void {
    void checkHealth()
    void refreshReferenceData()
  }

  return {
    addObservationConstraint,
    belongOptions,
    cancelVisibilityTaskPlan,
    changeSatellitePage,
    checkHealth,
    customWindowRange,
    defaultSettings,
    downlinkForm,
    downlinkPlan,
    downlinkRows,
    singleWindowRows,
    downlinkTimeRange,
    formatDuration,
    formatTime,
    initialize,
    loadingReference,
    observationConstraintDraft,
    pendingAction,
    queryTargets,
    queryTaskSatellites,
    removeObservationConstraint,
    runDownlinkPlan,
    runVisibilityPlan,
    satelliteKeyword,
    satellitePayloadConfigurations,
    satellitePayloadForms,
    satellitePageNumber,
    satellitePageSize,
    satelliteTotal,
    satellites,
    selectPlannedWindow,
    selectedTaskSatelliteIds,
    selectedTaskSatellites,
    selectedTargetIds,
    selectedWindowLabel,
    selectedWindowStartTime,
    selectedWindowEndTime,
    serviceStatus,
    serviceStatusText,
    serviceStatusType,
    savingSatellitePayloadId,
    saveSatellitePayload,
    selectSatellitePayloadType,
    stations,
    targetForm,
    targetFilterBreakdown,
    targetPageNumber,
    targetPageSize,
    targetTotal,
    targets,
    toggleSatelliteStationLink,
    toggleTaskSatellite,
    loadingSatellitePayloadIds,
    updateSelectedStations,
    validateCustomWindow,
    visibilityForm,
    visibilityPlan,
    visibilityPlanMode,
    visibilityTask,
    visibilityTaskProgressPercent,
    visibilityTaskRunning,
    visibilityTimeRange,
  }
}
