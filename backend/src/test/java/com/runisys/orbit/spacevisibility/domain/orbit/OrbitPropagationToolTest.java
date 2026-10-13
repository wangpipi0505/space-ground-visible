package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T08 的入口契约：验证单位、坐标与原生时间，不在测试中替换或实现传播公式。 */
class OrbitPropagationToolTest {
    private static final Instant EPOCH = Instant.parse("2026-07-01T10:00:00.123456789Z");

    /** 位置查询不能触发速度差分，否则旧窗口的传播器缓存调用顺序会改变。 */
    @Test
    void positionOnlyPreservesCallOrderAndStateKeepsSiUnits() {
        List<String> calls = new ArrayList<String>();
        Vector3 positionM = new Vector3(7000000, 20, 30);
        Vector3 velocityMps = new Vector3(1, 7500, 3);
        OrbitDefinition.Elements definition = spaceDefinition();
        OrbitTime time = OrbitTime.utc(EPOCH.plusNanos(1));
        OrbitPropagationTool tool = new OrbitPropagationTool(definition, new OrbitPropagationTool.NativePropagation() {
            public Vector3 positionAt(OrbitTime query) {
                assertThat(query).isSameAs(time);
                calls.add("position");
                return positionM;
            }
            public Vector3 velocityAt(OrbitTime query) {
                assertThat(query).isSameAs(time);
                calls.add("velocity");
                return velocityMps;
            }
        });
        assertThat(tool.positionAt(time, OrbitDefinition.Frame.TEME)).isSameAs(positionM);
        assertThat(calls).containsExactly("position");
        OrbitState state = tool.stateAt(time, OrbitDefinition.Frame.TEME);
        assertThat(calls).containsExactly("position", "position", "velocity");
        assertThat(state.positionM).isSameAs(positionM);
        assertThat(state.velocityMps).isSameAs(velocityMps);
        assertThat(state.time).isSameAs(time);
        assertThat(state.frame).isEqualTo(OrbitDefinition.Frame.TEME);
        assertThat(tool.definition()).isSameAs(definition);
        assertThat(tool.velocityAt(time, OrbitDefinition.Frame.TEME)).isSameAs(velocityMps);
        assertThat(calls).containsExactly("position", "position", "velocity", "velocity");
    }

    /** 旧偏移同样按原始bits保存；无效时间在新入口拒绝，不能污染原生传播器。 */
    @Test
    void preservesOffsetBitsAndRejectsNonFiniteTime() {
        double offset = -0.0;
        OrbitTime epoch = OrbitTime.legacyJ2000(0, offset);
        OrbitDefinition.Elements definition = new OrbitDefinition.Elements(OrbitDefinition.Model.J2_SECULAR,
                7000000, 0.01, 50, 20, 30, 40, epoch, 0);
        assertThat(Double.doubleToRawLongBits(epoch.utcOffsetSec())).isEqualTo(Double.doubleToRawLongBits(offset));
        assertThat(Double.doubleToRawLongBits(definition.utcOffsetSec)).isEqualTo(Double.doubleToRawLongBits(offset));
        assertThatThrownBy(() -> OrbitTime.legacyJ2000(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrbitTime.legacyJ2000(0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 使用不能无损往返纳秒 Instant 的 double 和负零，保证无截断、偏移或额外转换。 */
    @Test
    void legacySecondsReachAdapterWithIdenticalBits() {
        double[] values = {Math.nextUp(0.123456789012345), -0.0, 836000000.1234567};
        for (double value : values) {
            OrbitTime time = OrbitTime.legacyJ2000(value, 28800);
            OrbitDefinition.Elements definition = new OrbitDefinition.Elements(
                    OrbitDefinition.Model.TWO_BODY, 7000000, 0.01, 50, 20, 30, 40,
                    OrbitTime.legacyJ2000(100, 28800), 0);
            OrbitPropagationTool tool = new OrbitPropagationTool(definition, new OrbitPropagationTool.NativePropagation() {
                public Vector3 positionAt(OrbitTime query) {
                    assertThat(Double.doubleToRawLongBits(query.legacySeconds()))
                            .isEqualTo(Double.doubleToRawLongBits(value));
                    assertThat(query.utcOffsetSec()).isEqualTo(28800);
                    return Vector3.ZERO;
                }
                public Vector3 velocityAt(OrbitTime query) { return Vector3.ZERO; }
            });
            tool.positionAt(time, OrbitDefinition.Frame.J2000);
            assertThatThrownBy(time::instant).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UTC_INSTANT");
        }
    }

    /** 误用时间口径、偏移约定或坐标时，在调用原传播器前给出明确错误。 */
    @Test
    void rejectsFrameAndTimeMismatchBeforePropagation() {
        OrbitPropagationTool tool = new OrbitPropagationTool(spaceDefinition(), neverCalled());
        assertThatThrownBy(() -> tool.positionAt(OrbitTime.utc(EPOCH), OrbitDefinition.Frame.J2000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TEME");
        assertThatThrownBy(() -> tool.positionAt(OrbitTime.legacyJ2000(1, 0), OrbitDefinition.Frame.TEME))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTC_INSTANT");
        OrbitDefinition.Cartesian endpoint = new OrbitDefinition.Cartesian(
                OrbitTime.legacyJ2000(1, 28800), new Vector3(7000000, 0, 0), new Vector3(0, 7500, 0));
        OrbitPropagationTool endpointTool = new OrbitPropagationTool(endpoint, neverCalled());
        assertThatThrownBy(() -> endpointTool.positionAt(OrbitTime.legacyJ2000(2, 0), OrbitDefinition.Frame.J2000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("utcOffsetSec");
        assertThatThrownBy(() -> OrbitTime.utc(EPOCH).legacySeconds())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LEGACY_J2000_SECONDS");
    }

    /** 标签对应真实已有模型，六根数角度保持度；端点二体最大步长保持1秒。 */
    @Test
    void definitionsExposeNativeParametersAndModels() {
        OrbitDefinition.Elements space = spaceDefinition();
        assertThat(space.model).isEqualTo(OrbitDefinition.Model.SPACE_KEPLER_J2_RK4);
        assertThat(space.inputFrame).isEqualTo(OrbitDefinition.Frame.EME2000);
        assertThat(space.outputFrame).isEqualTo(OrbitDefinition.Frame.TEME);
        assertThat(space.integrationStepSec).isEqualTo(10);
        assertThat(space.epoch.instant()).isEqualTo(EPOCH);
        assertThat(space.semiMajorAxisM).isEqualTo(7000000);
        assertThat(space.inclinationDeg).isEqualTo(50);
        for (OrbitDefinition.Model model : new OrbitDefinition.Model[]{OrbitDefinition.Model.TWO_BODY,
                OrbitDefinition.Model.J2_SECULAR, OrbitDefinition.Model.J2_NUMERICAL}) {
            OrbitDefinition.Elements legacy = new OrbitDefinition.Elements(model, 7000000, 0.01, 50, 20, 30, 40,
                    OrbitTime.legacyJ2000(0.123456789012345, 0), 0);
            assertThat(legacy.outputFrame).isEqualTo(OrbitDefinition.Frame.J2000);
            assertThat(legacy.epoch.legacySeconds()).isEqualTo(0.123456789012345);
        }
        OrbitDefinition.Cartesian endpoint = new OrbitDefinition.Cartesian(
                OrbitTime.legacyJ2000(1, 0), new Vector3(1, 2, 3), new Vector3(4, 5, 6));
        assertThat(endpoint.model).isEqualTo(OrbitDefinition.Model.CARTESIAN_TWO_BODY_RK4);
        assertThat(endpoint.maxIntegrationStepSec).isEqualTo(1);
        assertThat(endpoint.positionM.getX()).isEqualTo(1);
        assertThat(endpoint.velocityMps.getZ()).isEqualTo(6);
        assertThatThrownBy(() -> new OrbitDefinition.Elements(OrbitDefinition.Model.TWO_BODY,
                7000000, 0.01, 50, 20, 30, 40, OrbitTime.utc(EPOCH), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LEGACY_J2000_SECONDS");
    }

    /** 原适配器的异常不包装，保留错误类型与实例，避免改变既有调用语义。 */
    @Test
    void propagatesOriginalExceptionUnchanged() {
        IllegalStateException original = new IllegalStateException("原传播错误");
        OrbitPropagationTool tool = new OrbitPropagationTool(spaceDefinition(), new OrbitPropagationTool.NativePropagation() {
            public Vector3 positionAt(OrbitTime time) { throw original; }
            public Vector3 velocityAt(OrbitTime time) { throw original; }
        });
        assertThatThrownBy(() -> tool.positionAt(OrbitTime.utc(EPOCH), OrbitDefinition.Frame.TEME)).isSameAs(original);
    }

    private static OrbitDefinition.Elements spaceDefinition() {
        return new OrbitDefinition.Elements(OrbitDefinition.Model.SPACE_KEPLER_J2_RK4,
                7000000, 0.01, 50, 20, 30, 40, OrbitTime.utc(EPOCH), 10);
    }

    private static OrbitPropagationTool.NativePropagation neverCalled() {
        return new OrbitPropagationTool.NativePropagation() {
            public Vector3 positionAt(OrbitTime time) { throw new AssertionError("不应调用位置"); }
            public Vector3 velocityAt(OrbitTime time) { throw new AssertionError("不应调用速度"); }
        };
    }
}
