package com.runisys.orbit.spacevisibility.api.space;

import com.runisys.orbit.spacevisibility.application.space.PayloadConstraintFactory;
import com.runisys.orbit.spacevisibility.application.space.PlanningFacadeService;
import com.runisys.orbit.spacevisibility.application.space.ReferenceDataService;
import com.runisys.orbit.spacevisibility.application.space.contract.SpacePlanningModels;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadType;
import com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity.StationEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 空间目录、载荷持久化、下传与同步观测的HTTP边界，直接返回既有DTO。
 * 请求使用UTC Instant，卫星/目标ID绑定Long；算法和筛选由应用服务执行，不在此改变模型。
 */
@RestController
@RequestMapping("/api/v1/space")
public class SpaceVisibilityController {

    private static final double METRES_PER_KILOMETRE = 1_000.0;

    private final ReferenceDataService referenceDataService;
    private final PlanningFacadeService planningFacadeService;
    private final PayloadConstraintFactory payloadConstraintFactory;

    public SpaceVisibilityController(ReferenceDataService referenceDataService,
                                     PlanningFacadeService planningFacadeService,
                                     PayloadConstraintFactory payloadConstraintFactory) {
        this.referenceDataService = referenceDataService;
        this.planningFacadeService = planningFacadeService;
        this.payloadConstraintFactory = payloadConstraintFactory;
    }

    /**
     * 提供轻量健康检查，用于前端联调页、部署探针和集成方判断服务是否已启动。
     *
     * @return 固定返回服务存活状态，不访问数据库和轨道计算资源
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("status", "UP");
        return result;
    }

    /**
     * 返回数据库中全部我方卫星及其轨道、载荷和 Space-Track 目录字段。
     *
     * @return 按主键升序排列的我方卫星列表
     */
    @GetMapping("/satellites")
    public List<SatelliteEntity> satellites() {
        return referenceDataService.allSatellites();
    }

    /**
     * 分页返回我方卫星目录字段，不携带两行数、六根数和载荷 JSON 等规划计算明细，供前端目录表格快速浏览。
     *
     * @param pageNumber 页码，从 1 开始
     * @param pageSize 每页记录数，最大为 200
     * @return 包含总数、页码、页大小及本页卫星目录记录的分页结果
     */
    @GetMapping("/satellites/page")
    public SpacePlanningModels.PageResponse<SpacePlanningModels.SatelliteSummaryResponse> satellitePage(
            @RequestParam(defaultValue = "1") long pageNumber,
            @RequestParam(defaultValue = "50") long pageSize,
            @RequestParam(required = false) String keyword) {
        return referenceDataService.satellitePage(pageNumber, pageSize, keyword);
    }

    /**
     * 返回我方卫星选择器所需的轻量字段，避免下拉框加载大量轨道和载荷明细。
     *
     * @return 按主键升序排列的卫星选择项
     */
    @GetMapping("/satellites/options")
    public List<SpacePlanningModels.SatelliteOptionResponse> satelliteOptions() {
        return referenceDataService.allSatelliteOptions();
    }

    /**
     * 读取我方卫星的对空观测载荷长期配置；未配置时返回 configured=false，便于前端引导补充参数。
     *
     * @param satelliteId 我方卫星主键
     * @return 对空观测载荷配置状态及参数
     */
    @GetMapping("/satellites/{satelliteId}/payload")
    public SpacePlanningModels.SpacePayloadConfigurationResponse satellitePayload(@PathVariable Long satelliteId) {
        return toSpacePayloadResponse(referenceDataService.requireSatellite(satelliteId));
    }

    /**
     * 保存我方卫星的对空观测载荷长期配置。目标阳照属于每次规划的临时约束，不在此接口保存。
     *
     * @param satelliteId 我方卫星主键
     * @param request 距离区间、方位/俯仰窗口、跟踪角速度、太阳回避半角和可选启动角速度
     * @return 已保存且可用于目标观测规划的载荷配置
     */
    @PutMapping("/satellites/{satelliteId}/payload")
    public SpacePlanningModels.SpacePayloadConfigurationResponse saveSatellitePayload(
            @PathVariable Long satelliteId,
            @RequestBody SpacePlanningModels.SpacePayloadConfigurationRequest request) {
        referenceDataService.requireSatellite(satelliteId);
        String payloadJson = createSpacePayloadJson(request);
        referenceDataService.updateSatellitePayloadJson(satelliteId, payloadJson);
        return toSpacePayloadResponse(referenceDataService.requireSatellite(satelliteId));
    }

    /**
     * 读取卫星全部载荷配置和当前活动类型。与旧的 /payload 对空兼容接口并存，
     * 新工作台使用本接口在不丢失另一种配置的前提下切换对空/对地载荷。
     */
    @GetMapping("/satellites/{satelliteId}/payload-profiles")
    public SpacePlanningModels.PayloadProfilesResponse satellitePayloadProfiles(@PathVariable Long satelliteId) {
        return toPayloadProfilesResponse(referenceDataService.requireSatellite(satelliteId));
    }

    /**
     * 保存指定类型的载荷并把它设为后续可见性规划的活动载荷。
     * 已保存的另一种类型会保留在 payloadProfiles 容器中，不再发生旧版“切换即覆盖”的数据丢失。
     */
    @PutMapping("/satellites/{satelliteId}/payload-profiles")
    public SpacePlanningModels.PayloadProfilesResponse saveSatellitePayloadProfile(
            @PathVariable Long satelliteId,
            @RequestBody SpacePlanningModels.PayloadProfileRequest request) {
        SatelliteEntity satellite = referenceDataService.requireSatellite(satelliteId);
        PayloadConstraintFactory.PayloadDefinition definition = createPayloadProfileDefinition(request);
        String payloadJson = payloadConstraintFactory.replaceActiveProfile(satellite.getPayloadJson(), definition);
        referenceDataService.updateSatellitePayloadJson(satelliteId, payloadJson);
        return toPayloadProfilesResponse(referenceDataService.requireSatellite(satelliteId));
    }

    /**
     * 返回持久化保存的地面站基础参数；一次规划的临时约束不从该接口读取。
     *
     * @return 按站点标识升序排列的地面站列表
     */
    @GetMapping("/stations")
    public List<StationEntity> stations() {
        return referenceDataService.allStations();
    }

    /**
     * 返回新建地面站临时约束时使用的前保护、后保护和允许断链默认值。
     *
     * @return 以秒为单位的默认规划参数
     */
    @GetMapping("/defaults")
    public Map<String, Object> defaults() {
        return planningFacadeService.defaults();
    }

    /**
     * 根据国籍、质量、轨道历元、目标标识和名称正则排除条件筛选非我方空间目标。
     *
     * @param request 目标筛选条件；空条件表示返回全部目标
     * @return 满足筛选条件且包含完整目录字段的目标列表
     */
    @PostMapping("/targets/query")
    public SpacePlanningModels.TargetQueryResponse queryTargets(@RequestBody SpacePlanningModels.TargetQueryRequest request) {
        return planningFacadeService.queryTargets(request);
    }

    /**
     * 返回当前目标目录中 BELONG_ 字段的实际可选值及数量。前端只能渲染此接口结果，
     * 因而数据重导入或新增编码后无需修改页面国籍常量。
     */
    @GetMapping("/targets/belong-options")
    public List<SpacePlanningModels.BelongOptionResponse> belongOptions() {
        return planningFacadeService.belongOptions();
    }

    /**
     * 按临时地面站约束计算用户互选卫星—地面站链路的独立稳定下传弧段。
     *
     * @param request 本次规划的时间段、步长、地面站和站星互选关系
     * @return 每条站星链路对应的下传窗口
     */
    @PostMapping("/downlink/plan")
    public SpacePlanningModels.DownlinkPlanResponse planDownlink(@RequestBody SpacePlanningModels.DownlinkPlanRequest request) {
        return planningFacadeService.planDownlink(request);
    }

    /**
     * 复用下传窗口规划结果，判断调用方指定的完整时段能否被所需窗口覆盖。
     *
     * @param request 自选时段及其约束条件
     * @return 覆盖判定及用于核对的下传窗口明细
     */
    @PostMapping("/downlink/validate-window")
    public SpacePlanningModels.WindowValidationResponse validateWindow(@RequestBody SpacePlanningModels.DownlinkPlanRequest request) {
        return planningFacadeService.validateWindow(request);
    }

    /**
     * 计算我方观测卫星对指定目标或目标库全部目标的可见弧段。
     *
     * @param request 观测星、目标、时间段、粗精选步长与光照组合临时条件
     * @return 按观测星和目标分组的可见弧段结果
     */
    @PostMapping("/visibility/plan")
    public SpacePlanningModels.VisibilityPlanResponse planVisibility(@RequestBody SpacePlanningModels.VisibilityPlanRequest request) {
        return planningFacadeService.planVisibility(request);
    }

    /**
     * 将持久化载荷 JSON 转换为对外接口模型，并区分未配置、非对空配置与无效配置。
     * 返回值同时给出公里和米：公里是新界面的业务输入单位，米用于一个发布周期内的旧集成方兼容。
     */
    private SpacePlanningModels.SpacePayloadConfigurationResponse toSpacePayloadResponse(SatelliteEntity satellite) {
        PayloadConstraintFactory.PayloadDefinition definition = payloadConstraintFactory.readDefinition(satellite.getPayloadJson());
        if (definition == null || !PayloadType.SPACE.name().equalsIgnoreCase(definition.getPayloadType())) {
            return SpacePlanningModels.SpacePayloadConfigurationResponse.unconfigured();
        }
        PayloadConstraint constraint = payloadConstraintFactory.parse(satellite.getPayloadJson());
        return SpacePlanningModels.SpacePayloadConfigurationResponse.configured(
                constraint.getMinDetectRangeM() / METRES_PER_KILOMETRE,
                constraint.getMaxDetectRangeM() / METRES_PER_KILOMETRE,
                constraint.getMinDetectRangeM(), constraint.getMaxDetectRangeM(),
                constraint.getAzimuthMinDeg(), constraint.getAzimuthMaxDeg(),
                constraint.getElevationMinDeg(), constraint.getElevationMaxDeg(),
                definition.getAngleRate(), constraint.getSunAvoidHalfAngleDeg(),
                definition.getStartupAngleRate());
    }

    /**
     * 兼容新旧距离字段并在进入领域层前统一为米。
     *
     * <p>不能允许同一请求混用公里和米，否则值为 1,000 的来源无法判定且会产生不可审计的
     * 双重换算。新前端只发送公里字段；旧集成方仍可发送明确命名的米字段。</p>
     */
    private String createSpacePayloadJson(SpacePlanningModels.SpacePayloadConfigurationRequest request) {
        boolean hasKilometreValue = request.getMinDistanceKm() != null || request.getMaxDistanceKm() != null;
        boolean hasMetreValue = request.getMinDistanceM() != null || request.getMaxDistanceM() != null;
        if (hasKilometreValue && hasMetreValue) {
            throw new IllegalArgumentException("探测距离不能同时使用公里和米字段");
        }
        Double minDistanceM = hasKilometreValue ? kilometresToMetres(request.getMinDistanceKm()) : request.getMinDistanceM();
        Double maxDistanceM = hasKilometreValue ? kilometresToMetres(request.getMaxDistanceKm()) : request.getMaxDistanceM();
        return payloadConstraintFactory.createSpacePayloadJson(minDistanceM, maxDistanceM,
                request.getAzimuthMinDeg(), request.getAzimuthMaxDeg(),
                request.getElevationMinDeg(), request.getElevationMaxDeg(), request.getAngleRateDegPerSec(),
                request.getSunAvoidHalfAngleDeg(), request.getStartupAngleRateDegPerSec());
    }

    /** 保留空值供参数工厂生成统一的“必须为有效数值”错误，避免空值提前触发空指针。 */
    private Double kilometresToMetres(Double distanceKm) {
        return distanceKm == null ? null : distanceKm * METRES_PER_KILOMETRE;
    }

    /** 将活动载荷和每一种已保存配置映射为前端可直接回填的响应模型。 */
    private SpacePlanningModels.PayloadProfilesResponse toPayloadProfilesResponse(SatelliteEntity satellite) {
        PayloadConstraintFactory.PayloadProfiles profiles = payloadConstraintFactory.readProfiles(satellite.getPayloadJson());
        List<SpacePlanningModels.PayloadProfileResponse> responseProfiles = new java.util.ArrayList<SpacePlanningModels.PayloadProfileResponse>();
        for (PayloadConstraintFactory.PayloadDefinition definition : profiles.getProfiles().values()) {
            responseProfiles.add(toPayloadProfileResponse(definition));
        }
        return new SpacePlanningModels.PayloadProfilesResponse(profiles.getActivePayloadType(), responseProfiles);
    }

    /**
     * 将单一持久化定义转为公里业务单位，避免前端接触内部米制数组。
     * 只有缺少标准距离字段的历史对地定义回显旧锥角字段；所有标准配置统一回显公共字段。
     */
    private SpacePlanningModels.PayloadProfileResponse toPayloadProfileResponse(PayloadConstraintFactory.PayloadDefinition definition) {
        PayloadConstraint constraint = payloadConstraintFactory.parse(payloadConstraintFactory.replaceActiveProfile(null, definition));
        if (constraint.isLegacyGroundCone()) {
            return new SpacePlanningModels.PayloadProfileResponse(PayloadType.GROUND.name(), true, null, null,
                    null, null, null, null, null, null, null,
                    definition.getSensorFullConeAngleDeg(), definition.getMaxDetectRangeKm(), definition.getSunExclusionAngleDeg());
        }
        return new SpacePlanningModels.PayloadProfileResponse(constraint.getPayloadType().name(), false,
                constraint.getMinDetectRangeM() / METRES_PER_KILOMETRE,
                constraint.getMaxDetectRangeM() / METRES_PER_KILOMETRE,
                constraint.getAzimuthMinDeg(), constraint.getAzimuthMaxDeg(),
                constraint.getElevationMinDeg(), constraint.getElevationMaxDeg(),
                definition.getAngleRate(), constraint.getSunAvoidHalfAngleDeg(),
                definition.getStartupAngleRate(), null, null, null);
    }

    /**
     * 为统一保存接口创建被激活的载荷定义。对空和对地距离均按公里提交，并在这里转为米；
     * 之后由同一个领域工厂校验，保证两种载荷不会再使用不同的字段语义。
     */
    private PayloadConstraintFactory.PayloadDefinition createPayloadProfileDefinition(
            SpacePlanningModels.PayloadProfileRequest request) {
        String payloadType = request.getPayloadType() == null ? "" : request.getPayloadType().trim().toUpperCase();
        if (PayloadType.SPACE.name().equals(payloadType)) {
            String payloadJson = payloadConstraintFactory.createSpacePayloadJson(
                    kilometresToMetres(request.getMinDistanceKm()), kilometresToMetres(request.getMaxDistanceKm()),
                    request.getAzimuthMinDeg(), request.getAzimuthMaxDeg(), request.getElevationMinDeg(),
                    request.getElevationMaxDeg(), request.getAngleRateDegPerSec(), request.getSunAvoidHalfAngleDeg(),
                    request.getStartupAngleRateDegPerSec());
            return payloadConstraintFactory.readDefinition(payloadJson);
        }
        if (PayloadType.GROUND.name().equals(payloadType)) {
            String payloadJson = payloadConstraintFactory.createGroundPayloadJson(
                    kilometresToMetres(request.getMinDistanceKm()), kilometresToMetres(request.getMaxDistanceKm()),
                    request.getAzimuthMinDeg(), request.getAzimuthMaxDeg(), request.getElevationMinDeg(),
                    request.getElevationMaxDeg(), request.getAngleRateDegPerSec(), request.getSunAvoidHalfAngleDeg(),
                    request.getStartupAngleRateDegPerSec());
            return payloadConstraintFactory.readDefinition(payloadJson);
        }
        throw new IllegalArgumentException("载荷类型仅支持 SPACE 或 GROUND");
    }
}
