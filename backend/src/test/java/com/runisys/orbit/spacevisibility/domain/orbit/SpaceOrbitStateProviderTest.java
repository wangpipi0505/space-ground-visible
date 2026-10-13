package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T09 真实空间绑定回归。原 provider 各建独立实例、使用相同查询历史，逐坐标原始 bits 比较；
 * 不能拿同一可变 RK4 实例先后跑两套历史，也不能用容差掩盖接入引起的状态差异。
 */
class SpaceOrbitStateProviderTest {
    private static final Instant EPOCH = Instant.parse("2026-07-01T10:00:00.123456789Z");
    private static final String L1 = "1 25544U 98067A   24100.51782528  .00014321  00000+0  25813-3 0  9997";
    private static final String L2 = "2 25544  51.6396  46.5178 0004400  75.0248  50.3079 15.50175300448688";
    private static final String A1 = "1 A0000U 26067CY  26222.88571527  .00004719  00000-0  21676-3 0  9994";
    private static final String A2 = "2 A0000  97.4613 180.8071 0008233 161.3775 198.7764 15.20848640 20016";

    /** 标准 TLE 的位置、差分速度与显式状态，均须与原 SGP4/SDP4 查询逐 bits 一致。 */
    @Test
    void standardTlePreservesEveryCoordinateBit() {
        verifyHistory(new TleOrbitStateProvider(L1, L2),
                SpaceOrbitStateProvider.fromDefinition(new OrbitDefinition.Tle(L1, L2)),
                Instant.parse("2024-04-10T12:30:00Z"));
    }

    /** Alpha5 原文只在原 provider 内遮蔽编号，工具定义仍保留上传原文。 */
    @Test
    void alpha5TlePreservesOriginalTextAndEveryCoordinateBit() {
        SpaceOrbitStateProvider actual = SpaceOrbitStateProvider.fromTle(A1, A2);
        assertThat(((OrbitDefinition.Tle) actual.tool().definition()).firstLine).isEqualTo(A1);
        assertThat(((OrbitDefinition.Tle) actual.tool().definition()).secondLine).isEqualTo(A2);
        verifyHistory(new TleOrbitStateProvider(A1, A2), actual, EPOCH);
    }

    /** 原默认10秒 RK4、60秒锚点附近回退、重复、历元前和非整秒查询保持同样的缓存演进。 */
    @Test
    void defaultKeplerPreservesEveryCoordinateBit() {
        verifyHistory(kepler(10), SpaceOrbitStateProvider.fromElements(
                7000000, 0.01, 50, 20, 30, 40, EPOCH, 10), EPOCH);
    }

    /** 定义构建保留配置步长及 EME2000 输入/TEME 输出，未替换为解析二体或重建每次查询。 */
    @Test
    void configuredKeplerDefinitionPreservesParametersAndEveryCoordinateBit() {
        OrbitDefinition.Elements definition = elements(3.5);
        SpaceOrbitStateProvider actual = SpaceOrbitStateProvider.fromDefinition(definition);
        OrbitDefinition.Elements bound = (OrbitDefinition.Elements) actual.tool().definition();
        assertThat(bound.model).isEqualTo(OrbitDefinition.Model.SPACE_KEPLER_J2_RK4);
        assertThat(bound.inputFrame).isEqualTo(OrbitDefinition.Frame.EME2000);
        assertThat(bound.outputFrame).isEqualTo(OrbitDefinition.Frame.TEME);
        assertThat(bound.timeBasis).isEqualTo(OrbitTime.Basis.UTC_INSTANT);
        assertThat(bound.epoch.instant()).isEqualTo(EPOCH);
        assertThat(bound.integrationStepSec).isEqualTo(3.5);
        assertThat(bound.semiMajorAxisM).isEqualTo(7000000);
        assertThat(bound.eccentricity).isEqualTo(0.01);
        assertThat(bound.inclinationDeg).isEqualTo(50);
        assertThat(bound.raanDeg).isEqualTo(20);
        assertThat(bound.argumentOfPerigeeDeg).isEqualTo(30);
        assertThat(bound.meanAnomalyDeg).isEqualTo(40);
        verifyHistory(kepler(3.5), actual, EPOCH);
    }

    /** 用记录型原 provider 检查桥必须通过工具且保持仅位置、仅速度及±1秒前后顺序。 */
    @Test
    void bridgeQueriesToolWithoutAddingVelocityOrChangingDifferenceOrder() {
        List<String> calls = new ArrayList<String>();
        OrbitStateProvider original = new OrbitStateProvider() {
            public Vector3 positionAt(Instant time) {
                calls.add("position:" + time);
                return new Vector3(time.getEpochSecond(), time.getNano(), 0);
            }
            public Vector3 velocityAt(Instant time) {
                calls.add("velocity:" + time);
                return OrbitStateProvider.super.velocityAt(time);
            }
        };
        SpaceOrbitStateProvider bridge = new SpaceOrbitStateProvider(elements(10), original);
        bridge.positionAt(EPOCH);
        assertThat(calls).containsExactly("position:" + EPOCH);
        bridge.velocityAt(EPOCH);
        assertThat(calls).containsExactly("position:" + EPOCH, "velocity:" + EPOCH,
                "position:" + EPOCH.minusSeconds(1), "position:" + EPOCH.plusSeconds(1));
        assertThatThrownBy(() -> bridge.tool().positionAt(OrbitTime.utc(EPOCH), OrbitDefinition.Frame.J2000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("坐标口径不匹配");
        assertThat(calls).hasSize(4);
    }

    /** 旧原始参数入口先运行原构造器校验，不能被定义的步长/历元校验抢先替换异常。 */
    @Test
    void compatibilityInputErrorsStillComeFromOriginalProviders() {
        assertThatThrownBy(() -> SpaceOrbitStateProvider.fromElements(
                7000000, 0.01, 50, 20, 30, 40, EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("六根数轨道或RK4积分步长无效");
        assertThatThrownBy(() -> SpaceOrbitStateProvider.fromElements(
                7000000, 0.01, 50, 20, 30, 40, null, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("六根数轨道或RK4积分步长无效");
        assertThatThrownBy(() -> SpaceOrbitStateProvider.fromTle("bad", "bad"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TLE 两行根数格式无效");
        assertThatThrownBy(() -> SpaceOrbitStateProvider.fromDefinition(new OrbitDefinition.Elements(
                OrbitDefinition.Model.TWO_BODY, 7000000, 0.01, 50, 20, 30, 40,
                OrbitTime.legacyJ2000(0, 0), 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("空间绑定不接受");
        assertThatThrownBy(() -> SpaceOrbitStateProvider.fromElements(
                7000000, 0.01, 50, 20, 30, 40, EPOCH, 10).positionAt(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("外推时刻不能为空");
    }

    /** 每轮先位置再速度：两实例各自执行同一历史，显式状态也按原位置后速度对齐。 */
    private void verifyHistory(OrbitStateProvider expected, SpaceOrbitStateProvider actual, Instant epoch) {
        for (long offset : Arrays.asList(0L, 60L, 120L, 59L, 59L, -3L, 3600L, 3601L)) {
            Instant query = epoch.plusSeconds(offset).plusNanos(7);
            assertBits(expected.positionAt(query), actual.positionAt(query));
            assertBits(expected.velocityAt(query), actual.velocityAt(query));
        }
        Instant query = epoch.plusSeconds(3602).plusNanos(13);
        Vector3 expectedPosition = expected.positionAt(query);
        Vector3 expectedVelocity = expected.velocityAt(query);
        OrbitState state = actual.tool().stateAt(OrbitTime.utc(query), OrbitDefinition.Frame.TEME);
        assertBits(expectedPosition, state.positionM);
        assertBits(expectedVelocity, state.velocityMps);
    }

    private void assertBits(Vector3 expected, Vector3 actual) {
        assertThat(Double.doubleToRawLongBits(actual.getX())).isEqualTo(Double.doubleToRawLongBits(expected.getX()));
        assertThat(Double.doubleToRawLongBits(actual.getY())).isEqualTo(Double.doubleToRawLongBits(expected.getY()));
        assertThat(Double.doubleToRawLongBits(actual.getZ())).isEqualTo(Double.doubleToRawLongBits(expected.getZ()));
    }

    private KeplerOrbitStateProvider kepler(double step) {
        return new KeplerOrbitStateProvider(7000000, 0.01, 50, 20, 30, 40, EPOCH, step);
    }

    private OrbitDefinition.Elements elements(double step) {
        return new OrbitDefinition.Elements(OrbitDefinition.Model.SPACE_KEPLER_J2_RK4,
                7000000, 0.01, 50, 20, 30, 40, OrbitTime.utc(EPOCH), step);
    }
}
