package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import com.runisys.orbit.spacevisibility.domain.downlink.DownlinkPlanner;
import com.runisys.orbit.spacevisibility.domain.downlink.IlluminationRequirement;
import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationCoordinate;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.IlluminationConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadType;
import com.runisys.orbit.spacevisibility.domain.window.CombinationConstraint;
import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.StationEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlanningFacadeService {

    /**
     * 门面层是请求参数进入计算域、计算结果返回接口层的唯一编排边界。
     * 日志只记录数量、时间范围和约束类型等可审计摘要，不记录 TLE、完整载荷 JSON 或结果原文，
     * 既便于定位性能与筛选问题，也避免日志文件因大字段迅速膨胀。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(PlanningFacadeService.class);
    /** 接口明细设上限，避免少量异常目标使同步响应或异步任务详情无限膨胀。 */
    private static final int MAX_SKIPPED_PAIRS_IN_RESPONSE = 500;
    private static final int MAX_SKIPPED_REASON_LENGTH = 200;

    private final ReferenceDataService referenceDataService;
    private final OrbitStateProviderFactory orbitStateProviderFactory;
    private final PayloadConstraintFactory payloadConstraintFactory;
    private final VisibilityPlanningService visibilityPlanningService;
    private final DownlinkPlanningService downlinkPlanningService;
    private final PlanningTaskService planningTaskService;
    private final VisibilityTaskResultService visibilityTaskResultService;
    private final double defaultPreGuardSec;
    private final double defaultPostGuardSec;
    private final double defaultAllowedGapSec;
    private final double defaultStationMinElevationDeg;
    private final double defaultStationMaxElevationDeg;
    /** 星历围绕任务窗口的默认建议近似天数；页面可按任务覆盖，它只标注风险而不硬筛选。 */
    private final long maxOrbitApproximationDays;
    /** 页面默认显示的 YYDDD 历元下限；实际任务可在前端按次覆盖。 */
    private final String defaultMinTleEpochCode;

    public PlanningFacadeService(ReferenceDataService referenceDataService,
                                 OrbitStateProviderFactory orbitStateProviderFactory,
                                 PayloadConstraintFactory payloadConstraintFactory,
                                 VisibilityPlanningService visibilityPlanningService,
                                 DownlinkPlanningService downlinkPlanningService,
                                 PlanningTaskService planningTaskService,
                                 VisibilityTaskResultService visibilityTaskResultService,
                                 @Value("${space.visibility.downlink.default-pre-guard-sec}") double defaultPreGuardSec,
                                 @Value("${space.visibility.downlink.default-post-guard-sec}") double defaultPostGuardSec,
                                 @Value("${space.visibility.downlink.default-allowed-gap-sec}") double defaultAllowedGapSec,
                                 @Value("${space.visibility.downlink.default-station-min-elevation-deg:10}") double defaultStationMinElevationDeg,
                                 @Value("${space.visibility.downlink.default-station-max-elevation-deg:90}") double defaultStationMaxElevationDeg,
                                 @Value("${space.visibility.orbit.max-approximation-days:14}") long maxOrbitApproximationDays,
                                 @Value("${space.visibility.target-filter.default-min-tle-epoch-code:23340}") String defaultMinTleEpochCode) {
        this.referenceDataService = referenceDataService;
        this.orbitStateProviderFactory = orbitStateProviderFactory;
        this.payloadConstraintFactory = payloadConstraintFactory;
        this.visibilityPlanningService = visibilityPlanningService;
        this.downlinkPlanningService = downlinkPlanningService;
        this.planningTaskService = planningTaskService;
        this.visibilityTaskResultService = visibilityTaskResultService;
        this.defaultPreGuardSec = defaultPreGuardSec;
        this.defaultPostGuardSec = defaultPostGuardSec;
        this.defaultAllowedGapSec = defaultAllowedGapSec;
        this.defaultStationMinElevationDeg = defaultStationMinElevationDeg;
        this.defaultStationMaxElevationDeg = defaultStationMaxElevationDeg;
        if (maxOrbitApproximationDays < 0L) {
            throw new IllegalArgumentException("允许星历近似外推时长不能小于 0 天");
        }
        this.maxOrbitApproximationDays = maxOrbitApproximationDays;
        // 启动即验证默认值，避免用户打开页面后才发现部署参数不能按 YYDDD 解析。
        parseEpochCode(defaultMinTleEpochCode);
        this.defaultMinTleEpochCode = defaultMinTleEpochCode;
    }

    /**
     * 编排一次卫星对地稳定下传计算：仅对用户明确勾选的卫星—地面站关系并行求解稳定建链窗口。
     *
     * @param request 本次计算的时段、步长、地面站临时约束和站星互选关系
     * @return 每条已选站星关系的可建链窗口
     */
    public SpaceApiModels.DownlinkPlanResponse planDownlink(SpaceApiModels.DownlinkPlanRequest request) {
        long startedNanos = System.nanoTime();
        validateTimeRange(request.getStartTime(), request.getEndTime(), request.getCoarseStepSec(), request.getFineStepSec());
        if (request.getStations() == null || request.getStations().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个地面站");
        }
        validateStationRequests(request.getStations());
        List<Long> selectedSatelliteIds = request.getSatelliteIds();
        if (selectedSatelliteIds == null || selectedSatelliteIds.isEmpty() || selectedSatelliteIds.contains(null)) {
            throw new IllegalArgumentException("请至少选择一颗我方卫星");
        }
        if (new HashSet<Long>(selectedSatelliteIds).size() != selectedSatelliteIds.size()) {
            throw new IllegalArgumentException("我方卫星不能重复选择");
        }
        validateSatelliteStationLinks(request.getLinks(), selectedSatelliteIds, request.getStations());
        Map<Long, SatelliteEntity> satellites = referenceDataService.findSatellites(selectedSatelliteIds).stream()
                .collect(Collectors.toMap(SatelliteEntity::getId, satellite -> satellite));
        if (satellites.size() != selectedSatelliteIds.size()) {
            throw new IllegalArgumentException("存在未找到的我方卫星");
        }
        List<String> stationIds = request.getStations().stream()
                .map(SpaceApiModels.StationConstraintRequest::getStationId)
                .collect(Collectors.toList());
        Map<String, StationEntity> stations = referenceDataService.findStations(stationIds).stream()
                .collect(Collectors.toMap(StationEntity::getId, station -> station));
        if (stations.size() != stationIds.size()) {
            throw new IllegalArgumentException("存在未找到的地面站");
        }

        Map<String, SpaceApiModels.StationConstraintRequest> stationRequests = request.getStations().stream()
                .collect(Collectors.toMap(SpaceApiModels.StationConstraintRequest::getStationId, requestItem -> requestItem));
        IlluminationRequirement illuminationRequirement = IlluminationRequirement.fromApiValue(request.getSatelliteIllumination());
        List<DownlinkPair> pairs = new ArrayList<DownlinkPair>();
        for (SpaceApiModels.SatelliteStationLinkRequest link : request.getLinks()) {
            StationEntity station = stations.get(link.getStationId());
            SpaceApiModels.StationConstraintRequest stationRequest = stationRequests.get(link.getStationId());
            GroundStationCoordinate coordinate = stationCoordinate(station);
            SatelliteEntity satellite = satellites.get(link.getSatelliteId());
            pairs.add(new DownlinkPair(link.getSatelliteId(), station.getId(), orbitStateProviderFactory.fromSatellite(satellite),
                    coordinate, request.getStartTime(), request.getEndTime(), request.getCoarseStepSec(), request.getFineStepSec(),
                    // 未在站点或任务中设置时使用与对比基线一致的 10°–87°，不再以 0° 隐式放宽。
                    valueOr(stationRequest.getMinElevationDeg(), station.getMinElevation(), defaultStationMinElevationDeg),
                    valueOr(stationRequest.getMaxElevationDeg(), station.getMaxElevation(), defaultStationMaxElevationDeg),
                    valueOr(request.getPreGuardSec(), defaultPreGuardSec),
                    valueOr(request.getPostGuardSec(), defaultPostGuardSec),
                    valueOr(request.getAllowedGapSec(), defaultAllowedGapSec), illuminationRequirement));
        }
        LOGGER.info("下传规划开始：卫星数={}，地面站数={}，站星关系数={}，时段={}~{}，粗选步长={}s，精选步长={}s，卫星光照约束={}",
                selectedSatelliteIds.size(), stationIds.size(), pairs.size(), request.getStartTime(), request.getEndTime(),
                request.getCoarseStepSec(), request.getFineStepSec(), illuminationRequirement);
        SpaceApiModels.DownlinkPlanResponse response = new SpaceApiModels.DownlinkPlanResponse();
        DownlinkPlanningOutcome planningOutcome = downlinkPlanningService.planWithOutcome(pairs);
        for (DownlinkPairResult result : planningOutcome.getResults()) {
            response.getSatelliteStationWindows().add(new SpaceApiModels.SatelliteStationWindowResponse(
                    result.getSatelliteId(), result.getStationId(), toWindowResponses(request.getStartTime(), result.getWindows())));
        }
        response.getSkippedLinks().addAll(readableSkippedLinks(planningOutcome.getSkippedLinks(), satellites, stations));
        response.setTotalSkippedLinks(planningOutcome.getSkippedLinks().size());
        // 原始链路窗口仍完整返回以便审计；任务选择和自选校验使用不允许断档的连续覆盖并集。
        response.getCoverageWindows().addAll(DownlinkCoverageWindowMerger.merge(response.getSatelliteStationWindows()));
        long rawWindowCount = response.getSatelliteStationWindows().stream()
                .mapToLong(item -> item.getWindows().size()).sum();
        LOGGER.info("下传规划完成：站星关系数={}，原始窗口数={}，连续覆盖窗口数={}，跳过链路数={}，耗时={}ms",
                pairs.size(), rawWindowCount, response.getCoverageWindows().size(), planningOutcome.getSkippedLinks().size(),
                elapsedMillis(startedNanos));
        return response;
    }

    /** 为下传失败链路补全卫星、站点名称，并限制同步接口的异常明细数量。 */
    private List<SpaceApiModels.SkippedLinkResponse> readableSkippedLinks(List<DownlinkSkippedLink> skippedLinks,
                                                                            Map<Long, SatelliteEntity> satellites,
                                                                            Map<String, StationEntity> stations) {
        List<SpaceApiModels.SkippedLinkResponse> readable = new ArrayList<SpaceApiModels.SkippedLinkResponse>();
        skippedLinks.stream()
                .sorted(java.util.Comparator.comparing(DownlinkSkippedLink::getSatelliteId)
                        .thenComparing(DownlinkSkippedLink::getStationId))
                .limit(MAX_SKIPPED_PAIRS_IN_RESPONSE)
                .forEach(skippedLink -> {
                    SatelliteEntity satellite = satellites.get(skippedLink.getSatelliteId());
                    StationEntity station = stations.get(skippedLink.getStationId());
                    readable.add(new SpaceApiModels.SkippedLinkResponse(skippedLink.getSatelliteId(),
                            satellite == null ? "未知卫星" : satellite.getName(), skippedLink.getStationId(),
                            station == null ? "未知地面站" : station.getName(), skippedLink.getStage().name(),
                            truncate(skippedLink.getReason(), MAX_SKIPPED_REASON_LENGTH)));
                });
        return readable;
    }

    /**
     * 计算下传窗口并验证所给起止时段是否被任一已选站星窗口完整覆盖。
     *
     * @param request 自选时段及其下传约束
     * @return 覆盖是否满足的判定和完整规划结果
     */
    public SpaceApiModels.WindowValidationResponse validateWindow(SpaceApiModels.DownlinkPlanRequest request) {
        SpaceApiModels.DownlinkPlanResponse plan = planDownlink(request);
        boolean satisfied = DownlinkWindowValidator.isSatisfied(plan, request.getStartTime(), request.getEndTime());
        LOGGER.info("下传时段校验完成：时段={}~{}，覆盖满足={}",
                request.getStartTime(), request.getEndTime(), satisfied);
        return new SpaceApiModels.WindowValidationResponse(satisfied, plan);
    }

    /**
     * 将 API 筛选请求转交给参考数据服务，返回包含目录字段和质量的目标实体。
     *
     * @param request 目标标识、国籍、质量和名称排除条件
     * @return 符合条件的目标查询结果
     */
    public SpaceApiModels.TargetQueryResponse queryTargets(SpaceApiModels.TargetQueryRequest request) {
        validateTargetFilters(request.getMinWeight(), request.getMaxWeight(),
                request.getEpochStartTime(), request.getEpochEndTime());
        // 任务规划时间与发射日期不参与目标筛选（WinTool 对齐口径，2026-09-08 反馈）：
        // 候选集合只由用户筛选条件决定；星历时效风险以提示输出，不改变候选集合。
        Instant effectiveEpochStart = resolveEpochStart(request.getEpochStartTime(), request.getMinTleEpochCode());
        SpaceApiModels.TargetQueryResponse response = referenceDataService.findTargetPage(request.getTargetIds(),
                request.getBelongs(), request.getMinWeight(), request.getMaxWeight(), effectiveEpochStart,
                request.getEpochEndTime(), request.getExcludeNameRegex(), request.getPageNumber(), request.getPageSize());
        LOGGER.info("目标筛选完成：显式目标数={}，国籍条件数={}，最小质量={}，最大质量={}，历元约束={}，兼容历元上限={}，已设置名称排除规则={}，页码={}，每页记录数={}，命中目标数={}",
                sizeOf(request.getTargetIds()), sizeOf(request.getBelongs()), request.getMinWeight(), request.getMaxWeight(),
                request.getMinTleEpochCode(), request.getEpochEndTime(), hasText(request.getExcludeNameRegex()),
                response.getPageNumber(), response.getPageSize(), response.getTotal());
        return response;
    }

    /** 返回由目标表实时生成的 BELONG_ 筛选字典，供页面避免硬编码国籍选项。 */
    public List<SpaceApiModels.BelongOptionResponse> belongOptions() {
        return referenceDataService.belongOptions();
    }

    /**
     * 编排我方卫星对空间目标的可见性计算，加载轨道与载荷约束后委派并行规划服务求解。
     * 空目标标识集合表示遍历目标库全部目标。
     *
     * @param request 观测星、目标、时间段、粗精选步长及阳照要求
     * @return 每个观测星与目标对应的可见弧段
     */
    public SpaceApiModels.VisibilityPlanResponse planVisibility(SpaceApiModels.VisibilityPlanRequest request) {
        return planVisibility(request, null, null);
    }

    /**
     * 提交一次异步可见性规划任务，复用同步规划的参数校验与目标筛选逻辑。
     * 提交记录与终态结果由任务结果持久化服务落库，持久化失败不影响任务本身。
     *
     * @param request 与同步规划接口一致的规划请求
     * @return 已登记的异步任务，调用方通过任务标识轮询状态和结果
     */
    public PlanningTask submitVisibilityTask(SpaceApiModels.VisibilityPlanRequest request) {
        String taskId = java.util.UUID.randomUUID().toString();
        visibilityTaskResultService.recordSubmitted(taskId, request);
        int observerCount = Math.max(1, request.getObserverSatelliteIds().size());
        return planningTaskService.submit(taskId, currentTask -> planVisibility(request,
                (completedPairs, totalPairs) -> {
                    currentTask.reportProgress(completedPairs, totalPairs);
                    // 进度按筛选后的目标数量展示：配对数 = 目标数 × 观测星数
                    currentTask.reportTargetProgress(completedPairs / observerCount, totalPairs / observerCount);
                }, currentTask::isCancellationRequested), visibilityTaskResultService);
    }

    /**
     * 可见性规划主流程，异步任务通过进度回调和取消检查接入批次级进度与取消能力。
     *
     * @param request 观测星、目标、时间段、粗精选步长及阳照要求
     * @param progressCallback 批次进度回调（已完成组合数、总组合数），同步调用为空
     * @param cancellationCheck 批次边界取消检查，同步调用为空
     * @return 每个观测星与目标对应的可见弧段
     */
    private SpaceApiModels.VisibilityPlanResponse planVisibility(SpaceApiModels.VisibilityPlanRequest request,
                                                                 java.util.function.BiConsumer<Long, Long> progressCallback,
                                                                 java.util.function.Supplier<Boolean> cancellationCheck) {
        long startedNanos = System.nanoTime();
        validateTimeRange(request.getStartTime(), request.getEndTime(), request.getCoarseStepSec(), request.getFineStepSec());
        if (request.getObserverSatelliteIds() == null || request.getObserverSatelliteIds().isEmpty()) {
            throw new IllegalArgumentException("请至少选择一颗观测卫星");
        }
        if (new HashSet<Long>(request.getObserverSatelliteIds()).size() != request.getObserverSatelliteIds().size()) {
            throw new IllegalArgumentException("观测卫星不能重复选择");
        }
        List<SatelliteEntity> satelliteEntities = referenceDataService.findSatellites(request.getObserverSatelliteIds());
        if (satelliteEntities.size() != request.getObserverSatelliteIds().size()) {
            throw new IllegalArgumentException("存在未找到的观测卫星");
        }
        validateExplicitTargetIdsExist(request.getTargetIds());
        long effectiveMaxApproximationDays = resolveMaxApproximationDays(request.getMaxApproximationDays());
        boolean usesTargetSelectionSnapshot = request.getTargetIds() != null && !request.getTargetIds().isEmpty();
        List<TargetEntity> targetEntities;
        if (usesTargetSelectionSnapshot) {
            // 页面已完成筛选时，ID 列表是唯一输入；绝不能再次叠加旧筛选字段。
            targetEntities = referenceDataService.findTargetsByIds(request.getTargetIds());
        } else {
            // 保留直接调用规划接口的兼容路径；前端工作台不会走这里，而是先完成目标筛选。
            validateTargetFilters(request.getMinWeight(), request.getMaxWeight(), request.getEpochStartTime(), request.getEpochEndTime());
            Instant effectiveEpochStart = resolveEpochStart(request.getEpochStartTime(), request.getMinTleEpochCode());
            targetEntities = referenceDataService.findTargetsForPlanning(null, request.getBelongs(),
                    request.getMinWeight(), request.getMaxWeight(), effectiveEpochStart,
                    request.getEpochEndTime(), request.getExcludeNameRegex());
        }
        List<SpaceApiModels.EphemerisWarningResponse> ephemerisWarnings = ephemerisWarnings(targetEntities,
                request.getStartTime(), request.getEndTime(), effectiveMaxApproximationDays);
        SpaceApiModels.TargetFilterBreakdownResponse targetFilterBreakdown;
        if (usesTargetSelectionSnapshot) {
            long selectedTargetCount = targetEntities.size();
            // 这些数量描述的是已确认快照，不再伪装成一次服务端重复筛选的统计。
            targetFilterBreakdown = new SpaceApiModels.TargetFilterBreakdownResponse(selectedTargetCount,
                    selectedTargetCount, selectedTargetCount, selectedTargetCount, ephemerisWarnings.size(),
                    effectiveMaxApproximationDays);
        } else {
            Instant effectiveEpochStart = resolveEpochStart(request.getEpochStartTime(), request.getMinTleEpochCode());
            targetFilterBreakdown = referenceDataService.buildTargetFilterBreakdown(null, request.getBelongs(),
                    request.getMinWeight(), request.getMaxWeight(), effectiveEpochStart, request.getEpochEndTime(),
                    targetEntities.size(), ephemerisWarnings.size(), effectiveMaxApproximationDays);
        }
        IlluminationConstraint illuminationConstraint = toIlluminationConstraint(request.getIlluminationConstraint());
        List<VisibilityObserver> observers = new ArrayList<VisibilityObserver>();
        for (SatelliteEntity satellite : satelliteEntities) {
            // 目标观测固定使用对空载荷。保存/回显 GROUND profile 时会更新 activePayloadType，
            // 但该展示状态绝不能改变目标观测的载荷口径，否则同一任务会因用户查看对地参数而改变结果。
            PayloadConstraint payload = payloadConstraintFactory.parseProfile(satellite.getPayloadJson(), PayloadType.SPACE);
            observers.add(new VisibilityObserver(String.valueOf(satellite.getId()),
                    orbitStateProviderFactory.fromSatellite(satellite), payload));
        }
        List<VisibilityTarget> targets = new ArrayList<VisibilityTarget>();
        List<VisibilitySkippedPair> skippedPairs = new ArrayList<VisibilitySkippedPair>();
        for (TargetEntity target : targetEntities) {
            String targetId = String.valueOf(target.getId());
            try {
                OrbitStateProvider targetOrbit = orbitStateProviderFactory.fromTarget(target);
                // 提交后立即验证规划区间两端，提前隔离不支持外推的坏 TLE，避免在大批扫描末尾才失败。
                targetOrbit.positionAt(request.getStartTime());
                targetOrbit.positionAt(request.getEndTime());
                targets.add(new VisibilityTarget(targetId, targetOrbit));
            } catch (RuntimeException exception) {
                String reason = failureReason(exception);
                for (VisibilityObserver observer : observers) {
                    skippedPairs.add(new VisibilitySkippedPair(observer.getSatelliteId(), targetId,
                            VisibilitySkippedPair.Stage.PRECHECK, reason));
                }
                LOGGER.warn("预检跳过空间目标：目标={}，名称={}，观测星数={}，原因={}",
                        targetId, target.getName(), observers.size(), reason);
            }
        }
        List<SpaceApiModels.ObservationConstraintRequest> requestedConstraints = request.getObservationConstraints() == null
                ? Collections.<SpaceApiModels.ObservationConstraintRequest>emptyList() : request.getObservationConstraints();
        List<CombinationConstraint> constraints = toObservationConstraints(requestedConstraints, request.getObserverSatelliteIds());
        if (constraints.isEmpty()) {
            for (Long observerSatelliteId : request.getObserverSatelliteIds()) {
                constraints.add(new CombinationConstraint(Collections.singletonList(String.valueOf(observerSatelliteId)), 0.0));
            }
        }
        LOGGER.info("目标观测规划开始：目标来源={}，观测星数={}，筛选后目标数={}，星历时效风险数={}，建议近似时长={}天，组合约束数={}，时段={}~{}，粗选步长={}s，精选步长={}s，光照模式={}",
                usesTargetSelectionSnapshot ? "前端筛选结果" : "服务端兼容筛选", observers.size(), targets.size(), targetFilterBreakdown.getEphemerisWarningHit(),
                effectiveMaxApproximationDays, constraints.size(), request.getStartTime(), request.getEndTime(),
                request.getCoarseStepSec(), request.getFineStepSec(), illuminationConstraint.getMode());
        VisibilityPlanningOutcome planningOutcome = visibilityPlanningService.planWithOutcome(
                observers, targets, request.getStartTime(), request.getEndTime(),
                request.getCoarseStepSec(), request.getFineStepSec(), illuminationConstraint,
                progressCallback, cancellationCheck);
        List<VisibilityPairResult> pairResults = planningOutcome.getResults();
        skippedPairs.addAll(planningOutcome.getSkippedPairs());
        List<VisibilityObservationResult> results = new VisibilityConstraintPlanner().calculate(
                request.getStartTime(), pairResults, constraints);
        Map<String, String> satelliteNames = satelliteEntities.stream().collect(Collectors.toMap(
                entity -> String.valueOf(entity.getId()), SatelliteEntity::getName));
        Map<String, String> targetNames = targetEntities.stream().collect(Collectors.toMap(
                entity -> String.valueOf(entity.getId()), TargetEntity::getName));
        Map<String, TargetEntity> targetById = targetEntities.stream().collect(Collectors.toMap(
                entity -> String.valueOf(entity.getId()), entity -> entity));
        List<VisibilityObservationResult> readableResults = new ArrayList<VisibilityObservationResult>();
        for (VisibilityObservationResult result : results) {
            String targetName = requiredName(targetNames, result.getTargetId(), "空间目标");
            List<String> observerSatelliteNames = result.getObserverSatelliteIds().stream()
                    .map(satelliteId -> requiredName(satelliteNames, satelliteId, "我方卫星"))
                    .collect(Collectors.toList());
            VisibilityObservationResult readableResult = new VisibilityObservationResult(result.getTargetId(), targetName,
                    result.getObserverSatelliteIds(), observerSatelliteNames,
                    readableArcs(result.getArcs(), satelliteNames, targetNames));
            // 补充目标目录元数据（NORAD/重量/两行根数），供 WinTool 格式摘要与对账报告使用。
            TargetEntity targetEntity = targetById.get(result.getTargetId());
            if (targetEntity != null) {
                readableResult.setTargetMeta(String.valueOf(targetEntity.getNoradCatId()),
                        targetEntity.getWeight() == null ? null : targetEntity.getWeight().doubleValue(),
                        targetEntity.getFirstOrbit(), targetEntity.getSecondOrbit());
            }
            readableResults.add(readableResult);
        }
        attachConciseSummaries(readableResults, pairResults,
                request.getObserverSatelliteIds(), request.getStartTime());
        List<SpaceApiModels.SkippedPairResponse> allReadableSkippedPairs = readableSkippedPairs(skippedPairs,
                satelliteNames, targetNames, Long.MAX_VALUE);
        List<SpaceApiModels.SkippedPairResponse> readableSkippedPairs = allReadableSkippedPairs.size()
                <= MAX_SKIPPED_PAIRS_IN_RESPONSE ? allReadableSkippedPairs
                : new ArrayList<SpaceApiModels.SkippedPairResponse>(
                        allReadableSkippedPairs.subList(0, MAX_SKIPPED_PAIRS_IN_RESPONSE));
        LOGGER.info("目标观测规划完成：观测星数={}，筛选目标数={}，有效单星组合数={}，输出观测结果数={}，跳过组合数={}，耗时={}ms",
                observers.size(), targets.size(), pairResults.size(), readableResults.size(), skippedPairs.size(),
                elapsedMillis(startedNanos));
        // WinTool 两段式对比报告：仅在调用方显式请求时生成，避免常规调用的响应体膨胀。
        String winToolReport = Boolean.TRUE.equals(request.getWinToolReport())
                ? buildWinToolReport(readableResults, pairResults, satelliteEntities, targetEntities,
                        request.getObserverSatelliteIds())
                : null;
        return new SpaceApiModels.VisibilityPlanResponse(readableResults, readableSkippedPairs, skippedPairs.size(),
                allReadableSkippedPairs, targetFilterBreakdown, ephemerisWarnings, winToolReport);
    }

    /**
     * 生成 WinTool 两段式对比报告（目标+各星观测时段列表、Mission 摘要/详细信息）。
     * 卫星展示字母优先取自名称后缀（如 “SHIYAN 20B (SY-20B)” → B），与对方 A/B/C 星口径一致。
     */
    private String buildWinToolReport(List<VisibilityObservationResult> readableResults,
                                      List<VisibilityPairResult> pairResults,
                                      List<SatelliteEntity> satelliteEntities,
                                      List<TargetEntity> targetEntities,
                                      List<Long> observerOrder) {
        Map<String, String> letters = new LinkedHashMap<String, String>();
        int fallback = 0;
        for (SatelliteEntity satellite : satelliteEntities) {
            String satelliteId = String.valueOf(satellite.getId());
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("([A-Z])\\)\\s*$").matcher(satellite.getName());
            letters.put(satelliteId, matcher.find()
                    ? matcher.group(1)
                    : String.valueOf((char) ('A' + (fallback++))));
        }
        Map<String, WinToolReportFormatter.TargetInfo> targetInfoById =
                new LinkedHashMap<String, WinToolReportFormatter.TargetInfo>();
        for (TargetEntity target : targetEntities) {
            targetInfoById.put(String.valueOf(target.getId()), new WinToolReportFormatter.TargetInfo(
                    target.getName(),
                    target.getWeight() == null ? null : target.getWeight().doubleValue(),
                    target.getFirstOrbit(), target.getSecondOrbit()));
        }
        return WinToolReportFormatter.format(readableResults, pairResults, letters, targetInfoById);
    }

    /**
     * 按 WinTool 简约格式生成每个目标的观测摘要：任务摘要列出各卫星组合的共视总时长，
     * 单星情况列出该星对目标的全部观测时段。时长基于单星原始弧段合并后计算，与约束筛选无关，
     * 用于与 WinTool 结果逐星核对。
     */
    private void attachConciseSummaries(List<VisibilityObservationResult> readableResults,
                                        List<VisibilityPairResult> pairResults,
                                        List<Long> observerOrder,
                                        Instant planningStart) {
        if (readableResults.isEmpty() || observerOrder.isEmpty()) {
            return;
        }
        Map<String, Map<String, List<double[]>>> intervalsByTarget = new LinkedHashMap<>();
        for (VisibilityPairResult pairResult : pairResults) {
            if (pairResult.getArcs().isEmpty()) {
                continue;
            }
            List<double[]> intervals = intervalsByTarget
                    .computeIfAbsent(pairResult.getTargetId(), key -> new LinkedHashMap<String, List<double[]>>())
                    .computeIfAbsent(pairResult.getObserverSatelliteId(), key -> new ArrayList<double[]>());
            for (VisibilityArc arc : pairResult.getArcs()) {
                intervals.add(new double[]{secondsFrom(planningStart, arc.getStartTime()),
                        secondsFrom(planningStart, arc.getEndTime())});
            }
        }
        Map<String, String> summaries = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<double[]>>> entry : intervalsByTarget.entrySet()) {
            summaries.put(entry.getKey(), conciseSummary(entry.getValue(), observerOrder, planningStart));
        }
        for (VisibilityObservationResult result : readableResults) {
            result.setConciseSummary(summaries.get(result.getTargetId()));
        }
    }

    /** 生成单个目标的 WinTool 简约摘要文本（任务摘要 + 各单星观测时段）。 */
    private String conciseSummary(Map<String, List<double[]>> intervalsByObserver,
                                  List<Long> observerOrder,
                                  Instant planningStart) {
        java.time.format.DateTimeFormatter timeFormat =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < observerOrder.size(); index++) {
            labels.add(observerLabel(index));
        }
        Map<Integer, List<double[]>> mergedByMask = new LinkedHashMap<>();
        int observerCount = observerOrder.size();
        for (int index = 0; index < observerCount; index++) {
            mergedByMask.put(1 << index, mergeIntervals(
                    intervalsByObserver.getOrDefault(String.valueOf(observerOrder.get(index)),
                            java.util.Collections.<double[]>emptyList())));
        }
        List<Integer> masks = new ArrayList<>();
        if (observerCount <= 5) {
            for (int mask = 1; mask < (1 << observerCount); mask++) {
                masks.add(mask);
            }
        } else {
            for (int index = 0; index < observerCount; index++) {
                masks.add(1 << index);
            }
            int full = (1 << observerCount) - 1;
            if (!masks.contains(full)) {
                masks.add(full);
            }
        }
        // 与 WinTool 简约版一致：单星在前、两星次之……同成员数内按卫星顺序排列
        masks.sort(java.util.Comparator.comparingInt(Integer::bitCount)
                .thenComparingInt(mask -> mask));
        StringBuilder text = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (int mask : masks) {
            List<double[]> common = null;
            for (int index = 0; index < observerCount; index++) {
                if ((mask & (1 << index)) == 0) {
                    continue;
                }
                common = common == null ? mergedByMask.get(1 << index) : intersect(common, mergedByMask.get(1 << index));
            }
            double total = totalDuration(common);
            if (total > 1e-9) {
                parts.add(subsetLabel(labels, mask) + "(" + String.format("%.3f", total) + ")");
            }
        }
        text.append("任务摘要：").append(String.join(" - ", parts));
        for (int index = 0; index < observerCount; index++) {
            List<double[]> merged = mergedByMask.get(1 << index);
            if (merged.isEmpty()) {
                continue;
            }
            text.append("\n").append(labels.get(index)).append(" 星情况：");
            for (int arcIndex = 0; arcIndex < merged.size(); arcIndex++) {
                double[] interval = merged.get(arcIndex);
                text.append("\n").append(arcIndex + 1).append(":")
                        .append(timeFormat.format(atOffset(planningStart, interval[0])))
                        .append("---")
                        .append(timeFormat.format(atOffset(planningStart, interval[1])))
                        .append("(").append(String.format("%.3f", interval[1] - interval[0])).append("s)");
            }
        }
        return text.toString();
    }

    /** 组合掩码对应的标签，如 A、AB、ABC。 */
    private static String subsetLabel(List<String> labels, int mask) {
        StringBuilder label = new StringBuilder();
        for (int index = 0; index < labels.size(); index++) {
            if ((mask & (1 << index)) != 0) {
                label.append(labels.get(index));
            }
        }
        return label.toString();
    }

    /** 观测星序号转字母标签：0→A、1→B、26 之后用 S27 兜底。 */
    private static String observerLabel(int index) {
        if (index < 26) {
            return String.valueOf((char) ('A' + index));
        }
        return "S" + (index + 1);
    }

    /** 合并重叠或相接的区间，输入乱序输出有序。 */
    private static List<double[]> mergeIntervals(List<double[]> raw) {
        List<double[]> sorted = new ArrayList<double[]>(raw);
        sorted.sort(java.util.Comparator.comparingDouble(interval -> interval[0]));
        List<double[]> merged = new ArrayList<double[]>();
        for (double[] interval : sorted) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                double[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new double[]{interval[0], interval[1]});
            }
        }
        return merged;
    }

    /** 两个有序区间列表的交集。 */
    private static List<double[]> intersect(List<double[]> first, List<double[]> second) {
        List<double[]> result = new ArrayList<double[]>();
        int i = 0;
        int j = 0;
        while (i < first.size() && j < second.size()) {
            double startSec = Math.max(first.get(i)[0], second.get(j)[0]);
            double endSec = Math.min(first.get(i)[1], second.get(j)[1]);
            if (endSec > startSec) {
                result.add(new double[]{startSec, endSec});
            }
            if (first.get(i)[1] < second.get(j)[1]) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    /** 区间总时长（秒）。 */
    private static double totalDuration(List<double[]> intervals) {
        double total = 0.0;
        for (double[] interval : intervals) {
            total += interval[1] - interval[0];
        }
        return total;
    }

    /** 将内部组合级失败补全为可直接展示的名称，并按调用方给出的最大条数截取。 */
    private List<SpaceApiModels.SkippedPairResponse> readableSkippedPairs(List<VisibilitySkippedPair> skippedPairs,
                                                                            Map<String, String> satelliteNames,
                                                                            Map<String, String> targetNames,
                                                                            long maxRecords) {
        List<SpaceApiModels.SkippedPairResponse> readable = new ArrayList<SpaceApiModels.SkippedPairResponse>();
        skippedPairs.stream()
                .sorted(java.util.Comparator.comparing(VisibilitySkippedPair::getStage)
                        .thenComparing(VisibilitySkippedPair::getTargetId)
                        .thenComparing(VisibilitySkippedPair::getObserverSatelliteId))
                .limit(maxRecords)
                .forEach(skippedPair -> readable.add(new SpaceApiModels.SkippedPairResponse(
                        skippedPair.getObserverSatelliteId(),
                        requiredName(satelliteNames, skippedPair.getObserverSatelliteId(), "我方卫星"),
                        skippedPair.getTargetId(),
                        requiredName(targetNames, skippedPair.getTargetId(), "空间目标"),
                        skippedPair.getStage().name(), truncate(skippedPair.getReason(), MAX_SKIPPED_REASON_LENGTH))));
        return readable;
    }

    /** 预检异常和异步扫描异常使用同一份、可展示的根因摘要格式。 */
    private static String failureReason(Throwable exception) {
        return PlanningFailureReasonFormatter.toUserMessage(exception);
    }

    /** 控制对外错误摘要长度，保留根因而不让异常堆栈或大字段撑大响应。 */
    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 将纳秒计时转换为日志易读的毫秒，统一避免各入口自行做不一致的换算。 */
    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** 空集合和空值都按 0 处理，保证摘要日志不会因调用方省略可选条件而失败。 */
    private static int sizeOf(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    /**
     * 仅校验“显式 ID 是否存在”，不能拿任务时间筛选后的数量判断存在性。
     * 例如目标在 2024 年尚未发射时，它确实存在于目录中，只是本次任务不具备传播资格，
     * 不应被错误提示为“空间目标不存在”。
     */
    private void validateExplicitTargetIdsExist(List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return;
        }
        if (targetIds.contains(null)) {
            throw new IllegalArgumentException("空间目标标识无效");
        }
        List<TargetEntity> existingTargets = referenceDataService.findTargets(targetIds, null, null, null, null);
        if (existingTargets.size() != new HashSet<Long>(targetIds).size()) {
            throw new IllegalArgumentException("存在未找到的空间目标");
        }
    }

    /** 日志仅记录是否启用名称排除规则，不记录正则原文，以免不必要地暴露调用方策略。 */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 将接口临时光照组合转换为领域模型；未提供时按不限处理。 */
    private IlluminationConstraint toIlluminationConstraint(
            SpaceApiModels.IlluminationConstraintRequest request) {
        if (request == null) {
            return IlluminationConstraint.from("ANY", Collections.<String>emptyList(), Collections.<String>emptyList());
        }
        return IlluminationConstraint.from(request.getMode(), request.getObserverStates(), request.getTargetStates());
    }

    /** 为领域计算得到的临时标识补全卫星和目标名称，生成可直接交给接口的光照详情。 */
    private List<VisibilityArc> readableArcs(List<VisibilityArc> arcs,
                                              Map<String, String> satelliteNames,
                                              Map<String, String> targetNames) {
        List<VisibilityArc> readable = new ArrayList<VisibilityArc>();
        for (VisibilityArc arc : arcs) {
            List<VisibilityArc.ObserverIllumination> observers = new ArrayList<VisibilityArc.ObserverIllumination>();
            for (VisibilityArc.ObserverIllumination observer : arc.getObserverIlluminations()) {
                observers.add(new VisibilityArc.ObserverIllumination(observer.getSatelliteId(),
                        requiredName(satelliteNames, observer.getSatelliteId(), "我方卫星"),
                        com.runisys.orbit.spacevisibility.domain.payload.IlluminationState.valueOf(observer.getState())));
            }
            VisibilityArc.TargetIllumination target = arc.getTargetIllumination();
            VisibilityArc.TargetIllumination readableTarget = target == null ? null : new VisibilityArc.TargetIllumination(
                    target.getTargetId(), requiredName(targetNames, target.getTargetId(), "空间目标"),
                    com.runisys.orbit.spacevisibility.domain.payload.IlluminationState.valueOf(target.getState()));
            List<VisibilityArc.IlluminationSegment> readableSegments = new ArrayList<VisibilityArc.IlluminationSegment>();
            for (VisibilityArc.IlluminationSegment segment : arc.getIlluminationSegments()) {
                List<VisibilityArc.ObserverIllumination> segmentObservers = new ArrayList<VisibilityArc.ObserverIllumination>();
                for (VisibilityArc.ObserverIllumination observer : segment.getObserverIlluminations()) {
                    segmentObservers.add(new VisibilityArc.ObserverIllumination(observer.getSatelliteId(),
                            requiredName(satelliteNames, observer.getSatelliteId(), "我方卫星"),
                            com.runisys.orbit.spacevisibility.domain.payload.IlluminationState.valueOf(observer.getState())));
                }
                VisibilityArc.TargetIllumination segmentTarget = segment.getTargetIllumination();
                VisibilityArc.TargetIllumination readableSegmentTarget = segmentTarget == null ? null
                        : new VisibilityArc.TargetIllumination(segmentTarget.getTargetId(),
                        requiredName(targetNames, segmentTarget.getTargetId(), "空间目标"),
                        com.runisys.orbit.spacevisibility.domain.payload.IlluminationState.valueOf(segmentTarget.getState()));
                readableSegments.add(new VisibilityArc.IlluminationSegment(segment.getStartTime(), segment.getEndTime(),
                        segmentObservers, readableSegmentTarget));
            }
            // 原始单星窗口在组合规划器中已确定，此处仅补全名称，绝不能重新按父窗口边界生成。
            List<VisibilityArc.SatelliteSegment> readableSatelliteSegments = new ArrayList<VisibilityArc.SatelliteSegment>();
            for (VisibilityArc.SatelliteSegment segment : arc.getSatelliteSegments()) {
                readableSatelliteSegments.add(new VisibilityArc.SatelliteSegment(segment.getSatelliteId(),
                        requiredName(satelliteNames, segment.getSatelliteId(), "我方卫星"),
                        segment.getState(), segment.getStateName(), segment.getStartTime(), segment.getEndTime(),
                        segment.getDurationSec(), segment.getStatistics()));
            }
            readable.add(arc.withIlluminations(observers, readableTarget, readableSegments)
                    .withSatelliteSegments(readableSatelliteSegments));
        }
        return readable;
    }

    /**
     * 从当前规划已加载实体中取得可展示名称；结果包含未加载标识时立即阻止返回错误数据。
     *
     * @param names 已加载实体的标识到名称映射
     * @param id 结果中出现的实体标识
     * @param entityType 用于异常提示的实体类型
     * @return 对应实体名称
     */
    private String requiredName(Map<String, String> names, String id, String entityType) {
        String name = names.get(id);
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalStateException("观测结果包含未加载的" + entityType + ": " + id);
        }
        return name;
    }

    /** 将接口中的单星或多星观测筛选配置转换为领域组合约束并校验成员归属。 */
    private List<CombinationConstraint> toObservationConstraints(
            List<SpaceApiModels.ObservationConstraintRequest> requests, List<Long> observerSatelliteIds) {
        Set<Long> selectedIds = new HashSet<Long>(observerSatelliteIds);
        List<CombinationConstraint> constraints = new ArrayList<CombinationConstraint>();
        for (SpaceApiModels.ObservationConstraintRequest request : requests) {
            if (request == null || request.getSatelliteIds() == null || request.getSatelliteIds().isEmpty()
                    || request.getSatelliteIds().contains(null) || request.getMinDurationSec() < 0.0) {
                throw new IllegalArgumentException("观测卫星组合或最小可见时长无效");
            }
            Set<Long> uniqueIds = new HashSet<Long>(request.getSatelliteIds());
            if (uniqueIds.size() != request.getSatelliteIds().size()) {
                throw new IllegalArgumentException("观测卫星组合标识无效");
            }
            if (!selectedIds.containsAll(uniqueIds)) {
                throw new IllegalArgumentException("观测筛选约束包含未选择的我方卫星");
            }
            constraints.add(new CombinationConstraint(request.getSatelliteIds().stream()
                    .map(String::valueOf).collect(Collectors.toList()), request.getMinDurationSec()));
        }
        // 相同成员与相同门槛的重复约束只计算一次：前端重复添加或历史请求重放
        // 都不应让同一目标输出两条完全相同的结果。
        return dedupeIdenticalConstraints(constraints);
    }

    /** 按成员集合与门槛去重；首个出现的顺序保留，便于结果排序稳定。 */
    static List<CombinationConstraint> dedupeIdenticalConstraints(List<CombinationConstraint> constraints) {
        LinkedHashMap<String, CombinationConstraint> unique = new LinkedHashMap<String, CombinationConstraint>();
        for (CombinationConstraint constraint : constraints) {
            String key = String.join("|", constraint.getSatelliteIds()) + "@" + constraint.getMinDurationSec();
            unique.putIfAbsent(key, constraint);
        }
        return new ArrayList<CombinationConstraint>(unique.values());
    }

    /** 校验目标质量和轨道历元区间，避免无效条件进入全库并行计算。 */
    private void validateTargetFilters(BigDecimal minWeight, BigDecimal maxWeight,
                                       Instant epochStartTime, Instant epochEndTime) {
        if ((minWeight != null && minWeight.signum() < 0)
                || (maxWeight != null && maxWeight.signum() < 0)
                || (minWeight != null && maxWeight != null && minWeight.compareTo(maxWeight) > 0)
                || (epochStartTime != null && epochEndTime != null && epochStartTime.isAfter(epochEndTime))) {
            throw new IllegalArgumentException("目标筛选范围无效");
        }
    }

    /**
     * 输出应用配置中的地面站临时约束默认值，所有数值单位均为秒。
     *
     * @return 前保护、后保护、允许断链及星历近似时长默认值
     */
    public Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("preGuardSec", defaultPreGuardSec);
        defaults.put("postGuardSec", defaultPostGuardSec);
        defaults.put("allowedGapSec", defaultAllowedGapSec);
        // 返回实际生效的部署配置，供前端和外部集成方显示“星历可近似到何时”，避免重复硬编码 14。
        defaults.put("orbitMaxApproximationDays", (double) maxOrbitApproximationDays);
        defaults.put("targetMinTleEpochCode", defaultMinTleEpochCode);
        return defaults;
    }

    /**
     * 将 YYDDD 下限转为严格大于的 UTC 时刻。数据库精度为毫秒，故在日初加一毫秒，
     * 使历元恰好为 23340.000 的记录不会被“严格大于 23340”的规则误保留。
     */
    private Instant resolveEpochStart(Instant legacyEpochStart, String minTleEpochCode) {
        if (!hasText(minTleEpochCode)) {
            return legacyEpochStart;
        }
        Instant epochConstraintStart = parseEpochCode(minTleEpochCode).plus(1L, ChronoUnit.MILLIS);
        return legacyEpochStart == null || epochConstraintStart.isAfter(legacyEpochStart)
                ? epochConstraintStart : legacyEpochStart;
    }

    /** 任务可覆盖部署默认值；负数在进入查询前拒绝，避免把风险阈值误用为筛选表达式。 */
    private long resolveMaxApproximationDays(Long requestValue) {
        long value = requestValue == null ? maxOrbitApproximationDays : requestValue.longValue();
        if (value < 0L) {
            throw new IllegalArgumentException("建议近似传播时长不能小于 0 天");
        }
        return value;
    }

    /** 解析 TLE 年份与年积日：57--99 对应 1957--1999，其余对应 2000--2056。 */
    private static Instant parseEpochCode(String epochCode) {
        if (epochCode == null || !epochCode.trim().matches("\\d{5}")) {
            throw new IllegalArgumentException("历元约束必须为5位YYDDD，例如23340");
        }
        String code = epochCode.trim();
        int shortYear = Integer.parseInt(code.substring(0, 2));
        int year = shortYear >= 57 ? 1900 + shortYear : 2000 + shortYear;
        int dayOfYear = Integer.parseInt(code.substring(2));
        int maximumDay = LocalDate.of(year, 12, 31).getDayOfYear();
        if (dayOfYear < 1 || dayOfYear > maximumDay) {
            throw new IllegalArgumentException("历元约束中的年积日无效");
        }
        return LocalDate.ofYearDay(year, dayOfYear).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /**
     * 构造不阻断计算的时效风险。缺失历元同样保留，由轨道预检决定能否算出窗口；
     * 若预检失败，结果中的中文跳过原因会与此风险记录并列展示。
     */
    private List<SpaceApiModels.EphemerisWarningResponse> ephemerisWarnings(List<TargetEntity> targets,
                                                                               Instant startTime,
                                                                               Instant endTime,
                                                                               long maxApproximationDays) {
        Instant earliest = startTime.minus(maxApproximationDays, ChronoUnit.DAYS);
        Instant latest = endTime.plus(maxApproximationDays, ChronoUnit.DAYS);
        List<SpaceApiModels.EphemerisWarningResponse> warnings = new ArrayList<SpaceApiModels.EphemerisWarningResponse>();
        for (TargetEntity target : targets) {
            if (target.getEpochTime() == null) {
                warnings.add(new SpaceApiModels.EphemerisWarningResponse(String.valueOf(target.getId()), target.getName(),
                        "星历时效风险：目标未提供轨道历元，已继续尝试计算；结果仅供参考。"));
                continue;
            }
            Instant epochTime = target.getEpochTime().toInstant(ZoneOffset.UTC);
            if (epochTime.isBefore(earliest) || epochTime.isAfter(latest)) {
                warnings.add(new SpaceApiModels.EphemerisWarningResponse(String.valueOf(target.getId()), target.getName(),
                        "星历时效风险：轨道历元超出建议近似传播时长（" + maxApproximationDays
                                + "天），已继续尝试计算；结果仅供参考。"));
            }
        }
        return warnings;
    }

    /**
     * 将地面站实体转换为几何计算坐标，并在经纬高缺失时阻止无意义的轨道计算。
     *
     * @param station 已加载的地面站实体
     * @return 用于可见性计算的地面站坐标
     */
    private GroundStationCoordinate stationCoordinate(StationEntity station) {
        if (station.getLongitude() == null || station.getLatitude() == null || station.getHeight() == null) {
            throw new IllegalArgumentException("地面站缺少经纬高参数: " + station.getId());
        }
        return new GroundStationCoordinate(station.getLongitude().doubleValue(), station.getLatitude().doubleValue(),
                station.getHeight().doubleValue());
    }

    /**
     * 将以规划开始时刻为零点的相对秒区间转换为 API 可直接序列化的绝对时刻窗口。
     *
     * @param start 规划时间段开始时刻
     * @param windows 相对秒表示的连续可见区间
     * @return ISO 时刻表示的窗口响应列表
     */
    private List<SpaceApiModels.WindowResponse> toWindowResponses(Instant start, Collection<TimeInterval> windows) {
        List<SpaceApiModels.WindowResponse> response = new ArrayList<SpaceApiModels.WindowResponse>();
        for (TimeInterval window : windows) {
            response.add(new SpaceApiModels.WindowResponse(atOffset(start, window.getStartSec()), atOffset(start, window.getEndSec())));
        }
        return response;
    }

    /**
     * 以纳秒精度将相对秒偏移叠加到规划起点，避免窗口精化结果被截断到整秒。
     *
     * @param start 规划时间段开始时刻
     * @param seconds 相对起点的秒偏移
     * @return 对应的绝对 UTC 时刻
     */
    private Instant atOffset(Instant start, double seconds) {
        return start.plusNanos(Math.round(seconds * 1_000_000_000.0));
    }

    /** 计算时刻相对规划起点的秒偏移，保留纳秒精度。 */
    private double secondsFrom(Instant planningStart, Instant instant) {
        return instant.getEpochSecond() - planningStart.getEpochSecond()
                + (instant.getNano() - planningStart.getNano()) / 1_000_000_000.0;
    }

    /**
     * 对可选数值优先使用请求覆盖值，未填写时返回服务配置默认值。
     *
     * @param value 请求中的可选值
     * @param fallback 配置默认值
     * @return 实际参与计算的数值
     */
    private double valueOr(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 按“请求临时值、地面站长期值、全局默认值”的优先级解析地面站约束参数。
     *
     * @param override 本次请求的覆盖值
     * @param stationValue 地面站表中保存的长期值
     * @param fallback 应用配置中的默认值
     * @return 最终参与计算的数值
     */
    private double valueOr(Double override, BigDecimal stationValue, double fallback) {
        return override == null ? (stationValue == null ? fallback : stationValue.doubleValue()) : override;
    }

    /** 校验用户互选的站星关系，确保每条链路唯一且成员均来自本次已选的卫星和地面站。 */
    private void validateSatelliteStationLinks(List<SpaceApiModels.SatelliteStationLinkRequest> links,
                                               List<Long> selectedSatelliteIds,
                                               List<SpaceApiModels.StationConstraintRequest> stationRequests) {
        Set<Long> selectedIds = new HashSet<Long>(selectedSatelliteIds);
        Set<String> stationIds = stationRequests.stream()
                .map(SpaceApiModels.StationConstraintRequest::getStationId).collect(Collectors.toSet());
        Set<String> uniqueLinks = new HashSet<String>();
        if (links == null || links.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一条卫星—地面站下传关系");
        }
        for (SpaceApiModels.SatelliteStationLinkRequest link : links) {
            if (link == null || link.getSatelliteId() == null || link.getStationId() == null
                    || link.getStationId().trim().isEmpty()) {
                throw new IllegalArgumentException("卫星—地面站下传关系无效");
            }
            String linkKey = link.getSatelliteId() + "@" + link.getStationId();
            if (!uniqueLinks.add(linkKey)) {
                throw new IllegalArgumentException("卫星—地面站下传关系不能重复");
            }
            if (!selectedIds.contains(link.getSatelliteId()) || !stationIds.contains(link.getStationId())) {
                throw new IllegalArgumentException("下传关系包含未选择的卫星或地面站");
            }
        }
    }

    /**
     * 校验地面站临时约束，防止重复站点、缺少站点标识或仰角范围无效的请求进入计算。
     *
     * @param stations 待校验的地面站临时约束列表
     */
    private void validateStationRequests(List<SpaceApiModels.StationConstraintRequest> stations) {
        Set<String> uniqueStationIds = new HashSet<String>();
        for (SpaceApiModels.StationConstraintRequest station : stations) {
            if (station == null || station.getStationId() == null || station.getStationId().trim().isEmpty()) {
                throw new IllegalArgumentException("地面站标识无效");
            }
            if (!uniqueStationIds.add(station.getStationId())) {
                throw new IllegalArgumentException("地面站不能重复选择");
            }
            if (station.getMinElevationDeg() != null && station.getMaxElevationDeg() != null
                    && station.getMinElevationDeg() > station.getMaxElevationDeg()) {
                throw new IllegalArgumentException("地面站仰角范围无效");
            }
        }
    }

    /**
     * 校验统一的规划时间与粗精选步长边界，精选步长不得大于粗选步长。
     *
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选边界步长（秒）
     */
    private void validateTimeRange(Instant start, Instant end, double coarseStepSec, double fineStepSec) {
        if (start == null || end == null || !end.isAfter(start) || !Double.isFinite(coarseStepSec)
                || !Double.isFinite(fineStepSec) || coarseStepSec < 0.001
                || fineStepSec < 0.001 || fineStepSec > coarseStepSec) {
            throw new IllegalArgumentException("规划时间段或步长无效");
        }
    }
}
