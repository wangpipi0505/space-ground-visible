package com.runisys.orbit.spacevisibility.api;

import com.runisys.orbit.spacevisibility.application.VisibilityObservationResult;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.VisibilityTaskResultEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SpaceApiModels {

    private SpaceApiModels() {
    }

    /**
     * 对空观测载荷的长期配置请求。
     *
     * <p>新界面使用公里字段，匹配规划人员填写的业务量纲；米字段保留一个发布周期，
     * 仅用于兼容已有集成方。服务端会拒绝同一请求同时携带两种单位，避免二次换算。</p>
     */
    public static class SpacePayloadConfigurationRequest {
        private Double minDistanceKm;
        private Double maxDistanceKm;
        /** @deprecated 请改用 {@link #minDistanceKm}，该字段始终按米解释。 */
        private Double minDistanceM;
        /** @deprecated 请改用 {@link #maxDistanceKm}，该字段始终按米解释。 */
        private Double maxDistanceM;
        private Double azimuthMinDeg;
        private Double azimuthMaxDeg;
        private Double elevationMinDeg;
        private Double elevationMaxDeg;
        private Double angleRateDegPerSec;
        private Double sunAvoidHalfAngleDeg;
        private Double startupAngleRateDegPerSec;

        public Double getMinDistanceKm() { return minDistanceKm; }
        public void setMinDistanceKm(Double minDistanceKm) { this.minDistanceKm = minDistanceKm; }
        public Double getMaxDistanceKm() { return maxDistanceKm; }
        public void setMaxDistanceKm(Double maxDistanceKm) { this.maxDistanceKm = maxDistanceKm; }
        public Double getMinDistanceM() { return minDistanceM; }
        public void setMinDistanceM(Double minDistanceM) { this.minDistanceM = minDistanceM; }
        public Double getMaxDistanceM() { return maxDistanceM; }
        public void setMaxDistanceM(Double maxDistanceM) { this.maxDistanceM = maxDistanceM; }
        public Double getAzimuthMinDeg() { return azimuthMinDeg; }
        public void setAzimuthMinDeg(Double azimuthMinDeg) { this.azimuthMinDeg = azimuthMinDeg; }
        public Double getAzimuthMaxDeg() { return azimuthMaxDeg; }
        public void setAzimuthMaxDeg(Double azimuthMaxDeg) { this.azimuthMaxDeg = azimuthMaxDeg; }
        public Double getElevationMinDeg() { return elevationMinDeg; }
        public void setElevationMinDeg(Double elevationMinDeg) { this.elevationMinDeg = elevationMinDeg; }
        public Double getElevationMaxDeg() { return elevationMaxDeg; }
        public void setElevationMaxDeg(Double elevationMaxDeg) { this.elevationMaxDeg = elevationMaxDeg; }
        public Double getAngleRateDegPerSec() { return angleRateDegPerSec; }
        public void setAngleRateDegPerSec(Double angleRateDegPerSec) { this.angleRateDegPerSec = angleRateDegPerSec; }
        public Double getSunAvoidHalfAngleDeg() { return sunAvoidHalfAngleDeg; }
        public void setSunAvoidHalfAngleDeg(Double sunAvoidHalfAngleDeg) { this.sunAvoidHalfAngleDeg = sunAvoidHalfAngleDeg; }
        public Double getStartupAngleRateDegPerSec() { return startupAngleRateDegPerSec; }
        public void setStartupAngleRateDegPerSec(Double startupAngleRateDegPerSec) { this.startupAngleRateDegPerSec = startupAngleRateDegPerSec; }
    }

    /** 对空观测载荷配置读取结果；未配置时仅返回 configured=false。 */
    public static class SpacePayloadConfigurationResponse {
        private final boolean configured;
        private final String payloadType;
        private final Double minDistanceKm;
        private final Double maxDistanceKm;
        private final Double minDistanceM;
        private final Double maxDistanceM;
        private final Double azimuthMinDeg;
        private final Double azimuthMaxDeg;
        private final Double elevationMinDeg;
        private final Double elevationMaxDeg;
        private final Double angleRateDegPerSec;
        private final Double sunAvoidHalfAngleDeg;
        private final Double startupAngleRateDegPerSec;

        private SpacePayloadConfigurationResponse(boolean configured,
                                                  String payloadType,
                                                  Double minDistanceKm,
                                                  Double maxDistanceKm,
                                                  Double minDistanceM,
                                                  Double maxDistanceM,
                                                  Double azimuthMinDeg,
                                                  Double azimuthMaxDeg,
                                                  Double elevationMinDeg,
                                                  Double elevationMaxDeg,
                                                  Double angleRateDegPerSec,
                                                  Double sunAvoidHalfAngleDeg,
                                                  Double startupAngleRateDegPerSec) {
            this.configured = configured;
            this.payloadType = payloadType;
            this.minDistanceKm = minDistanceKm;
            this.maxDistanceKm = maxDistanceKm;
            this.minDistanceM = minDistanceM;
            this.maxDistanceM = maxDistanceM;
            this.azimuthMinDeg = azimuthMinDeg;
            this.azimuthMaxDeg = azimuthMaxDeg;
            this.elevationMinDeg = elevationMinDeg;
            this.elevationMaxDeg = elevationMaxDeg;
            this.angleRateDegPerSec = angleRateDegPerSec;
            this.sunAvoidHalfAngleDeg = sunAvoidHalfAngleDeg;
            this.startupAngleRateDegPerSec = startupAngleRateDegPerSec;
        }

        public static SpacePayloadConfigurationResponse unconfigured() {
            return new SpacePayloadConfigurationResponse(false, "SPACE", null, null, null, null, null, null,
                    null, null, null, null, null);
        }

        public static SpacePayloadConfigurationResponse configured(Double minDistanceKm,
                                                                   Double maxDistanceKm,
                                                                   Double minDistanceM,
                                                                   Double maxDistanceM,
                                                                   Double azimuthMinDeg,
                                                                   Double azimuthMaxDeg,
                                                                   Double elevationMinDeg,
                                                                   Double elevationMaxDeg,
                                                                   Double angleRateDegPerSec,
                                                                   Double sunAvoidHalfAngleDeg,
                                                                   Double startupAngleRateDegPerSec) {
            return new SpacePayloadConfigurationResponse(true, "SPACE", minDistanceKm, maxDistanceKm, minDistanceM, maxDistanceM,
                    azimuthMinDeg, azimuthMaxDeg, elevationMinDeg, elevationMaxDeg,
                    angleRateDegPerSec, sunAvoidHalfAngleDeg, startupAngleRateDegPerSec);
        }

        public boolean isConfigured() { return configured; }
        public String getPayloadType() { return payloadType; }
        public Double getMinDistanceKm() { return minDistanceKm; }
        public Double getMaxDistanceKm() { return maxDistanceKm; }
        public Double getMinDistanceM() { return minDistanceM; }
        public Double getMaxDistanceM() { return maxDistanceM; }
        public Double getAzimuthMinDeg() { return azimuthMinDeg; }
        public Double getAzimuthMaxDeg() { return azimuthMaxDeg; }
        public Double getElevationMinDeg() { return elevationMinDeg; }
        public Double getElevationMaxDeg() { return elevationMaxDeg; }
        public Double getAngleRateDegPerSec() { return angleRateDegPerSec; }
        public Double getSunAvoidHalfAngleDeg() { return sunAvoidHalfAngleDeg; }
        public Double getStartupAngleRateDegPerSec() { return startupAngleRateDegPerSec; }
    }

    /**
     * 一颗卫星的全部长期载荷配置及当前参与规划的活动类型。
     * 配置按类型独立保存，切换对空/对地不会覆盖另一类已校验参数。
     */
    public static class PayloadProfilesResponse {
        private final String activePayloadType;
        private final List<PayloadProfileResponse> profiles;

        public PayloadProfilesResponse(String activePayloadType, List<PayloadProfileResponse> profiles) {
            this.activePayloadType = activePayloadType;
            this.profiles = profiles;
        }

        public String getActivePayloadType() { return activePayloadType; }
        public List<PayloadProfileResponse> getProfiles() { return profiles; }
    }

    /**
     * 用于保存对空或对地载荷的统一请求。两类载荷均使用同一组标准字段；
     * 三个旧锥角字段仅用于回显未迁移的历史记录，不能用于创建新的对地配置。
     */
    public static class PayloadProfileRequest {
        private String payloadType;
        private Double minDistanceKm;
        private Double maxDistanceKm;
        private Double azimuthMinDeg;
        private Double azimuthMaxDeg;
        private Double elevationMinDeg;
        private Double elevationMaxDeg;
        private Double angleRateDegPerSec;
        private Double sunAvoidHalfAngleDeg;
        private Double startupAngleRateDegPerSec;
        private Double sensorFullConeAngleDeg;
        private Double maxDetectRangeKm;
        private Double sunExclusionAngleDeg;

        public String getPayloadType() { return payloadType; }
        public void setPayloadType(String payloadType) { this.payloadType = payloadType; }
        public Double getMinDistanceKm() { return minDistanceKm; }
        public void setMinDistanceKm(Double minDistanceKm) { this.minDistanceKm = minDistanceKm; }
        public Double getMaxDistanceKm() { return maxDistanceKm; }
        public void setMaxDistanceKm(Double maxDistanceKm) { this.maxDistanceKm = maxDistanceKm; }
        public Double getAzimuthMinDeg() { return azimuthMinDeg; }
        public void setAzimuthMinDeg(Double azimuthMinDeg) { this.azimuthMinDeg = azimuthMinDeg; }
        public Double getAzimuthMaxDeg() { return azimuthMaxDeg; }
        public void setAzimuthMaxDeg(Double azimuthMaxDeg) { this.azimuthMaxDeg = azimuthMaxDeg; }
        public Double getElevationMinDeg() { return elevationMinDeg; }
        public void setElevationMinDeg(Double elevationMinDeg) { this.elevationMinDeg = elevationMinDeg; }
        public Double getElevationMaxDeg() { return elevationMaxDeg; }
        public void setElevationMaxDeg(Double elevationMaxDeg) { this.elevationMaxDeg = elevationMaxDeg; }
        public Double getAngleRateDegPerSec() { return angleRateDegPerSec; }
        public void setAngleRateDegPerSec(Double angleRateDegPerSec) { this.angleRateDegPerSec = angleRateDegPerSec; }
        public Double getSunAvoidHalfAngleDeg() { return sunAvoidHalfAngleDeg; }
        public void setSunAvoidHalfAngleDeg(Double sunAvoidHalfAngleDeg) { this.sunAvoidHalfAngleDeg = sunAvoidHalfAngleDeg; }
        public Double getStartupAngleRateDegPerSec() { return startupAngleRateDegPerSec; }
        public void setStartupAngleRateDegPerSec(Double startupAngleRateDegPerSec) { this.startupAngleRateDegPerSec = startupAngleRateDegPerSec; }
        public Double getSensorFullConeAngleDeg() { return sensorFullConeAngleDeg; }
        public void setSensorFullConeAngleDeg(Double sensorFullConeAngleDeg) { this.sensorFullConeAngleDeg = sensorFullConeAngleDeg; }
        public Double getMaxDetectRangeKm() { return maxDetectRangeKm; }
        public void setMaxDetectRangeKm(Double maxDetectRangeKm) { this.maxDetectRangeKm = maxDetectRangeKm; }
        public Double getSunExclusionAngleDeg() { return sunExclusionAngleDeg; }
        public void setSunExclusionAngleDeg(Double sunExclusionAngleDeg) { this.sunExclusionAngleDeg = sunExclusionAngleDeg; }
    }

    /** 单一载荷配置的读取模型；与请求同名字段便于前端切换时直接回填。 */
    public static class PayloadProfileResponse {
        private final String payloadType;
        /** true 表示来自历史天底锥角配置，前端需要提示用户按统一字段重新保存。 */
        private final boolean legacyGroundProfile;
        private final Double minDistanceKm;
        private final Double maxDistanceKm;
        private final Double azimuthMinDeg;
        private final Double azimuthMaxDeg;
        private final Double elevationMinDeg;
        private final Double elevationMaxDeg;
        private final Double angleRateDegPerSec;
        private final Double sunAvoidHalfAngleDeg;
        private final Double startupAngleRateDegPerSec;
        private final Double sensorFullConeAngleDeg;
        private final Double maxDetectRangeKm;
        private final Double sunExclusionAngleDeg;

        public PayloadProfileResponse(String payloadType, boolean legacyGroundProfile,
                                      Double minDistanceKm, Double maxDistanceKm,
                                      Double azimuthMinDeg, Double azimuthMaxDeg, Double elevationMinDeg,
                                      Double elevationMaxDeg, Double angleRateDegPerSec,
                                      Double sunAvoidHalfAngleDeg, Double startupAngleRateDegPerSec,
                                      Double sensorFullConeAngleDeg, Double maxDetectRangeKm,
                                      Double sunExclusionAngleDeg) {
            this.payloadType = payloadType;
            this.legacyGroundProfile = legacyGroundProfile;
            this.minDistanceKm = minDistanceKm;
            this.maxDistanceKm = maxDistanceKm;
            this.azimuthMinDeg = azimuthMinDeg;
            this.azimuthMaxDeg = azimuthMaxDeg;
            this.elevationMinDeg = elevationMinDeg;
            this.elevationMaxDeg = elevationMaxDeg;
            this.angleRateDegPerSec = angleRateDegPerSec;
            this.sunAvoidHalfAngleDeg = sunAvoidHalfAngleDeg;
            this.startupAngleRateDegPerSec = startupAngleRateDegPerSec;
            this.sensorFullConeAngleDeg = sensorFullConeAngleDeg;
            this.maxDetectRangeKm = maxDetectRangeKm;
            this.sunExclusionAngleDeg = sunExclusionAngleDeg;
        }

        public String getPayloadType() { return payloadType; }
        public boolean isLegacyGroundProfile() { return legacyGroundProfile; }
        public Double getMinDistanceKm() { return minDistanceKm; }
        public Double getMaxDistanceKm() { return maxDistanceKm; }
        public Double getAzimuthMinDeg() { return azimuthMinDeg; }
        public Double getAzimuthMaxDeg() { return azimuthMaxDeg; }
        public Double getElevationMinDeg() { return elevationMinDeg; }
        public Double getElevationMaxDeg() { return elevationMaxDeg; }
        public Double getAngleRateDegPerSec() { return angleRateDegPerSec; }
        public Double getSunAvoidHalfAngleDeg() { return sunAvoidHalfAngleDeg; }
        public Double getStartupAngleRateDegPerSec() { return startupAngleRateDegPerSec; }
        public Double getSensorFullConeAngleDeg() { return sensorFullConeAngleDeg; }
        public Double getMaxDetectRangeKm() { return maxDetectRangeKm; }
        public Double getSunExclusionAngleDeg() { return sunExclusionAngleDeg; }
    }

    /** 异步可见性规划任务的状态读取结果，不包含规划结果明细。 */
    public static class PlanningTaskStatusResponse {
        private final String taskId;
        private final String status;
        private final long totalPairs;
        private final long completedPairs;
        private final long totalTargets;
        private final long completedTargets;
        private final String errorMessage;
        private final Instant createdAt;
        private final Instant completedAt;

        public PlanningTaskStatusResponse(String taskId,
                                          String status,
                                          long totalPairs,
                                          long completedPairs,
                                          long totalTargets,
                                          long completedTargets,
                                          String errorMessage,
                                          Instant createdAt,
                                          Instant completedAt) {
            this.taskId = taskId;
            this.status = status;
            this.totalPairs = totalPairs;
            this.completedPairs = completedPairs;
            this.totalTargets = totalTargets;
            this.completedTargets = completedTargets;
            this.errorMessage = errorMessage;
            this.createdAt = createdAt;
            this.completedAt = completedAt;
        }

        public String getTaskId() { return taskId; }
        public String getStatus() { return status; }
        public long getTotalPairs() { return totalPairs; }
        public long getCompletedPairs() { return completedPairs; }
        public long getTotalTargets() { return totalTargets; }
        public long getCompletedTargets() { return completedTargets; }
        public String getErrorMessage() { return errorMessage; }

        /** @return 以 UTC 三位毫秒序列化的任务创建时刻。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getCreatedAt() { return createdAt; }

        /** @return 以 UTC 三位毫秒序列化的任务完成时刻；未完成时为 null。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getCompletedAt() { return completedAt; }
    }

    /** 异步可见性规划任务持久化记录的列表项，不包含请求与结果 JSON 大字段。 */
    public static class VisibilityTaskResultSummaryResponse {
        /*
         * 任务结果表采用雪花 ID，数值会超过 JavaScript Number 的 53 位安全整数上限。
         * 若直接序列化为 JSON number，浏览器解析列表后会静默舍入 ID，进而用错误的
         * 路径参数请求明细并得到 404。因此仅在 API 边界转为十进制字符串；数据库、
         * Service 和 Controller 路径参数仍保留 Long，避免改变持久化语义。
         */
        private final String id;
        private final String taskId;
        private final String taskType;
        private final String status;
        private final Long totalPairs;
        private final Long completedPairs;
        private final String errorMessage;
        private final String remark;
        private final LocalDateTime createTime;
        private final LocalDateTime completeTime;

        public VisibilityTaskResultSummaryResponse(VisibilityTaskResultEntity entity) {
            this.id = entity.getId() == null ? null : String.valueOf(entity.getId());
            this.taskId = entity.getTaskId();
            this.taskType = entity.getTaskType();
            this.status = entity.getStatus();
            this.totalPairs = entity.getTotalPairs();
            this.completedPairs = entity.getCompletedPairs();
            this.errorMessage = entity.getErrorMessage();
            this.remark = entity.getRemark();
            this.createTime = entity.getCreateTime();
            this.completeTime = entity.getCompleteTime();
        }

        /** @return 十进制字符串主键，保证浏览器端传递时不发生精度丢失。 */
        public String getId() { return id; }
        public String getTaskId() { return taskId; }
        public String getTaskType() { return taskType; }
        public String getStatus() { return status; }
        public Long getTotalPairs() { return totalPairs; }
        public Long getCompletedPairs() { return completedPairs; }
        public String getErrorMessage() { return errorMessage; }
        public String getRemark() { return remark; }

        /** @return 任务提交时刻（UTC，无时区后缀）。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        public LocalDateTime getCreateTime() { return createTime; }

        /** @return 任务进入终态的时刻（UTC，无时区后缀）；未终结时为 null。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        public LocalDateTime getCompleteTime() { return completeTime; }
    }

    /** 异步可见性规划任务持久化记录的全字段明细，包含请求与结果 JSON 原文。 */
    public static class VisibilityTaskResultDetailResponse extends VisibilityTaskResultSummaryResponse {
        private final String requestJson;
        private final String resultJson;
        private final String skippedPairsJson;
        /** 用户在结果列表勾选目标后“确认选择”提交的明细原文;未做过确认的历史记录为 null。 */
        private final String selectionJson;

        public VisibilityTaskResultDetailResponse(VisibilityTaskResultEntity entity) {
            super(entity);
            this.requestJson = entity.getRequestJson();
            this.resultJson = entity.getResultJson();
            this.skippedPairsJson = entity.getSkippedPairsJson();
            this.selectionJson = entity.getSelectionJson();
        }

        /** @return 提交任务时的 VisibilityPlanRequest JSON 原文。 */
        public String getRequestJson() { return requestJson; }

        /** @return 任务成功时的 VisibilityPlanResponse JSON 原文；未成功时为 null。 */
        public String getResultJson() { return resultJson; }

        /** @return 未截断的跳过组合 JSON 原文；仅成功任务且存在跳过组合时有值。 */
        public String getSkippedPairsJson() { return skippedPairsJson; }

        /** @return 确认选择明细 JSON 原文；未做过确认的历史记录为 null。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String getSelectionJson() { return selectionJson; }
    }

    /** 更新异步可见性规划任务持久化记录备注的请求体。 */
    public static class VisibilityTaskResultRemarkRequest {
        private String remark;

        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    /** “确认选择”请求体:勾选目标的观测明细 + 前端合并出的完整时段。 */
    public static class VisibilityTaskResultSelectionRequest {
        /** 规划时间段。 */
        private com.fasterxml.jackson.databind.JsonNode planningWindow;
        /** 所有勾选弧段的并集(前端合并),含 segments 数组与 totalDurationSec。 */
        private com.fasterxml.jackson.databind.JsonNode mergedTimeRange;
        /** 逐目标勾选明细,每个元素含目标信息、共视时段与逐星观测统计。 */
        private com.fasterxml.jackson.databind.JsonNode selections;
        /** 可选备注。 */
        private String remark;

        public com.fasterxml.jackson.databind.JsonNode getPlanningWindow() { return planningWindow; }
        public void setPlanningWindow(com.fasterxml.jackson.databind.JsonNode planningWindow) { this.planningWindow = planningWindow; }
        public com.fasterxml.jackson.databind.JsonNode getMergedTimeRange() { return mergedTimeRange; }
        public void setMergedTimeRange(com.fasterxml.jackson.databind.JsonNode mergedTimeRange) { this.mergedTimeRange = mergedTimeRange; }
        public com.fasterxml.jackson.databind.JsonNode getSelections() { return selections; }
        public void setSelections(com.fasterxml.jackson.databind.JsonNode selections) { this.selections = selections; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    /** “确认选择”回执:后端已收到的凭据信息。 */
    public static class VisibilityTaskResultSelectionResponse {
        private final String recordId;
        private final boolean received;
        private final int selectionCount;
        private final int mergedSegments;
        private final String message;
        /** 更新后的记录明细(含 selectionJson 原文)。 */
        private final VisibilityTaskResultDetailResponse detail;

        public VisibilityTaskResultSelectionResponse(String recordId, boolean received,
                                                     int selectionCount, int mergedSegments,
                                                     String message, VisibilityTaskResultDetailResponse detail) {
            this.recordId = recordId;
            this.received = received;
            this.selectionCount = selectionCount;
            this.mergedSegments = mergedSegments;
            this.message = message;
            this.detail = detail;
        }

        public String getRecordId() { return recordId; }
        public boolean isReceived() { return received; }
        public int getSelectionCount() { return selectionCount; }
        public int getMergedSegments() { return mergedSegments; }
        public String getMessage() { return message; }
        public VisibilityTaskResultDetailResponse getDetail() { return detail; }
    }

    public static class TargetQueryRequest {
        private List<Long> targetIds = new ArrayList<Long>();
        private List<String> belongs = new ArrayList<String>();
        private BigDecimal minWeight;
        private BigDecimal maxWeight;
        private Instant epochStartTime;
        private Instant epochEndTime;
        /** 目录历元下限，采用 TLE 约定的 YYDDD 五位编码，例如 23340。 */
        private String minTleEpochCode;
        /** 星历超出该天数时仅产生风险提示，不能作为硬排除条件。 */
        private Long maxApproximationDays;
        /**
         * 当前任务的观测开始时间。提供该字段后，服务端会排除尚未发射目标，并计算星历时效风险；
         * 时效风险不会阻断传播尝试。它不是用户可选的轨道历元，而是本次任务的计算时间基准。
         */
        private Instant planningStartTime;
        /** 当前任务的观测结束时间，必须与 {@link #planningStartTime} 成对提供。 */
        private Instant planningEndTime;
        private String excludeNameRegex;
        private long pageNumber = 1L;
        private long pageSize = 50L;

        public List<Long> getTargetIds() { return targetIds; }
        public void setTargetIds(List<Long> targetIds) { this.targetIds = targetIds; }
        public List<String> getBelongs() { return belongs; }
        public void setBelongs(List<String> belongs) { this.belongs = belongs; }
        public BigDecimal getMinWeight() { return minWeight; }
        public void setMinWeight(BigDecimal minWeight) { this.minWeight = minWeight; }
        public BigDecimal getMaxWeight() { return maxWeight; }
        public void setMaxWeight(BigDecimal maxWeight) { this.maxWeight = maxWeight; }
        public Instant getEpochStartTime() { return epochStartTime; }
        public void setEpochStartTime(Instant epochStartTime) { this.epochStartTime = epochStartTime; }
        public Instant getEpochEndTime() { return epochEndTime; }
        public void setEpochEndTime(Instant epochEndTime) { this.epochEndTime = epochEndTime; }
        public String getMinTleEpochCode() { return minTleEpochCode; }
        public void setMinTleEpochCode(String minTleEpochCode) { this.minTleEpochCode = minTleEpochCode; }
        public Long getMaxApproximationDays() { return maxApproximationDays; }
        public void setMaxApproximationDays(Long maxApproximationDays) { this.maxApproximationDays = maxApproximationDays; }
        public Instant getPlanningStartTime() { return planningStartTime; }
        public void setPlanningStartTime(Instant planningStartTime) { this.planningStartTime = planningStartTime; }
        public Instant getPlanningEndTime() { return planningEndTime; }
        public void setPlanningEndTime(Instant planningEndTime) { this.planningEndTime = planningEndTime; }
        public String getExcludeNameRegex() { return excludeNameRegex; }
        public void setExcludeNameRegex(String excludeNameRegex) { this.excludeNameRegex = excludeNameRegex; }
        public long getPageNumber() { return pageNumber; }
        public void setPageNumber(long pageNumber) { this.pageNumber = pageNumber; }
        public long getPageSize() { return pageSize; }
        public void setPageSize(long pageSize) { this.pageSize = pageSize; }
    }

    public static class DownlinkPlanRequest {
        private Instant startTime;
        private Instant endTime;
        private double coarseStepSec = 60.0;
        private double fineStepSec = 1.0;
        private List<Long> satelliteIds = new ArrayList<Long>();
        private Double preGuardSec;
        private Double postGuardSec;
        private Double allowedGapSec;
        private String satelliteIllumination;
        private List<StationConstraintRequest> stations = new ArrayList<StationConstraintRequest>();
        private List<SatelliteStationLinkRequest> links = new ArrayList<SatelliteStationLinkRequest>();

        public Instant getStartTime() { return startTime; }
        public void setStartTime(Instant startTime) { this.startTime = startTime; }
        public Instant getEndTime() { return endTime; }
        public void setEndTime(Instant endTime) { this.endTime = endTime; }
        public double getCoarseStepSec() { return coarseStepSec; }
        public void setCoarseStepSec(double coarseStepSec) { this.coarseStepSec = coarseStepSec; }
        public double getFineStepSec() { return fineStepSec; }
        public void setFineStepSec(double fineStepSec) { this.fineStepSec = fineStepSec; }
        public List<Long> getSatelliteIds() { return satelliteIds; }
        public void setSatelliteIds(List<Long> satelliteIds) { this.satelliteIds = satelliteIds; }
        public Double getPreGuardSec() { return preGuardSec; }
        public void setPreGuardSec(Double preGuardSec) { this.preGuardSec = preGuardSec; }
        public Double getPostGuardSec() { return postGuardSec; }
        public void setPostGuardSec(Double postGuardSec) { this.postGuardSec = postGuardSec; }
        public Double getAllowedGapSec() { return allowedGapSec; }
        public void setAllowedGapSec(Double allowedGapSec) { this.allowedGapSec = allowedGapSec; }
        public String getSatelliteIllumination() { return satelliteIllumination; }
        public void setSatelliteIllumination(String satelliteIllumination) { this.satelliteIllumination = satelliteIllumination; }
        public List<StationConstraintRequest> getStations() { return stations; }
        public void setStations(List<StationConstraintRequest> stations) { this.stations = stations; }
        public List<SatelliteStationLinkRequest> getLinks() { return links; }
        public void setLinks(List<SatelliteStationLinkRequest> links) { this.links = links; }
    }

    public static class StationConstraintRequest {
        private String stationId;
        private Double minElevationDeg;
        private Double maxElevationDeg;

        public String getStationId() { return stationId; }
        public void setStationId(String stationId) { this.stationId = stationId; }
        public Double getMinElevationDeg() { return minElevationDeg; }
        public void setMinElevationDeg(Double minElevationDeg) { this.minElevationDeg = minElevationDeg; }
        public Double getMaxElevationDeg() { return maxElevationDeg; }
        public void setMaxElevationDeg(Double maxElevationDeg) { this.maxElevationDeg = maxElevationDeg; }
    }

    /** 表示本次下传规划中被用户明确勾选的一条卫星—地面站建链关系。 */
    public static class SatelliteStationLinkRequest {
        private Long satelliteId;
        private String stationId;

        public Long getSatelliteId() { return satelliteId; }
        public void setSatelliteId(Long satelliteId) { this.satelliteId = satelliteId; }
        public String getStationId() { return stationId; }
        public void setStationId(String stationId) { this.stationId = stationId; }
    }

    public static class DownlinkPlanResponse {
        private final List<SatelliteStationWindowResponse> satelliteStationWindows = new ArrayList<SatelliteStationWindowResponse>();
        private final List<CoverageWindowResponse> coverageWindows = new ArrayList<CoverageWindowResponse>();
        private final List<SkippedLinkResponse> skippedLinks = new ArrayList<SkippedLinkResponse>();
        private long totalSkippedLinks;

        public List<SatelliteStationWindowResponse> getSatelliteStationWindows() { return satelliteStationWindows; }
        /**
         * 所有已选站星链路形成的连续覆盖并集。仅窗口重叠或首尾相接时合并，
         * 可以直接作为后续目标观测的可选时间范围。
         */
        public List<CoverageWindowResponse> getCoverageWindows() { return coverageWindows; }
        /** @return 当前响应携带的跳过下传链路明细，最多 500 条。 */
        public List<SkippedLinkResponse> getSkippedLinks() { return skippedLinks; }
        /** @return 本次规划跳过链路总数，可能大于 {@link #getSkippedLinks()} 的明细条数。 */
        public long getTotalSkippedLinks() { return totalSkippedLinks; }
        public void setTotalSkippedLinks(long totalSkippedLinks) { this.totalSkippedLinks = totalSkippedLinks; }
    }

    /** 一条未完成的卫星—地面站下传链路及其失败原因摘要。 */
    public static class SkippedLinkResponse {
        private final Long satelliteId;
        private final String satelliteName;
        private final String stationId;
        private final String stationName;
        private final String stage;
        private final String reason;

        public SkippedLinkResponse(Long satelliteId,
                                   String satelliteName,
                                   String stationId,
                                   String stationName,
                                   String stage,
                                   String reason) {
            this.satelliteId = satelliteId;
            this.satelliteName = satelliteName;
            this.stationId = stationId;
            this.stationName = stationName;
            this.stage = stage;
            this.reason = reason;
        }

        public Long getSatelliteId() { return satelliteId; }
        public String getSatelliteName() { return satelliteName; }
        public String getStationId() { return stationId; }
        public String getStationName() { return stationName; }
        public String getStage() { return stage; }
        public String getReason() { return reason; }
    }

    public static class SatelliteStationWindowResponse {
        private final Long satelliteId;
        private final String stationId;
        private final List<WindowResponse> windows;

        public SatelliteStationWindowResponse(Long satelliteId, String stationId, List<WindowResponse> windows) {
            this.satelliteId = satelliteId;
            this.stationId = stationId;
            this.windows = windows;
        }

        public Long getSatelliteId() { return satelliteId; }
        public String getStationId() { return stationId; }
        public List<WindowResponse> getWindows() { return windows; }
    }

    public static class WindowResponse {
        private final Instant startTime;
        private final Instant endTime;
        private final double durationSec;

        public WindowResponse(Instant startTime, Instant endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationSec = endTime.getEpochSecond() - startTime.getEpochSecond()
                    + (endTime.getNano() - startTime.getNano()) / 1_000_000_000.0;
        }

        /** @return 以 UTC 三位毫秒序列化的窗口开始时刻。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getStartTime() { return startTime; }

        /** @return 以 UTC 三位毫秒序列化的窗口结束时刻。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        public Instant getEndTime() { return endTime; }
        public double getDurationSec() { return durationSec; }
    }

    /** 表示一段可连续使用的下传覆盖时间及其参与的站星链路。 */
    public static class CoverageWindowResponse extends WindowResponse {
        private final List<SatelliteStationLinkResponse> links;

        public CoverageWindowResponse(Instant startTime, Instant endTime,
                                      List<SatelliteStationLinkResponse> links) {
            super(startTime, endTime);
            this.links = links;
        }

        public List<SatelliteStationLinkResponse> getLinks() { return links; }
    }

    /**
     * 合并覆盖窗口的可追溯来源。一个链路在同一合并窗口出现多次时仅保留一次，
     * 以避免前端因原始分段重复显示同一卫星—站点关系。
     */
    public static class SatelliteStationLinkResponse {
        private final Long satelliteId;
        private final String stationId;

        public SatelliteStationLinkResponse(Long satelliteId, String stationId) {
            this.satelliteId = satelliteId;
            this.stationId = stationId;
        }

        public Long getSatelliteId() { return satelliteId; }
        public String getStationId() { return stationId; }
    }

    public static class WindowValidationResponse {
        private final boolean satisfied;
        private final DownlinkPlanResponse plan;

        public WindowValidationResponse(boolean satisfied, DownlinkPlanResponse plan) {
            this.satisfied = satisfied;
            this.plan = plan;
        }

        public boolean isSatisfied() { return satisfied; }
        public DownlinkPlanResponse getPlan() { return plan; }
    }

    public static class VisibilityPlanRequest {
        private List<Long> observerSatelliteIds = new ArrayList<Long>();
        private List<Long> targetIds = new ArrayList<Long>();
        private List<String> belongs = new ArrayList<String>();
        private BigDecimal minWeight;
        private BigDecimal maxWeight;
        private Instant epochStartTime;
        private Instant epochEndTime;
        /** WinTool 风格的 YYDDD 目录历元下限；为空时不额外收窄旧集成方请求。 */
        private String minTleEpochCode;
        /** 仅用于星历时效风险判定的建议近似传播天数。 */
        private Long maxApproximationDays;
        private String excludeNameRegex;
        private Instant startTime;
        private Instant endTime;
        private double coarseStepSec = 60.0;
        private double fineStepSec = 1.0;
        private IlluminationConstraintRequest illuminationConstraint;
        private List<ObservationConstraintRequest> observationConstraints = new ArrayList<ObservationConstraintRequest>();
        /** 是否在响应中附带 WinTool 两段式对比报告文本（列表 + Mission 摘要/详细信息）。 */
        private Boolean winToolReport;

        public List<Long> getObserverSatelliteIds() { return observerSatelliteIds; }
        public void setObserverSatelliteIds(List<Long> observerSatelliteIds) { this.observerSatelliteIds = observerSatelliteIds; }
        public List<Long> getTargetIds() { return targetIds; }
        public void setTargetIds(List<Long> targetIds) { this.targetIds = targetIds; }
        public List<String> getBelongs() { return belongs; }
        public void setBelongs(List<String> belongs) { this.belongs = belongs; }
        public BigDecimal getMinWeight() { return minWeight; }
        public void setMinWeight(BigDecimal minWeight) { this.minWeight = minWeight; }
        public BigDecimal getMaxWeight() { return maxWeight; }
        public void setMaxWeight(BigDecimal maxWeight) { this.maxWeight = maxWeight; }
        public Instant getEpochStartTime() { return epochStartTime; }
        public void setEpochStartTime(Instant epochStartTime) { this.epochStartTime = epochStartTime; }
        public Instant getEpochEndTime() { return epochEndTime; }
        public void setEpochEndTime(Instant epochEndTime) { this.epochEndTime = epochEndTime; }
        public String getMinTleEpochCode() { return minTleEpochCode; }
        public void setMinTleEpochCode(String minTleEpochCode) { this.minTleEpochCode = minTleEpochCode; }
        public Long getMaxApproximationDays() { return maxApproximationDays; }
        public void setMaxApproximationDays(Long maxApproximationDays) { this.maxApproximationDays = maxApproximationDays; }
        public String getExcludeNameRegex() { return excludeNameRegex; }
        public void setExcludeNameRegex(String excludeNameRegex) { this.excludeNameRegex = excludeNameRegex; }
        public Instant getStartTime() { return startTime; }
        public void setStartTime(Instant startTime) { this.startTime = startTime; }
        public Instant getEndTime() { return endTime; }
        public void setEndTime(Instant endTime) { this.endTime = endTime; }
        public double getCoarseStepSec() { return coarseStepSec; }
        public void setCoarseStepSec(double coarseStepSec) { this.coarseStepSec = coarseStepSec; }
        public double getFineStepSec() { return fineStepSec; }
        public void setFineStepSec(double fineStepSec) { this.fineStepSec = fineStepSec; }
        /** @return 本次观测规划使用的观测星—目标光照组合临时约束；空值等价于不限。 */
        public IlluminationConstraintRequest getIlluminationConstraint() { return illuminationConstraint; }

        /**
         * 设置本次观测规划使用的观测星—目标光照组合临时约束。
         *
         * @param illuminationConstraint 预置模式或自定义精确状态集合
         */
        public void setIlluminationConstraint(IlluminationConstraintRequest illuminationConstraint) {
            this.illuminationConstraint = illuminationConstraint;
        }
        public List<ObservationConstraintRequest> getObservationConstraints() { return observationConstraints; }
        public void setObservationConstraints(List<ObservationConstraintRequest> observationConstraints) { this.observationConstraints = observationConstraints; }
        public Boolean getWinToolReport() { return winToolReport; }
        public void setWinToolReport(Boolean winToolReport) { this.winToolReport = winToolReport; }
    }

    /**
     * 表示一次目标观测规划的临时光照组合条件。
     * 仅在 mode 为 CUSTOM 时使用两端精确状态集合，其他模式由服务端按预置规则展开。
     */
    public static class IlluminationConstraintRequest {
        private String mode = "ANY";
        private List<String> observerStates = new ArrayList<String>();
        private List<String> targetStates = new ArrayList<String>();

        /** @return 光照组合模式编码。 */
        public String getMode() { return mode; }

        /** @param mode 光照组合模式编码。 */
        public void setMode(String mode) { this.mode = mode; }

        /** @return CUSTOM 模式下观测星允许的精确光照状态。 */
        public List<String> getObserverStates() { return observerStates; }

        /** @param observerStates CUSTOM 模式下观测星允许的精确光照状态。 */
        public void setObserverStates(List<String> observerStates) { this.observerStates = observerStates; }

        /** @return CUSTOM 模式下目标允许的精确光照状态。 */
        public List<String> getTargetStates() { return targetStates; }

        /** @param targetStates CUSTOM 模式下目标允许的精确光照状态。 */
        public void setTargetStates(List<String> targetStates) { this.targetStates = targetStates; }
    }

    public static class ObservationConstraintRequest {
        private List<Long> satelliteIds = new ArrayList<Long>();
        private double minDurationSec;

        public List<Long> getSatelliteIds() { return satelliteIds; }
        public void setSatelliteIds(List<Long> satelliteIds) { this.satelliteIds = satelliteIds; }
        public double getMinDurationSec() { return minDurationSec; }
        public void setMinDurationSec(double minDurationSec) { this.minDurationSec = minDurationSec; }
    }

    public static class VisibilityPlanResponse {
        private final List<VisibilityObservationResult> results;
        private final List<SkippedPairResponse> skippedPairs;
        private final long totalSkippedPairs;
        /**
         * 仅供异步结果持久化使用的完整跳过清单。同步接口只输出 {@link #skippedPairs}，
         * 避免大批量坏数据令响应体无限增长。
         */
        @JsonIgnore
        private final List<SkippedPairResponse> allSkippedPairs;
        /** 任务时间自动筛选的逐层命中数，用于说明未发射和星历过期目标并未进入传播。 */
        private final TargetFilterBreakdownResponse targetFilterBreakdown;
        /** 通过硬筛选但星历超出建议时长的目标风险；该清单不会阻止传播尝试。 */
        private final List<EphemerisWarningResponse> ephemerisWarnings;
        /** WinTool 两段式对比报告文本；仅在请求 winToolReport=true 时生成。 */
        private String winToolReport;

        public VisibilityPlanResponse(List<VisibilityObservationResult> results) {
            this(results, new ArrayList<SkippedPairResponse>(), 0L);
        }

        public VisibilityPlanResponse(List<VisibilityObservationResult> results,
                                      List<SkippedPairResponse> skippedPairs,
                                      long totalSkippedPairs) {
            this(results, skippedPairs, totalSkippedPairs, skippedPairs);
        }

        /**
         * 同时承载接口展示的受限清单与供任务结果表保存的完整清单。
         *
         * @param results 已计算完成的观测结果
         * @param skippedPairs 同步接口可展示的跳过明细（最多 500 条）
         * @param totalSkippedPairs 本次跳过组合总数
         * @param allSkippedPairs 未截断的跳过组合明细，仅用于持久化
         */
        public VisibilityPlanResponse(List<VisibilityObservationResult> results,
                                      List<SkippedPairResponse> skippedPairs,
                                      long totalSkippedPairs,
                                      List<SkippedPairResponse> allSkippedPairs) {
            this(results, skippedPairs, totalSkippedPairs, allSkippedPairs, null);
        }

        /**
         * 同时返回观测结果、完整跳过清单和任务时间筛选统计。
         * 时间筛选统计只描述进入传播前的目录过滤，不计入“跳过组合”数量，避免把正常业务排除误报为计算失败。
         */
        public VisibilityPlanResponse(List<VisibilityObservationResult> results,
                                      List<SkippedPairResponse> skippedPairs,
                                      long totalSkippedPairs,
                                      List<SkippedPairResponse> allSkippedPairs,
                                      TargetFilterBreakdownResponse targetFilterBreakdown) {
            this(results, skippedPairs, totalSkippedPairs, allSkippedPairs, targetFilterBreakdown,
                    Collections.<EphemerisWarningResponse>emptyList());
        }

        /**
         * 完整规划响应。风险清单与跳过组合分离：前者说明结果精度风险，后者说明轨道无法产生数值窗口。
         */
        public VisibilityPlanResponse(List<VisibilityObservationResult> results,
                                      List<SkippedPairResponse> skippedPairs,
                                      long totalSkippedPairs,
                                      List<SkippedPairResponse> allSkippedPairs,
                                      TargetFilterBreakdownResponse targetFilterBreakdown,
                                      List<EphemerisWarningResponse> ephemerisWarnings) {
            this.results = results;
            this.skippedPairs = skippedPairs;
            this.totalSkippedPairs = totalSkippedPairs;
            this.allSkippedPairs = allSkippedPairs;
            this.targetFilterBreakdown = targetFilterBreakdown;
            this.ephemerisWarnings = ephemerisWarnings;
        }

        /** 完整规划响应并附带 WinTool 对比报告文本。 */
        public VisibilityPlanResponse(List<VisibilityObservationResult> results,
                                      List<SkippedPairResponse> skippedPairs,
                                      long totalSkippedPairs,
                                      List<SkippedPairResponse> allSkippedPairs,
                                      TargetFilterBreakdownResponse targetFilterBreakdown,
                                      List<EphemerisWarningResponse> ephemerisWarnings,
                                      String winToolReport) {
            this.results = results;
            this.skippedPairs = skippedPairs;
            this.totalSkippedPairs = totalSkippedPairs;
            this.allSkippedPairs = allSkippedPairs;
            this.targetFilterBreakdown = targetFilterBreakdown;
            this.ephemerisWarnings = ephemerisWarnings;
            this.winToolReport = winToolReport;
        }

        public List<VisibilityObservationResult> getResults() { return results; }
        /** @return 当前响应携带的跳过组合明细，最多 500 条。 */
        public List<SkippedPairResponse> getSkippedPairs() { return skippedPairs; }
        /** @return 本次规划跳过组合总数，可能大于 {@link #getSkippedPairs()} 的明细条数。 */
        public long getTotalSkippedPairs() { return totalSkippedPairs; }
        /** @return 未截断的跳过组合明细，仅供服务端异步任务持久化使用。 */
        @JsonIgnore
        public List<SkippedPairResponse> getAllSkippedPairs() { return allSkippedPairs; }
        /** @return 本次任务时间筛选的逐层统计；历史结果或内部构造的简化响应可为空。 */
        public TargetFilterBreakdownResponse getTargetFilterBreakdown() { return targetFilterBreakdown; }
        /** @return 仍已尝试计算、但应结合星历时效审阅的目标风险清单。 */
        public List<EphemerisWarningResponse> getEphemerisWarnings() { return ephemerisWarnings; }
        /** @return WinTool 两段式对比报告文本；未请求时为 null，不序列化。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String getWinToolReport() { return winToolReport; }
    }

    /** 一个目标的星历时效风险；不会用虚假的可见窗口替代真实计算结果。 */
    public static class EphemerisWarningResponse {
        private final String targetId;
        private final String targetName;
        private final String reason;

        public EphemerisWarningResponse(String targetId, String targetName, String reason) {
            this.targetId = targetId;
            this.targetName = targetName;
            this.reason = reason;
        }

        public String getTargetId() { return targetId; }
        public String getTargetName() { return targetName; }
        public String getReason() { return reason; }
    }

    /** 一条未完成的观测星—目标组合及其失败阶段、原因摘要。 */
    public static class SkippedPairResponse {
        private final String observerSatelliteId;
        private final String observerSatelliteName;
        private final String targetId;
        private final String targetName;
        private final String stage;
        private final String reason;

        public SkippedPairResponse(String observerSatelliteId,
                                   String observerSatelliteName,
                                   String targetId,
                                   String targetName,
                                   String stage,
                                   String reason) {
            this.observerSatelliteId = observerSatelliteId;
            this.observerSatelliteName = observerSatelliteName;
            this.targetId = targetId;
            this.targetName = targetName;
            this.stage = stage;
            this.reason = reason;
        }

        public String getObserverSatelliteId() { return observerSatelliteId; }
        public String getObserverSatelliteName() { return observerSatelliteName; }
        public String getTargetId() { return targetId; }
        public String getTargetName() { return targetName; }
        public String getStage() { return stage; }
        public String getReason() { return reason; }
    }

    public static class TargetQueryResponse {
        private final long total;
        private final long pageNumber;
        private final long pageSize;
        private final List<TargetEntity> targets;
        /**
         * 与本页预览同一套条件得到的完整目标主键集合。前端把它作为规划输入回传，
         * 以保证“页面显示的筛选结果”和“实际参与传播的目标”是同一集合。
         */
        private final List<Long> targetIds;
        private final TargetFilterBreakdownResponse filterBreakdown;

        public TargetQueryResponse(long total, long pageNumber, long pageSize, List<TargetEntity> targets) {
            this(total, pageNumber, pageSize, targets, null, Collections.<Long>emptyList());
        }

        /**
         * @param filterBreakdown 各筛选条件顺序叠加后的命中数；目录预览接口始终返回，
         *                        保留可空兼容已有内部调用方。
         */
        public TargetQueryResponse(long total,
                                   long pageNumber,
                                   long pageSize,
                                   List<TargetEntity> targets,
                                   TargetFilterBreakdownResponse filterBreakdown) {
            this(total, pageNumber, pageSize, targets, filterBreakdown, Collections.<Long>emptyList());
        }

        /**
         * @param targetIds 与 {@code total} 对应的全部命中目标 ID，不受分页影响；为空只表示旧调用方
         *                  尚未提供该字段，不能由前端擅自改为“全库目标”。
         */
        public TargetQueryResponse(long total,
                                   long pageNumber,
                                   long pageSize,
                                   List<TargetEntity> targets,
                                   TargetFilterBreakdownResponse filterBreakdown,
                                   List<Long> targetIds) {
            this.total = total;
            this.pageNumber = pageNumber;
            this.pageSize = pageSize;
            this.targets = targets;
            this.filterBreakdown = filterBreakdown;
            this.targetIds = targetIds == null ? Collections.<Long>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Long>(targetIds));
        }

        public long getTotal() { return total; }
        public long getPageNumber() { return pageNumber; }
        public long getPageSize() { return pageSize; }
        public List<TargetEntity> getTargets() { return targets; }
        public List<Long> getTargetIds() { return targetIds; }
        public TargetFilterBreakdownResponse getFilterBreakdown() { return filterBreakdown; }
    }

    /**
     * 目标筛选每一层处理完成后的剩余命中数。
     * 数值不是各条件独立命中的并集：每一项均建立在其前置条件已生效的基础上，
     * 因而可直观定位究竟是哪一个筛选条件将候选目标收敛为零。
     */
    public static class TargetFilterBreakdownResponse {
        private final long belongsHit;
        private final long weightHit;
        private final long epochHit;
        private final long epochConstraintHit;
        private final long ephemerisWarningHit;
        private final long excludeHit;
        private final long maxApproximationDays;

        public TargetFilterBreakdownResponse(long belongsHit, long weightHit, long epochHit, long excludeHit) {
            this(belongsHit, weightHit, epochHit, excludeHit, 0L, 0L);
        }

        /**
         * @param ephemerisWarningHit 星历超出建议近似时长但仍参与计算的目标数（只提示，不排除）
         * @param maxApproximationDays 服务端实际使用的星历近似时长；0 表示本次没有风险提示
         */
        public TargetFilterBreakdownResponse(long belongsHit,
                                             long weightHit,
                                             long epochHit,
                                             long excludeHit,
                                             long ephemerisWarningHit,
                                             long maxApproximationDays) {
            this.belongsHit = belongsHit;
            this.weightHit = weightHit;
            this.epochHit = epochHit;
            this.epochConstraintHit = epochHit;
            this.ephemerisWarningHit = ephemerisWarningHit;
            this.excludeHit = excludeHit;
            this.maxApproximationDays = maxApproximationDays;
        }

        public long getBelongsHit() { return belongsHit; }
        public long getWeightHit() { return weightHit; }
        public long getEpochHit() { return epochHit; }
        /** @return 施加 YYDDD 历元下限后的剩余目标数。 */
        public long getEpochConstraintHit() { return epochConstraintHit; }
        /** @return 超出建议近似时长但仍会尝试计算的目标数。 */
        public long getEphemerisWarningHit() { return ephemerisWarningHit; }
        public long getExcludeHit() { return excludeHit; }
        public long getMaxApproximationDays() { return maxApproximationDays; }
    }

    /** 目标表 BELONG_ 数据字典的一项实际取值及其当前记录数。 */
    public static class BelongOptionResponse {
        private final String code;
        private final long count;
        private final String description;

        public BelongOptionResponse(String code, long count, String description) {
            this.code = code;
            this.count = count;
            this.description = description;
        }

        public String getCode() { return code; }
        public long getCount() { return count; }
        public String getDescription() { return description; }
    }

    /**
     * 统一承载分页接口的总数、页码、页大小与当前页记录。
     *
     * @param <T> 当前页记录类型
     */
    public static class PageResponse<T> {
        private final long total;
        private final long pageNumber;
        private final long pageSize;
        private final List<T> records;

        public PageResponse(long total, long pageNumber, long pageSize, List<T> records) {
            this.total = total;
            this.pageNumber = pageNumber;
            this.pageSize = pageSize;
            this.records = records;
        }

        public long getTotal() { return total; }
        public long getPageNumber() { return pageNumber; }
        public long getPageSize() { return pageSize; }
        public List<T> getRecords() { return records; }
    }

    /**
     * 供卫星目录表格展示的轻量记录，不包含轨道两行数、六根数及载荷配置。
     */
    public static class SatelliteSummaryResponse {
        private final Long id;
        private final String name;
        private final String modelType;
        private final String type;
        private final Long scid;
        private final String objectId;
        private final String objectName;
        private final Long noradCatId;
        private final String country;
        private final BigDecimal period;
        private final BigDecimal catalogInclination;
        private final BigDecimal apogee;
        private final BigDecimal perigee;
        private final String rcsSize;
        private final BigDecimal rcsValue;
        private final java.time.LocalDate launchDate;
        private final String catalogComment;
        private final BigDecimal weight;

        public SatelliteSummaryResponse(SatelliteEntity satellite) {
            this.id = satellite.getId();
            this.name = satellite.getName();
            this.modelType = satellite.getModelType();
            this.type = satellite.getType();
            this.scid = satellite.getScid();
            this.objectId = satellite.getObjectId();
            this.objectName = satellite.getObjectName();
            this.noradCatId = satellite.getNoradCatId();
            this.country = satellite.getCountry();
            this.period = satellite.getPeriod();
            this.catalogInclination = satellite.getCatalogInclination();
            this.apogee = satellite.getApogee();
            this.perigee = satellite.getPerigee();
            this.rcsSize = satellite.getRcsSize();
            this.rcsValue = satellite.getRcsValue();
            this.launchDate = satellite.getLaunchDate();
            this.catalogComment = satellite.getCatalogComment();
            this.weight = satellite.getWeight();
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getModelType() { return modelType; }
        public String getType() { return type; }
        public Long getScid() { return scid; }
        public String getObjectId() { return objectId; }
        public String getObjectName() { return objectName; }
        public Long getNoradCatId() { return noradCatId; }
        public String getCountry() { return country; }
        public BigDecimal getPeriod() { return period; }
        public BigDecimal getCatalogInclination() { return catalogInclination; }
        public BigDecimal getApogee() { return apogee; }
        public BigDecimal getPerigee() { return perigee; }
        public String getRcsSize() { return rcsSize; }
        public BigDecimal getRcsValue() { return rcsValue; }
        public java.time.LocalDate getLaunchDate() { return launchDate; }
        public String getCatalogComment() { return catalogComment; }
        public BigDecimal getWeight() { return weight; }
    }

    /**
     * 供卫星选择器使用的最小目录字段，避免渲染和传输完整卫星实体。
     */
    public static class SatelliteOptionResponse {
        private final Long id;
        private final String name;
        private final String modelType;
        private final String type;
        private final Long scid;
        private final Long noradCatId;

        public SatelliteOptionResponse(SatelliteEntity satellite) {
            this.id = satellite.getId();
            this.name = satellite.getName();
            this.modelType = satellite.getModelType();
            this.type = satellite.getType();
            this.scid = satellite.getScid();
            this.noradCatId = satellite.getNoradCatId();
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getModelType() { return modelType; }
        public String getType() { return type; }
        public Long getScid() { return scid; }
        public Long getNoradCatId() { return noradCatId; }
    }
}
