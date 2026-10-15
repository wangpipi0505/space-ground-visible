import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.SpaceTargetVisibleApplication;
import com.runisys.orbit.spacevisibility.api.space.SpaceVisibilityController;
import com.runisys.orbit.spacevisibility.application.space.contract.SpacePlanningModels;
import com.runisys.orbit.spacevisibility.api.space.PlanningTaskController;
import com.runisys.orbit.spacevisibility.api.space.VisibilityTaskResultController;
import com.runisys.orbit.spacevisibility.application.space.OrbitStateProviderFactory;
import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity.*;
import com.runisys.orbit.spacevisibility.infrastructure.space.persistence.mapper.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;

/**
 * 空间真实服务基准：以原 Spring 上下文、独立 H2 库、原 Mapper 和 Controller 执行业务。
 * 轨道传播、太阳、遮挡和窗口扫描均来自产品原实现；不会用固定结果模拟传播。
 * 固定 2026 UTC 历元、目录和站点，输出原 Jackson 注解规定的 JSON 类型及格式。
 */
public class SpaceRegression {
    private static final Instant EPOCH = Instant.parse("2026-07-01T10:00:00Z");

    /** 启动隔离内存库并执行两个非空窗口案例；空结果立即失败，避免得到无效黄金样例。 */
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        SpringApplication application = new SpringApplication(SpaceTargetVisibleApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.datasource.url=" + (args.length > 1 ? args[1] : "jdbc:h2:mem:t07_baseline;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"),
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.flyway.enabled=true",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--space.visibility.calculation.worker-threads=2")) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            SatelliteMapper satellites = context.getBean(SatelliteMapper.class);
            TargetMapper targets = context.getBean(TargetMapper.class);
            StationMapper stations = context.getBean(StationMapper.class);
            targets.delete(null);
            satellites.delete(null);
            stations.delete(null);
            SatelliteEntity observer = mapper.readValue(orbitJson(1, "基准观测星", 7078137, 0), SatelliteEntity.class);
            satellites.insert(observer);
            TargetEntity target = mapper.readValue(orbitJson(2, "基准目标星", 7278137, 30), TargetEntity.class);
            target.setBelong("US");
            target.setWeight(new BigDecimal("120"));
            target.setLaunchDate(LocalDate.of(2020, 1, 1));
            targets.insert(target);
            SpaceVisibilityController controller = context.getBean(SpaceVisibilityController.class);
            SpacePlanningModels.PayloadProfileRequest profile = mapper.readValue(
                    "{\"payloadType\":\"SPACE\",\"minDistanceKm\":0,\"maxDistanceKm\":0,"
                    + "\"azimuthMinDeg\":null,\"azimuthMaxDeg\":null,\"elevationMinDeg\":null,"
                    + "\"elevationMaxDeg\":null,\"angleRateDegPerSec\":-1,\"sunAvoidHalfAngleDeg\":0}",
                    SpacePlanningModels.PayloadProfileRequest.class);
            controller.saveSatellitePayloadProfile(1L, profile);
            // 站点取原传播器在历元的地固星下点，保证下传非空；使用原 GMST 旋转，不造位置。
            Vector3 ecef = GroundStationGeometry.eciToEcef(context.getBean(OrbitStateProviderFactory.class)
                    .fromSatellite(observer).positionAt(EPOCH), EPOCH);
            StationEntity station = new StationEntity();
            station.setId("T04-STATION");
            station.setName("基准星下站");
            station.setLongitude(BigDecimal.valueOf(Math.toDegrees(Math.atan2(ecef.getY(), ecef.getX()))));
            station.setLatitude(BigDecimal.valueOf(Math.toDegrees(Math.atan2(ecef.getZ(),
                    Math.sqrt(ecef.getX() * ecef.getX() + ecef.getY() * ecef.getY())))));
            station.setHeight(BigDecimal.ZERO);
            stations.insert(station);
            String times = "\"startTime\":\"2026-07-01T10:00:00Z\",\"endTime\":\"2026-07-01T10:10:00Z\","
                    + "\"coarseStepSec\":60,\"fineStepSec\":1";
            SpacePlanningModels.DownlinkPlanRequest downlink = mapper.readValue("{" + times
                    + ",\"satelliteIds\":[1],\"preGuardSec\":0,\"postGuardSec\":0,\"allowedGapSec\":0,"
                    + "\"satelliteIllumination\":\"ANY\",\"stations\":[{\"stationId\":\"T04-STATION\","
                    + "\"minElevationDeg\":0,\"maxElevationDeg\":90}],\"links\":[{\"satelliteId\":1,"
                    + "\"stationId\":\"T04-STATION\"}]}", SpacePlanningModels.DownlinkPlanRequest.class);
            SpacePlanningModels.VisibilityPlanRequest visibility = mapper.readValue("{" + times
                    + ",\"observerSatelliteIds\":[1],\"targetIds\":[2],\"winToolReport\":false}",
                    SpacePlanningModels.VisibilityPlanRequest.class);
            SpacePlanningModels.TargetQueryRequest query = mapper.readValue(
                    "{\"belongs\":[\"US\"],\"minWeight\":100,\"maxWeight\":150,"
                    + "\"planningStartTime\":\"2026-07-01T10:00:00Z\","
                    + "\"planningEndTime\":\"2026-07-01T10:10:00Z\"}", SpacePlanningModels.TargetQueryRequest.class);
            write(mapper, output, "satellites.json", controller.satellites());
            write(mapper, output, "stations.json", controller.stations());
            write(mapper, output, "target-query.json", controller.queryTargets(query));
            write(mapper, output, "payload-profile.json", controller.satellitePayloadProfiles(1L));
            write(mapper, output, "downlink-request.json", downlink);
            write(mapper, output, "visibility-request.json", visibility);
            write(mapper, output, "target-query-request.json", query);
            SpacePlanningModels.DownlinkPlanResponse downlinkResult = controller.planDownlink(downlink);
            SpacePlanningModels.VisibilityPlanResponse visibilityResult = controller.planVisibility(visibility);
            write(mapper, output, "downlink.json", downlinkResult);
            write(mapper, output, "visibility.json", visibilityResult);
            write(mapper, output, "window-validation.json", controller.validateWindow(downlink));
            SatelliteEntity secondObserver = mapper.readValue(orbitJson(3, "基准共同观测星", 7078137, 2), SatelliteEntity.class);
            satellites.insert(secondObserver);
            controller.saveSatellitePayloadProfile(3L, profile);
            SpacePlanningModels.PayloadProfileRequest groundProfile = mapper.readValue(
                    "{\"payloadType\":\"GROUND\",\"minDistanceKm\":0,\"maxDistanceKm\":0,\"angleRateDegPerSec\":0,\"sunAvoidHalfAngleDeg\":0}",
                    SpacePlanningModels.PayloadProfileRequest.class);
            write(mapper, output, "ground-profile-save.json", controller.saveSatellitePayloadProfile(3L, groundProfile));
            write(mapper, output, "ground-profile-read.json", controller.satellitePayloadProfiles(3L));
            controller.saveSatellitePayloadProfile(3L, profile);
            visibility.setObserverSatelliteIds(java.util.Arrays.asList(1L, 3L));
            visibility.setObservationConstraints(java.util.Arrays.asList(mapper.readValue("{\"satelliteIds\":[1,3],\"minDurationSec\":0}", SpacePlanningModels.ObservationConstraintRequest.class)));
            visibility.setWinToolReport(true);
            SpacePlanningModels.VisibilityPlanResponse common = controller.planVisibility(visibility);
            com.fasterxml.jackson.databind.JsonNode commonTree = mapper.valueToTree(common);
            if (commonTree.path("results").size() != 1 || !commonTree.toString().contains("COOPERATIVE")) {
                throw new IllegalStateException("两星共同观测必须产生一条共同非空结果");
            }
            write(mapper, output, "common-request.json", visibility);
            write(mapper, output, "common-sync.json", common);
            Files.write(output.resolve("wintool-report.txt"), common.getWinToolReport().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            PlanningTaskController taskController = context.getBean(PlanningTaskController.class);
            String taskId = taskController.submitTask(visibility).get("taskId");
            long deadline = System.currentTimeMillis() + 60000;
            while (!"SUCCEEDED".equals(taskController.taskStatus(taskId).getBody().getStatus())) {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("异步真实任务未成功完成");
                Thread.sleep(20);
            }
            Object asyncResult = taskController.taskResult(taskId).getBody();
            if (!commonTree.equals(mapper.valueToTree(asyncResult))) throw new IllegalStateException("同步/异步结果不相等");
            write(mapper, output, "common-async.json", asyncResult);
            // 成功状态先于终态持久化回调发布，必须等到真实数据库结果落盘后才比较。
            VisibilityTaskResultMapper recordMapper = context.getBean(VisibilityTaskResultMapper.class);
            VisibilityTaskResultEntity record = null;
            while (System.currentTimeMillis() <= deadline) {
                java.util.List<VisibilityTaskResultEntity> rows = recordMapper.selectList(null);
                for (VisibilityTaskResultEntity row : rows) if (taskId.equals(row.getTaskId())) record = row;
                if (record != null && record.getResultJson() != null) break;
                Thread.sleep(20);
            }
            if (record == null || record.getResultJson() == null) throw new IllegalStateException("真实任务结果未持久化");
            if (!mapper.readTree(mapper.writeValueAsString(common)).equals(mapper.readTree(record.getResultJson()))) throw new IllegalStateException("数据库回读结果不相等");
            write(mapper, output, "persisted-request.json", mapper.readTree(record.getRequestJson()));
            write(mapper, output, "persisted-result.json", mapper.readTree(record.getResultJson()));
            com.fasterxml.jackson.databind.node.ObjectNode status = mapper.valueToTree(taskController.taskStatus(taskId).getBody());
            status.put("taskId", "<TASK_ID>"); status.putNull("createdAt"); status.putNull("completedAt");
            write(mapper, output, "async-status.json", status);
            VisibilityTaskResultController records = context.getBean(VisibilityTaskResultController.class);
            SpacePlanningModels.VisibilityTaskResultRemarkRequest remark = new SpacePlanningModels.VisibilityTaskResultRemarkRequest();
            remark.setRemark("T07真实基准备注");
            if (!"T07真实基准备注".equals(records.updateRemark(record.getId(), remark).getBody().getRemark()))
                throw new IllegalStateException("真实数据库备注更新失败");
            com.fasterxml.jackson.databind.node.ObjectNode detail = mapper.valueToTree(records.detail(record.getId()).getBody());
            detail.put("id", 0); detail.put("taskId", "<TASK_ID>"); detail.putNull("createTime"); detail.putNull("completeTime");
            // JSON 文本按完整对象解析，保留所有字段、数值及数组顺序，仅消除序列化属性排列差异。
            detail.set("requestJson", mapper.readTree(detail.path("requestJson").asText()));
            detail.set("resultJson", mapper.readTree(detail.path("resultJson").asText()));
            write(mapper, output, "record-detail.json", detail);
            if (records.delete(record.getId()).getStatusCodeValue() != 200 || records.detail(record.getId()).getStatusCodeValue() != 404)
                throw new IllegalStateException("真实数据库记录删除失败");
            SpacePlanningModels.PayloadProfileRequest browserProfile = mapper.readValue(
                    "{\"payloadType\":\"SPACE\",\"minDistanceKm\":100,\"maxDistanceKm\":8000,\"angleRateDegPerSec\":-1,\"sunAvoidHalfAngleDeg\":0}",
                    SpacePlanningModels.PayloadProfileRequest.class);
            controller.saveSatellitePayloadProfile(1L, browserProfile);
            write(mapper, output, "browser-profile.json", controller.satellitePayloadProfiles(1L));
            // 浏览器 fixture 固定站点仰角 0—90 度，与上面的显式请求一致，避免页面默认收窄。
            station.setMinElevation(BigDecimal.ZERO); station.setMaxElevation(BigDecimal.valueOf(90));
            stations.updateById(station);
            if (mapper.valueToTree(downlinkResult).findValues("startTime").isEmpty()
                    || mapper.valueToTree(visibilityResult).findValues("startTime").isEmpty()) {
                throw new IllegalStateException("空间基准窗口必须非空");
            }
        }
    }

    /** 固定原实体字段和时间。审计时间显式为 null，避免数据库运行时默认值进入业务比较。 */
    private static String orbitJson(int id, String name, int a, int m) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"modelType\":\"KEPLER_6\","
                + "\"semiMajorAxis\":" + a + ",\"eccentricity\":0,\"inclination\":50,\"raan\":30,"
                + "\"argumentOfPerigee\":0,\"meanAnomaly\":" + m
                + ",\"epochTime\":\"2026-07-01T10:00:00\",\"createTime\":null,\"updateTime\":null}";
    }

    /** 只规范化实体审计 createTime/updateTime 字段；业务时间、类型和数值保持原 Jackson 输出。 */
    private static void write(ObjectMapper mapper, Path output, String name, Object value) throws Exception {
        com.fasterxml.jackson.databind.JsonNode tree = mapper.valueToTree(value);
        clearAuditTime(tree);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(name).toFile(), tree);
    }

    /** 递归保留审计字段的 null 类型；不处理任何窗口时间、历元或其他业务字段。 */
    private static void clearAuditTime(com.fasterxml.jackson.databind.JsonNode tree) {
        if (tree.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode object = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            if (object.has("createTime")) object.putNull("createTime");
            if (object.has("updateTime")) object.putNull("updateTime");
        }
        for (com.fasterxml.jackson.databind.JsonNode child : tree) clearAuditTime(child);
    }
}
