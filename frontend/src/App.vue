<script setup lang="ts">
/** 空间规划入口：连接卫星、载荷、站星链路、目录与后台任务状态，并切换历史结果视图。 */
import { computed, onMounted, ref } from 'vue'
import BallisticPayloadConfigurationView from './views/BallisticPayloadConfigurationView.vue'
import DownlinkPlanningStep from './components/DownlinkPlanningStep.vue'
import ObservationConstraintPanel from './components/ObservationConstraintPanel.vue'
import SatelliteStationLinkPanel from './components/SatelliteStationLinkPanel.vue'
import SatellitePayloadPanel from './components/SatellitePayloadPanel.vue'
import StationConstraintStep from './components/StationConstraintStep.vue'
import TargetSelectionStep from './components/TargetSelectionStep.vue'
import TaskResultRecordsPanel from './components/TaskResultRecordsPanel.vue'
import TaskSatelliteSelectionStep from './components/TaskSatelliteSelectionStep.vue'
import VisibilityPlanningStep from './components/VisibilityPlanningStep.vue'
import WindowSelectionPanel from './components/WindowSelectionPanel.vue'
import { useVisibilityWorkbench } from './composables/useVisibilityWorkbench'

const {
  addObservationConstraint,
  belongOptions,
  changeSatellitePage,
  customWindowRange,
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
  cancelVisibilityTaskPlan,
  satelliteKeyword,
  satellitePageNumber,
  satellitePageSize,
  satellitePayloadConfigurations,
  satellitePayloadForms,
  satelliteTotal,
  satellites,
  selectPlannedWindow,
  selectedTaskSatellites,
  selectedWindowLabel,
  selectedWindowStartTime,
  selectedWindowEndTime,
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
} = useVisibilityWorkbench()

/** 原工作台始终由 tabs 保留挂载；弹道草稿首次打开后同样保留，不重置任务、目录选择或草稿。 */
const activeView = ref<'workbench' | 'records' | 'ballistic-payloads'>('workbench')
/** 当前目录页加已选任务星，分页后仍可选到空间既有任务星；弹道拥有独立选择/草稿。 */
const payloadSatelliteOptions = computed(() => [...new Map([...satellites.value, ...selectedTaskSatellites.value].map((satellite) => [satellite.id, satellite])).values()])

onMounted(() => {
  initialize()
})
</script>

<template>
  <main class="workspace-shell">
    <header class="workspace-toolbar">
      <h1 class="workspace-title">空间目标可见窗口规划</h1>
    </header>

    <el-tabs v-model="activeView" class="workspace-tabs">
      <el-tab-pane label="规划工作台" name="workbench">
        <section class="planning-workbench">
          <section class="workbench-column selection-column" data-test="satellite-station-selection" aria-label="卫星与地面站选择">
            <div class="column-guide">
              <span class="column-guide-step">1</span>
              <div class="column-guide-text">
                <h2>选择卫星与地面站</h2>
                <p>勾选任务卫星并维护对空载荷，再勾选地面站并确认仰角约束。</p>
              </div>
            </div>
        <TaskSatelliteSelectionStep
          v-model:keyword="satelliteKeyword"
          :loading="loadingReference"
          :satellites="satellites"
          :selected-satellites="selectedTaskSatellites"
          :total="satelliteTotal"
          :page-number="satellitePageNumber"
          :page-size="satellitePageSize"
          @query="queryTaskSatellites"
          @page-change="changeSatellitePage"
          @toggle="toggleTaskSatellite"
        />
        <SatellitePayloadPanel
          :satellites="selectedTaskSatellites"
          :configurations="satellitePayloadConfigurations"
          :forms="satellitePayloadForms"
          :loading-satellite-ids="loadingSatellitePayloadIds"
          :saving-satellite-id="savingSatellitePayloadId"
          @save="saveSatellitePayload"
          @select-type="selectSatellitePayloadType"
        />
        <StationConstraintStep
          :stations="stations"
          :constraints="downlinkForm.stations"
          @stations-change="updateSelectedStations"
        />
      </section>

          <section class="workbench-column downlink-column" data-test="satellite-station-links" aria-label="下传窗口规划">
            <div class="column-guide">
              <span class="column-guide-step">2</span>
              <div class="column-guide-text">
                <h2>站星链路与下传窗口</h2>
                <p>互选卫星—地面站链路并计算下传弧段，再选择或校验一个观测计算窗口。</p>
              </div>
            </div>
        <SatelliteStationLinkPanel
          :satellites="selectedTaskSatellites"
          :stations="stations"
          :station-constraints="downlinkForm.stations"
          :links="downlinkForm.links"
          @toggle-link="toggleSatelliteStationLink"
        />
        <DownlinkPlanningStep
          v-model:time-range="downlinkTimeRange"
          :form="downlinkForm"
          :loading="pendingAction === 'plan-downlink'"
          @plan="runDownlinkPlan"
        />
        <WindowSelectionPanel
          v-model:custom-window-range="customWindowRange"
          :rows="downlinkRows"
          :single-windows="singleWindowRows"
          :skipped-links="downlinkPlan?.skippedLinks"
          :total-skipped-links="downlinkPlan?.totalSkippedLinks"
          :loading="pendingAction === 'validate-downlink'"
          :selected-window-label="selectedWindowLabel"
          :selected-window-start-time="selectedWindowStartTime"
          :selected-window-end-time="selectedWindowEndTime"
          :format-time="formatTime"
          :format-duration="formatDuration"
          @select-planned="selectPlannedWindow"
          @validate-custom="validateCustomWindow"
        />
      </section>

      <section class="workbench-column observation-column" data-test="target-visibility-planning" aria-label="目标全量观测规划">
            <div class="column-guide">
              <span class="column-guide-step">3</span>
              <div class="column-guide-text">
                <h2>目标筛选与观测规划</h2>
                <p>设置目标筛选条件与观测约束，在已选下传窗口内提交后台任务并查看实时进度。</p>
              </div>
            </div>
        <TargetSelectionStep
          :form="targetForm"
          :belong-options="belongOptions"
          :loading="pendingAction === 'query-targets'"
          :targets="targets"
          :filter-breakdown="targetFilterBreakdown"
          :total="targetTotal"
          :page-number="targetPageNumber"
          :page-size="targetPageSize"
          @query="queryTargets()"
          @page-change="queryTargets"
        />
        <ObservationConstraintPanel
          :satellites="selectedTaskSatellites"
          :draft="observationConstraintDraft"
          :constraints="visibilityForm.observationConstraints"
          @add="addObservationConstraint"
          @remove="removeObservationConstraint"
        />
        <VisibilityPlanningStep
          v-model:mode="visibilityPlanMode"
          :satellites="selectedTaskSatellites"
          :selected-window-label="selectedWindowLabel"
          :selected-window-start-time="selectedWindowStartTime"
          :selected-window-end-time="selectedWindowEndTime"
          :time-range="visibilityTimeRange"
          :form="visibilityForm"
          :plan="visibilityPlan"
          :loading="pendingAction === 'plan-visibility' || pendingAction === 'plan-visibility-task'"
          :task="visibilityTask"
          :task-running="visibilityTaskRunning"
          :task-progress-percent="visibilityTaskProgressPercent"
          :format-time="formatTime"
          :format-duration="formatDuration"
          @plan="runVisibilityPlan"
          @cancel-task="cancelVisibilityTaskPlan"
        />
          </section>
        </section>
      </el-tab-pane>

      <el-tab-pane label="任务结果记录" name="records" lazy>
        <TaskResultRecordsPanel :format-time="formatTime" />
      </el-tab-pane>
      <el-tab-pane label="弹道载荷配置" name="ballistic-payloads" lazy>
        <BallisticPayloadConfigurationView :satellites="payloadSatelliteOptions" />
      </el-tab-pane>
    </el-tabs>
  </main>
</template>
