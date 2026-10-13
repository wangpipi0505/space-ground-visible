package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.KeplerOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.SpaceOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.TleOrbitStateProvider;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 空间应用入口必须返回工具桥接器，防止定义了公共工具却仍绕过工具执行生产查询。 */
class OrbitStateProviderFactoryTest {
    @Test
    void satelliteUsesThePublicPropagationToolBridge() {
        SatelliteEntity entity = satellite();
        OrbitStateProvider provider = new OrbitStateProviderFactory(10).fromSatellite(entity);
        assertThat(provider.getClass().getSimpleName()).isEqualTo("SpaceOrbitStateProvider");
        verifyKepler(provider, entity, 10);
    }

    /** 目标入口也保留六根数顺序、UTC纳秒历元和原配置步长，并真实经过公共工具。 */
    @Test
    void targetUsesTheSameElementsAndConfiguredStep() {
        SatelliteEntity satellite = satellite();
        TargetEntity target = new TargetEntity();
        target.setModelType(satellite.getModelType());
        target.setSemiMajorAxis(satellite.getSemiMajorAxis());
        target.setEccentricity(satellite.getEccentricity());
        target.setInclination(satellite.getInclination());
        target.setRaan(satellite.getRaan());
        target.setArgumentOfPerigee(satellite.getArgumentOfPerigee());
        target.setMeanAnomaly(satellite.getMeanAnomaly());
        target.setEpochTime(satellite.getEpochTime());
        OrbitStateProvider provider = new OrbitStateProviderFactory(3.5).fromTarget(target);
        assertThat(provider).isInstanceOf(SpaceOrbitStateProvider.class);
        verifyKepler(provider, satellite, 3.5);
    }

    /** 两个生产实体入口的 TLE 均保留原文，且无需读取缺省六根数字段或验证无关 RK4 配置。 */
    @Test
    void satelliteAndTargetTlePreserveOriginalTextAndPropagation() {
        String first = "1 A0000U 26067CY  26222.88571527  .00004719  00000-0  21676-3 0  9994";
        String second = "2 A0000  97.4613 180.8071 0008233 161.3775 198.7764 15.20848640 20016";
        SatelliteEntity satellite = new SatelliteEntity();
        satellite.setModelType("tle_sgp4");
        satellite.setFirstOrbit(first);
        satellite.setSecondOrbit(second);
        TargetEntity target = new TargetEntity();
        target.setModelType("TLE_SGP4");
        target.setFirstOrbit(first);
        target.setSecondOrbit(second);
        OrbitStateProviderFactory factory = new OrbitStateProviderFactory(0);
        for (OrbitStateProvider provider : new OrbitStateProvider[]{
                factory.fromSatellite(satellite), factory.fromTarget(target)}) {
            assertThat(provider).isInstanceOf(SpaceOrbitStateProvider.class);
            OrbitDefinition.Tle definition = (OrbitDefinition.Tle) ((SpaceOrbitStateProvider) provider)
                    .tool().definition();
            assertThat(definition.firstLine).isEqualTo(first);
            assertThat(definition.secondLine).isEqualTo(second);
            assertThat(definition.model).isEqualTo(OrbitDefinition.Model.TLE_SGP4);
            assertThat(definition.outputFrame).isEqualTo(OrbitDefinition.Frame.TEME);
            verifyHistory(new TleOrbitStateProvider(first, second), provider);
        }
    }

    /** 模型/缺参、原六根数检查及非法 TLE 的已有错误消息须继续优先于新增定义校验。 */
    @Test
    void legacyInvalidInputMessagesRemainUnchanged() {
        OrbitStateProviderFactory factory = new OrbitStateProviderFactory(10);
        SatelliteEntity missing = satellite();
        missing.setMeanAnomaly(null);
        assertThatThrownBy(() -> factory.fromSatellite(missing)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("轨道模型或轨道参数无效");
        TargetEntity invalidModel = new TargetEntity();
        invalidModel.setModelType("TWO_BODY");
        assertThatThrownBy(() -> factory.fromTarget(invalidModel)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("轨道模型或轨道参数无效");
        assertThatThrownBy(() -> new OrbitStateProviderFactory(0).fromSatellite(satellite()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("六根数轨道或RK4积分步长无效");
        SatelliteEntity invalidNumber = satellite();
        invalidNumber.setSemiMajorAxis(BigDecimal.ZERO);
        assertThatThrownBy(() -> factory.fromSatellite(invalidNumber)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("六根数轨道或RK4积分步长无效");
        TargetEntity invalidTle = new TargetEntity();
        invalidTle.setModelType("TLE_SGP4");
        invalidTle.setFirstOrbit("bad");
        invalidTle.setSecondOrbit("bad");
        assertThatThrownBy(() -> factory.fromTarget(invalidTle)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TLE 两行根数格式无效");
    }

    private void verifyKepler(OrbitStateProvider actual, SatelliteEntity entity, double step) {
        OrbitDefinition.Elements definition = (OrbitDefinition.Elements) ((SpaceOrbitStateProvider) actual)
                .tool().definition();
        assertThat(definition.model).isEqualTo(OrbitDefinition.Model.SPACE_KEPLER_J2_RK4);
        assertThat(definition.inputFrame).isEqualTo(OrbitDefinition.Frame.EME2000);
        assertThat(definition.outputFrame).isEqualTo(OrbitDefinition.Frame.TEME);
        assertThat(definition.integrationStepSec).isEqualTo(step);
        assertThat(definition.epoch.instant()).isEqualTo(entity.getEpochTime().toInstant(ZoneOffset.UTC));
        verifyHistory(new KeplerOrbitStateProvider(entity.getSemiMajorAxis().doubleValue(),
                entity.getEccentricity().doubleValue(), entity.getInclination().doubleValue(),
                entity.getRaan().doubleValue(), entity.getArgumentOfPerigee().doubleValue(),
                entity.getMeanAnomaly().doubleValue(), entity.getEpochTime().toInstant(ZoneOffset.UTC), step), actual);
    }

    private void verifyHistory(OrbitStateProvider expected, OrbitStateProvider actual) {
        Instant epoch = Instant.parse("2026-07-01T10:00:00.123456789Z");
        for (long offset : new long[]{0, 60, 120, 59, 59, -3, 3600, 3601}) {
            Instant query = epoch.plusSeconds(offset).plusNanos(7);
            assertBits(expected.positionAt(query), actual.positionAt(query));
            assertBits(expected.velocityAt(query), actual.velocityAt(query));
        }
    }

    private void assertBits(Vector3 expected, Vector3 actual) {
        assertThat(Double.doubleToRawLongBits(actual.getX())).isEqualTo(Double.doubleToRawLongBits(expected.getX()));
        assertThat(Double.doubleToRawLongBits(actual.getY())).isEqualTo(Double.doubleToRawLongBits(expected.getY()));
        assertThat(Double.doubleToRawLongBits(actual.getZ())).isEqualTo(Double.doubleToRawLongBits(expected.getZ()));
    }

    private SatelliteEntity satellite() {
        SatelliteEntity entity = new SatelliteEntity();
        entity.setModelType("kepler_6");
        entity.setSemiMajorAxis(BigDecimal.valueOf(7000000));
        entity.setEccentricity(BigDecimal.valueOf(0.01));
        entity.setInclination(BigDecimal.valueOf(50));
        entity.setRaan(BigDecimal.valueOf(20));
        entity.setArgumentOfPerigee(BigDecimal.valueOf(30));
        entity.setMeanAnomaly(BigDecimal.valueOf(40));
        entity.setEpochTime(LocalDateTime.parse("2026-07-01T10:00:00.123456789"));
        return entity;
    }
}
