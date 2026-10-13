package com.runisys.orbit.spacevisibility.api;

import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.StationEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.SatelliteMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.StationMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.TargetMapper;
import com.runisys.orbit.spacevisibility.application.OrbitStateProviderFactory;
import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
class SpaceVisibilityControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SatelliteMapper satelliteMapper;
    @Autowired
    private StationMapper stationMapper;
    @Autowired
    private TargetMapper targetMapper;
    @Autowired
    private OrbitStateProviderFactory orbitStateProviderFactory;

    @BeforeEach
    void resetReferenceData() {
        targetMapper.delete(null);
        satelliteMapper.delete(null);
        stationMapper.delete(null);

        SatelliteEntity satellite = new SatelliteEntity();
        satellite.setId(1L);
        satellite.setName("测试我方星");
        satellite.setModelType("KEPLER_6");
        satellite.setSemiMajorAxis(new BigDecimal("7000000"));
        satellite.setEccentricity(BigDecimal.ZERO);
        satellite.setInclination(BigDecimal.ZERO);
        satellite.setRaan(BigDecimal.ZERO);
        satellite.setArgumentOfPerigee(BigDecimal.ZERO);
        satellite.setMeanAnomaly(BigDecimal.ZERO);
        satellite.setEpochTime(LocalDateTime.of(2000, 1, 1, 12, 0));
        satellite.setObjectId("2026-180C");
        satellite.setNoradCatId(100245L);
        satellite.setCountry("PRC");
        satellite.setRcsSize("LARGE");
        satellite.setWeight(new BigDecimal("321.123456"));
        satellite.setLaunchDate(java.time.LocalDate.of(2026, 8, 6));
        satellite.setCatalogComment("测试目录注释");
        satellite.setPayloadJson(payloadProfilesJson(0, 2_000));
        satelliteMapper.insert(satellite);

        SatelliteEntity secondSatellite = new SatelliteEntity();
        secondSatellite.setId(3L);
        secondSatellite.setName("测试我方星二号");
        secondSatellite.setModelType("KEPLER_6");
        secondSatellite.setSemiMajorAxis(new BigDecimal("7000000"));
        secondSatellite.setEccentricity(BigDecimal.ZERO);
        secondSatellite.setInclination(BigDecimal.ZERO);
        secondSatellite.setRaan(BigDecimal.ZERO);
        secondSatellite.setArgumentOfPerigee(BigDecimal.ZERO);
        secondSatellite.setMeanAnomaly(BigDecimal.ZERO);
        secondSatellite.setEpochTime(LocalDateTime.of(2000, 1, 1, 12, 0));
        secondSatellite.setPayloadJson(payloadProfilesJson(0, 2_000));
        satelliteMapper.insert(secondSatellite);

        com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity target = new com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity();
        target.setId(2L);
        target.setName("测试目标");
        target.setModelType("KEPLER_6");
        target.setObjectId("2026-180D");
        target.setNoradCatId(100246L);
        target.setCountry("US");
        target.setBelong("US");
        target.setRcsSize("MEDIUM");
        target.setWeight(new BigDecimal("56.789012"));
        // 该目标在基准规划时段之前已发射，用于验证可被任务时间筛选保留。
        target.setLaunchDate(java.time.LocalDate.of(1999, 8, 6));
        target.setCatalogComment("测试目标目录注释");
        target.setSemiMajorAxis(new BigDecimal("7000000"));
        target.setEccentricity(BigDecimal.ZERO);
        target.setInclination(BigDecimal.ZERO);
        target.setRaan(BigDecimal.ZERO);
        target.setArgumentOfPerigee(BigDecimal.ZERO);
        target.setMeanAnomaly(new BigDecimal("20"));
        target.setEpochTime(LocalDateTime.of(2000, 1, 1, 12, 0));
        targetMapper.insert(target);

        com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity secondTarget = new com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity();
        secondTarget.setId(4L);
        secondTarget.setName("测试目标二号");
        secondTarget.setModelType("TLE_SGP4");
        secondTarget.setObjectId("2026-180E");
        secondTarget.setNoradCatId(100247L);
        secondTarget.setCountry("CN");
        secondTarget.setBelong("CN");
        secondTarget.setWeight(new BigDecimal("120.000000"));
        secondTarget.setSemiMajorAxis(new BigDecimal("7000000"));
        secondTarget.setEccentricity(BigDecimal.ZERO);
        secondTarget.setInclination(BigDecimal.ZERO);
        secondTarget.setRaan(BigDecimal.ZERO);
        secondTarget.setArgumentOfPerigee(BigDecimal.ZERO);
        secondTarget.setMeanAnomaly(new BigDecimal("10"));
        secondTarget.setEpochTime(LocalDateTime.of(2001, 1, 1, 12, 0));
        // 目标二号在 2000 年任务时段尚未发射，且星历也明显过期；两项均应在传播前排除。
        secondTarget.setLaunchDate(java.time.LocalDate.of(2001, 8, 6));
        targetMapper.insert(secondTarget);

        StationEntity station = new StationEntity();
        station.setId("ST-001");
        station.setName("测试站");
        station.setLongitude(new BigDecimal("79.53938163"));
        station.setLatitude(BigDecimal.ZERO);
        station.setHeight(BigDecimal.ZERO);
        stationMapper.insert(station);

        StationEntity secondStation = new StationEntity();
        secondStation.setId("ST-002");
        secondStation.setName("测试站二号");
        secondStation.setLongitude(new BigDecimal("79.53938163"));
        secondStation.setLatitude(BigDecimal.ZERO);
        secondStation.setHeight(BigDecimal.ZERO);
        stationMapper.insert(secondStation);
    }

    @Test
    void rejectsUnknownSatelliteIlluminationConstraint() throws Exception {
        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-01-01T00:00:00Z\",\"endTime\":\"2026-01-01T00:10:00Z\",\"satelliteIds\":[1],\"satelliteIllumination\":\"UNKNOWN\",\"stations\":[{\"stationId\":\"ST-001\"}],\"links\":[{\"satelliteId\":1,\"stationId\":\"ST-001\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("卫星光照约束仅支持 ANY、SUNLIT 或 SHADOW"));
    }

    @Test
    void rejectsLinkContainingUnselectedSatelliteOrStation() throws Exception {
        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-01-01T00:00:00Z\",\"endTime\":\"2026-01-01T00:10:00Z\",\"satelliteIds\":[1],\"stations\":[{\"stationId\":\"ST-001\"}],\"links\":[{\"satelliteId\":3,\"stationId\":\"ST-001\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("下传关系包含未选择的卫星或地面站"));
    }

    @Test
    // 验证 Spring 的可选静态资源服务能力；仅使用 test/resources 中明确的夹具。
    // 正式 Vue 页面独立构建部署，不要求 Maven 测试依赖 ignored 的历史生成 static。
    void servesOptionalStaticResourceFixture() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("T28_STATIC_TEST_FIXTURE")));
    }

    @Test
    void exposesImportedCatalogFieldsAndWeightForReferenceData() throws Exception {
        mockMvc.perform(get("/api/v1/space/satellites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].objectId").value("2026-180C"))
                .andExpect(jsonPath("$[0].noradCatId").value(100245))
                .andExpect(jsonPath("$[0].rcsSize").value("LARGE"))
                .andExpect(jsonPath("$[0].weight").value(321.123456))
                .andExpect(jsonPath("$[0].launchDate").value("2026-08-06"))
                .andExpect(jsonPath("$[0].catalogComment").value("测试目录注释"));

        mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[0].objectId").value("2026-180D"))
                .andExpect(jsonPath("$.targets[0].noradCatId").value(100246))
                .andExpect(jsonPath("$.targets[0].rcsSize").value("MEDIUM"))
                .andExpect(jsonPath("$.targets[0].weight").value(56.789012))
                .andExpect(jsonPath("$.targets[0].launchDate").value("1999-08-06"))
                .andExpect(jsonPath("$.targets[0].catalogComment").value("测试目标目录注释"));
    }

    @Test
    void pagesCatalogDataAndReturnsLightweightSatelliteOptions() throws Exception {
        mockMvc.perform(get("/api/v1/space/satellites/page")
                        .param("pageNumber", "1")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.records[0].id").value(1));

        mockMvc.perform(get("/api/v1/space/satellites/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("测试我方星"))
                .andExpect(jsonPath("$[0].firstOrbit").doesNotExist());

        mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNumber\":1,\"pageSize\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.targets[0].id").value(2))
                .andExpect(jsonPath("$.targetIds").value(org.hamcrest.Matchers.contains(2, 4)));
    }

    /**
     * 筛选预览除返回最终目标页外，还必须给出各条件顺序叠加后的剩余数量，
     * 以便使用人员区分“条件无效”和“当前库确实没有命中”的情况。
     */
    @Test
    void returnsSequentialTargetFilterBreakdown() throws Exception {
        mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"belongs\":[\"CN\"],\"minWeight\":50,"
                                + "\"epochStartTime\":\"2000-12-31T00:00:00Z\","
                                + "\"epochEndTime\":\"2001-12-31T23:59:59Z\","
                                + "\"excludeNameRegex\":\"二号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filterBreakdown.belongsHit").value(1))
                .andExpect(jsonPath("$.filterBreakdown.weightHit").value(1))
                .andExpect(jsonPath("$.filterBreakdown.epochHit").value(1))
                .andExpect(jsonPath("$.filterBreakdown.excludeHit").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void filtersTargetPreviewPageByOrbitEpochRange() throws Exception {
        mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"epochStartTime\":\"1999-12-31T00:00:00Z\",\"epochEndTime\":\"2000-12-31T23:59:59Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.targets[0].id").value(2));
    }

    @Test
    void requiresTargetsToBeStrictlyHeavierThanMinimumWeight() throws Exception {
        mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minWeight\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    /**
     * 任务规划时间与发射日期不参与目标筛选（2026-09-08 集成方确认的 WinTool 对齐口径）：
     * 带与不带任务时间的候选页总量必须一致，未发射目标（4 号）同样出现在候选页中。
     */
    @Test
    void planningTimeDoesNotFilterTargetPreview() throws Exception {
        org.springframework.test.web.servlet.MvcResult plainResult = mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNumber\":1,\"pageSize\":50}"))
                .andExpect(status().isOk())
                .andReturn();
        org.springframework.test.web.servlet.MvcResult withTimeResult = mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planningStartTime\":\"2000-01-01T12:00:00Z\","
                                + "\"planningEndTime\":\"2000-01-01T12:05:00Z\","
                                + "\"pageNumber\":1,\"pageSize\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[?(@.id == 4)]").exists())
                .andReturn();

        com.jayway.jsonpath.DocumentContext plainContext = com.jayway.jsonpath.JsonPath.parse(
                plainResult.getResponse().getContentAsString());
        com.jayway.jsonpath.DocumentContext withTimeContext = com.jayway.jsonpath.JsonPath.parse(
                withTimeResult.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(withTimeContext.read("$.total", Long.class))
                .isEqualTo(plainContext.read("$.total", Long.class));
    }

    /**
     * 历元约束采用 TLE 的 YYDDD 下限且为严格大于，属于用户筛选条件，继续生效。
     * 任务规划时间与发射日期不再参与筛选：带任务时间的候选页结果与不带时一致，
     * 星历时效仅在真实规划响应中以风险提示输出。
     */
    @Test
    void appliesEpochCutoffWithoutPlanningTimeFilter() throws Exception {
        targetMapper.insert(tleTarget(5L, "历元恰为23340的目标", LocalDateTime.of(2023, 12, 6, 0, 0)));
        targetMapper.insert(tleTarget(6L, "历元晚于23340的目标", LocalDateTime.of(2023, 12, 6, 0, 1)));

        mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minWeight\":250,\"minTleEpochCode\":\"23340\","
                                + "\"maxApproximationDays\":14,"
                                + "\"planningStartTime\":\"2026-01-18T13:43:41.312Z\","
                                + "\"planningEndTime\":\"2026-01-18T13:58:56.749Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.targets[0].id").value(6))
                .andExpect(jsonPath("$.filterBreakdown.epochConstraintHit").value(1))
                .andExpect(jsonPath("$.filterBreakdown.excludeHit").value(1));
    }

    /** 超出建议近似时长的开普勒星历仍应进入计算，并在响应中保留可审计的中文风险提示。 */
    @Test
    void calculatesOutOfRangeEphemerisAndReturnsWarningInsteadOfFilteringItOut() throws Exception {
        SatelliteEntity satellite = satelliteMapper.selectById(1L);
        satellite.setPayloadJson("{\"payloadType\":\"SPACE\",\"distance\":[0,3000000],\"distanceUnit\":\"M\",\"angleRate\":1,\"sunAvoidHalfAngle\":0}");
        satelliteMapper.updateById(satellite);
        com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity target = targetMapper.selectById(2L);
        // 仅早于任务开始 1 秒，既稳定制造“超出 0 天建议时效”的风险，又不改变该用例的可见几何。
        target.setEpochTime(LocalDateTime.of(2000, 1, 1, 11, 59, 59));
        target.setMeanAnomaly(BigDecimal.ONE);
        targetMapper.updateById(target);

        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[2],"
                                + "\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\","
                                + "\"maxApproximationDays\":0,\"coarseStepSec\":1,\"fineStepSec\":1,"
                                + "\"observationConstraints\":[{\"satelliteIds\":[1],\"minDurationSec\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].targetId").value("2"))
                .andExpect(jsonPath("$.targetFilterBreakdown.ephemerisWarningHit").value(1))
                .andExpect(jsonPath("$.ephemerisWarnings[0].targetId").value("2"))
                .andExpect(jsonPath("$.ephemerisWarnings[0].reason").value(org.hamcrest.Matchers.containsString("超出建议近似传播时长")));
    }

    /**
     * 页面已筛选出的目标 ID 是规划输入的唯一来源。即使调用方仍附带旧版筛选字段，
     * 规划也不能第二次按这些字段缩小集合，否则页面显示的目标数会与实际计算数不一致。
     */
    @Test
    void usesExplicitTargetIdsWithoutReapplyingLegacyTargetFilters() throws Exception {
        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[2],"
                                + "\"belongs\":[\"不存在的国籍\"],\"minWeight\":999999,"
                                + "\"minTleEpochCode\":\"23340\",\"excludeNameRegex\":\"测试目标\","
                                + "\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\","
                                + "\"coarseStepSec\":1,\"fineStepSec\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetFilterBreakdown.excludeHit").value(1));
    }

    /**
     * 未发射目标不再按任务时间排除（2026-09-08 集成方确认）：显式选择后仍进入传播预检。
     * 该目标的 TLE 字段故意不完整，预检失败应落入 skippedPairs（PRECHECK）并可追溯，
     * 而不是静默消失或令整次任务失败。
     */
    @Test
    void includesUnlaunchedExplicitTargetAndSkipsItOnInvalidTle() throws Exception {
        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[4],"
                                + "\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\","
                                + "\"coarseStepSec\":1,\"fineStepSec\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty())
                .andExpect(jsonPath("$.totalSkippedPairs").value(1))
                .andExpect(jsonPath("$.skippedPairs[0].targetId").value("4"))
                .andExpect(jsonPath("$.skippedPairs[0].stage").value("PRECHECK"));
    }

    /** 国籍筛选可选项必须来自当前目标表 BELONG_ 实际值，并返回各值的数量供页面提示。 */
    @Test
    void returnsBelongOptionsFromTargetDataDictionary() throws Exception {
        mockMvc.perform(get("/api/v1/space/targets/belong-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CN"))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[1].code").value("US"))
                .andExpect(jsonPath("$[1].count").value(1))
                .andExpect(jsonPath("$[0].description").value(org.hamcrest.Matchers.containsString("BELONG_")));
    }

    @Test
    void savesSpacePayloadConfigurationForSatellite() throws Exception {
        mockMvc.perform(put("/api/v1/space/satellites/1/payload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minDistanceKm\":1000,\"maxDistanceKm\":4000,"
                                + "\"azimuthMinDeg\":90,\"azimuthMaxDeg\":270,"
                                + "\"elevationMinDeg\":-4,\"elevationMaxDeg\":60,"
                                + "\"angleRateDegPerSec\":0.3,\"sunAvoidHalfAngleDeg\":25,"
                                + "\"startupAngleRateDegPerSec\":0.07}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadType").value("SPACE"))
                .andExpect(jsonPath("$.minDistanceKm").value(1000))
                .andExpect(jsonPath("$.maxDistanceKm").value(4000))
                .andExpect(jsonPath("$.minDistanceM").value(1000000))
                .andExpect(jsonPath("$.maxDistanceM").value(4000000))
                .andExpect(jsonPath("$.azimuthMinDeg").value(90))
                .andExpect(jsonPath("$.azimuthMaxDeg").value(270))
                .andExpect(jsonPath("$.elevationMinDeg").value(-4))
                .andExpect(jsonPath("$.elevationMaxDeg").value(60))
                .andExpect(jsonPath("$.angleRateDegPerSec").value(0.3))
                .andExpect(jsonPath("$.sunAvoidHalfAngleDeg").value(25))
                .andExpect(jsonPath("$.startupAngleRateDegPerSec").value(0.07));

        mockMvc.perform(get("/api/v1/space/satellites/1/payload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.minDistanceKm").value(1000))
                .andExpect(jsonPath("$.minDistanceM").value(1000000))
                .andExpect(jsonPath("$.startupAngleRateDegPerSec").value(0.07));
    }

    /**
     * 对空与对地载荷必须各自保留。后保存 SPACE 时会成为活动载荷，
     * 但不能删除已经保存的 GROUND 配置，避免旧版切换类型导致的参数丢失。
     */
    @Test
    void preservesBothPayloadProfilesWhenSwitchingActivePayloadType() throws Exception {
        mockMvc.perform(put("/api/v1/space/satellites/1/payload-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payloadType\":\"GROUND\",\"minDistanceKm\":800,\"maxDistanceKm\":3500,"
                                + "\"azimuthMinDeg\":null,\"azimuthMaxDeg\":null,\"elevationMinDeg\":-91,"
                                + "\"elevationMaxDeg\":0,\"angleRateDegPerSec\":1,"
                                + "\"sunAvoidHalfAngleDeg\":45,\"startupAngleRateDegPerSec\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePayloadType").value("GROUND"))
                .andExpect(jsonPath("$.profiles.length()").value(2))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].minDistanceKm").value(800.0))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].maxDistanceKm").value(3500.0))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].elevationMinDeg").value(-91.0))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].elevationMaxDeg").value(0.0))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].angleRateDegPerSec").value(1.0))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].sunAvoidHalfAngleDeg").value(45.0))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].startupAngleRateDegPerSec").value(1.0))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].legacyGroundProfile").value(false));

        mockMvc.perform(put("/api/v1/space/satellites/1/payload-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payloadType\":\"SPACE\",\"minDistanceKm\":1000,\"maxDistanceKm\":4000,"
                                + "\"azimuthMinDeg\":90,\"azimuthMaxDeg\":270,\"elevationMinDeg\":-4,"
                                + "\"elevationMaxDeg\":60,\"angleRateDegPerSec\":0.3,"
                                + "\"sunAvoidHalfAngleDeg\":90,\"startupAngleRateDegPerSec\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePayloadType").value("SPACE"))
                .andExpect(jsonPath("$.profiles.length()").value(2))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'GROUND')].minDistanceKm").value(800.0));

        mockMvc.perform(get("/api/v1/space/satellites/1/payload-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePayloadType").value("SPACE"))
                .andExpect(jsonPath("$.profiles[?(@.payloadType == 'SPACE')].minDistanceKm").value(1000.0));
    }

    /** 验证数据库中的标准载荷配置可按接口字段读取。 */
    @Test
    void mapsCanonicalMetrePayloadConfigurationToResponseFields() throws Exception {
        mockMvc.perform(get("/api/v1/space/satellites/3/payload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.payloadType").value("SPACE"))
                .andExpect(jsonPath("$.minDistanceKm").value(0))
                .andExpect(jsonPath("$.maxDistanceKm").value(2000))
                .andExpect(jsonPath("$.minDistanceM").value(0))
                .andExpect(jsonPath("$.maxDistanceM").value(2000000))
                .andExpect(jsonPath("$.angleRateDegPerSec").value(0))
                .andExpect(jsonPath("$.sunAvoidHalfAngleDeg").value(0))
                .andExpect(jsonPath("$.azimuthMinDeg").doesNotExist())
                .andExpect(jsonPath("$.startupAngleRateDegPerSec").doesNotExist());
    }

    @Test
    void rejectsCustomPeriodWhenGuardOnlyLeavesPartialLink() throws Exception {
        mockMvc.perform(post("/api/v1/space/downlink/validate-window")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":1,\"satelliteIds\":[1],\"preGuardSec\":1,\"postGuardSec\":0,\"allowedGapSec\":0,\"stations\":[{\"stationId\":\"ST-001\"}],\"links\":[{\"satelliteId\":1,\"stationId\":\"ST-001\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satisfied").value(false));
    }

    @Test
    void plansOnlyTheExplicitlySelectedSatelliteStationLink() throws Exception {
        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":1,\"satelliteIds\":[1],\"stations\":[{\"stationId\":\"ST-001\"}],\"links\":[{\"satelliteId\":1,\"stationId\":\"ST-001\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satelliteStationWindows[0].satelliteId").value(1))
                .andExpect(jsonPath("$.satelliteStationWindows[0].stationId").value("ST-001"));
    }

    /** 下传仅由站点与卫星光照约束决定；GROUND profile 的距离限制不能再裁剪下传窗口。 */
    @Test
    void ignoresGroundPayloadProfileWhenPlanningDownlinkApi() throws Exception {
        SatelliteEntity satellite = satelliteMapper.selectById(1L);
        satellite.setPayloadJson(payloadProfilesJson(1_000, 2_000));
        satelliteMapper.updateById(satellite);
        relocateStationDirectlyBelowSatellite(satellite, Instant.parse("2000-01-01T12:00:00Z"));

        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:01:00Z\","
                                + "\"coarseStepSec\":1,\"fineStepSec\":1,\"preGuardSec\":0,\"postGuardSec\":0,"
                                + "\"allowedGapSec\":0,\"satelliteIds\":[1],"
                                + "\"stations\":[{\"stationId\":\"ST-001\",\"maxElevationDeg\":90}],"
                                + "\"links\":[{\"satelliteId\":1,\"stationId\":\"ST-001\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satelliteStationWindows[0].windows.length()").value(1));
    }

    /** 卫星未配置载荷或仅配置 SPACE 载荷时，下传接口不得再报缺少 GROUND profile。 */
    @Test
    void plansDownlinkWhenSatellitePayloadIsEmpty() throws Exception {
        SatelliteEntity satellite = satelliteMapper.selectById(1L);
        satellite.setPayloadJson(null);
        satelliteMapper.updateById(satellite);

        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\","
                                + "\"coarseStepSec\":1,\"fineStepSec\":1,\"satelliteIds\":[1],"
                                + "\"stations\":[{\"stationId\":\"ST-001\"}],"
                                + "\"links\":[{\"satelliteId\":1,\"stationId\":\"ST-001\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satelliteStationWindows[0].satelliteId").value(1))
                .andExpect(jsonPath("$.skippedLinks").isArray())
                .andExpect(jsonPath("$.totalSkippedLinks").value(0));
    }

    @Test
    void plansOnlyExplicitlySelectedSatelliteStationLinks() throws Exception {
        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":1,\"satelliteIds\":[1,3],\"stations\":[{\"stationId\":\"ST-001\"},{\"stationId\":\"ST-002\"}],\"links\":[{\"satelliteId\":1,\"stationId\":\"ST-001\"},{\"satelliteId\":3,\"stationId\":\"ST-002\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satelliteStationWindows.length()").value(2))
                .andExpect(jsonPath("$.satelliteStationWindows[0].satelliteId").value(1))
                .andExpect(jsonPath("$.satelliteStationWindows[0].stationId").value("ST-001"))
                .andExpect(jsonPath("$.satelliteStationWindows[1].satelliteId").value(3))
                .andExpect(jsonPath("$.satelliteStationWindows[1].stationId").value("ST-002"));
    }

    @Test
    void rejectsStationWithInvertedElevationRange() throws Exception {
        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":1,\"satelliteIds\":[1],\"stations\":[{\"stationId\":\"ST-001\",\"minElevationDeg\":91,\"maxElevationDeg\":5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("地面站仰角范围无效"));
    }

    @Test
    void filtersSatelliteCatalogPageByKeyword() throws Exception {
        mockMvc.perform(get("/api/v1/space/satellites/page")
                        .param("pageNumber", "1")
                        .param("pageSize", "1")
                        .param("keyword", "NOT-FOUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void rejectsNullSatelliteSelectionOnStation() throws Exception {
        mockMvc.perform(post("/api/v1/space/downlink/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"stations\":[{\"stationId\":\"ST-001\",\"satelliteIds\":null}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请至少选择一颗我方卫星"));
    }

    @Test
    void rejectsSpecifiedTargetIdWhenTargetDoesNotExist() throws Exception {
        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[999],\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("存在未找到的空间目标"));
    }

    @Test
    void plansOnlyAllTargetsMatchingTheSubmittedFilters() throws Exception {
        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":1,\"belongs\":[\"US\"],\"minWeight\":50,\"maxWeight\":60,\"epochStartTime\":\"1999-12-31T00:00:00Z\",\"epochEndTime\":\"2000-12-31T23:59:59Z\",\"observationConstraints\":[{\"satelliteIds\":[1],\"minDurationSec\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());
    }

    @Test
    void returnsReadableNamesAndSingleObservationTypeForVisibilityResults() throws Exception {
        SatelliteEntity satellite = satelliteMapper.selectById(1L);
        satellite.setPayloadJson("{\"payloadType\":\"SPACE\",\"distance\":[0,3000000],\"distanceUnit\":\"M\",\"angleRate\":1,\"sunAvoidHalfAngle\":0}");
        satelliteMapper.updateById(satellite);
        com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity target = targetMapper.selectById(2L);
        target.setMeanAnomaly(BigDecimal.ONE);
        targetMapper.updateById(target);

        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[2],\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":1,\"observationConstraints\":[{\"satelliteIds\":[1],\"minDurationSec\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].targetName").value("测试目标"))
                .andExpect(jsonPath("$.results[0].observerSatelliteNames[0]").value("测试我方星"))
                .andExpect(jsonPath("$.results[0].observationType").value("SINGLE"))
                .andExpect(jsonPath("$.results[0].observationTypeName").value("单星观测"))
                .andExpect(jsonPath("$.results[0].arcs[0].startTime").value("2000-01-01T12:00:00.000Z"))
                .andExpect(jsonPath("$.results[0].arcs[0].endTime").value("2000-01-01T12:00:05.000Z"));
    }

    /** 验证自定义光照组合必须同时明确观测星和目标允许的状态。 */
    @Test
    void rejectsCustomIlluminationConstraintWithoutBothStateSelections() throws Exception {
        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[2],\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\",\"coarseStepSec\":1,\"fineStepSec\":0.001,\"illuminationConstraint\":{\"mode\":\"CUSTOM\",\"observerStates\":[],\"targetStates\":[\"SUNLIT\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("自定义光照约束必须至少选择一个观测星状态"));
    }

    /** 验证规划响应携带可读光照状态详情，并统一以三位毫秒返回时间。 */
    @Test
    void returnsReadableIlluminationStatesWithMillisecondTimes() throws Exception {
        SatelliteEntity satellite = satelliteMapper.selectById(1L);
        satellite.setPayloadJson("{\"payloadType\":\"SPACE\",\"distance\":[0,3000000],\"distanceUnit\":\"M\",\"angleRate\":1,\"sunAvoidHalfAngle\":0}");
        satelliteMapper.updateById(satellite);
        com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity target = targetMapper.selectById(2L);
        target.setMeanAnomaly(BigDecimal.ONE);
        targetMapper.updateById(target);

        mockMvc.perform(post("/api/v1/space/visibility/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[2],\"startTime\":\"2000-01-01T12:00:00.000Z\",\"endTime\":\"2000-01-01T12:00:05.000Z\",\"coarseStepSec\":1,\"fineStepSec\":0.001,\"illuminationConstraint\":{\"mode\":\"ANY\"},\"observationConstraints\":[{\"satelliteIds\":[1],\"minDurationSec\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].arcs[0].observerIlluminations[0].satelliteName").value("测试我方星"))
                .andExpect(jsonPath("$.results[0].arcs[0].targetIllumination.targetName").value("测试目标"))
                .andExpect(jsonPath("$.results[0].arcs[0].startTime").value("2000-01-01T12:00:00.000Z"));
    }

    @Test
    void rejectsInvalidTargetExcludeRegex() throws Exception {
        mockMvc.perform(post("/api/v1/space/targets/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludeNameRegex\":\"[\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("排除目标正则表达式无效"));
    }

    /**
     * 构造活动类型固定为 SPACE 的双 profile 数据，用于覆盖“下传仍强制读取 GROUND”的真实存储形式。
     * 入参按接口展示单位 km 传入，JSON 内显式转成 m，避免测试把单位换算问题混入链路验证。
     */
    private String payloadProfilesJson(int groundMinDistanceKm, int groundMaxDistanceKm) {
        return "{\"activePayloadType\":\"SPACE\",\"payloadProfiles\":{"
                + "\"SPACE\":{\"payloadType\":\"SPACE\",\"distance\":[0,2000000],"
                + "\"distanceUnit\":\"M\",\"angleRate\":0,\"sunAvoidHalfAngle\":0},"
                + "\"GROUND\":{\"payloadType\":\"GROUND\",\"distance\":["
                + (groundMinDistanceKm * 1_000) + "," + (groundMaxDistanceKm * 1_000) + "],"
                + "\"distanceUnit\":\"M\",\"elevation\":[-91,0],\"angleRate\":10,"
                + "\"sunAvoidHalfAngle\":0}}}";
    }

    /** 构造仅供目录筛选用的 TLE 目标；本用例不进入传播，因此无需填写两行根数。 */
    private com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity tleTarget(
            Long id, String name, LocalDateTime epochTime) {
        com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity target =
                new com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity();
        target.setId(id);
        target.setName(name);
        target.setModelType("TLE_SGP4");
        target.setBelong("US");
        target.setWeight(new BigDecimal("300"));
        target.setLaunchDate(java.time.LocalDate.of(2020, 1, 1));
        target.setEpochTime(epochTime);
        return target;
    }

    /**
     * 从实际轨道外推结果反算站点经纬度，使接口用例稳定获得“站点仰角通过”的输入。
     * 这样测试仅检验 GROUND 距离下限是否被传递，不依赖固定 GMST 常数或 EME2000 到 TEME 的岁差转换。
     */
    private void relocateStationDirectlyBelowSatellite(SatelliteEntity satellite, Instant instant) {
        Vector3 ecef = GroundStationGeometry.eciToEcef(
                orbitStateProviderFactory.fromSatellite(satellite).positionAt(instant), instant);
        double longitudeDeg = Math.toDegrees(Math.atan2(ecef.getY(), ecef.getX()));
        double latitudeDeg = Math.toDegrees(Math.atan2(ecef.getZ(),
                Math.sqrt(ecef.getX() * ecef.getX() + ecef.getY() * ecef.getY())));
        StationEntity station = stationMapper.selectById("ST-001");
        station.setLongitude(BigDecimal.valueOf(longitudeDeg));
        station.setLatitude(BigDecimal.valueOf(latitudeDeg));
        stationMapper.updateById(station);
    }
}
