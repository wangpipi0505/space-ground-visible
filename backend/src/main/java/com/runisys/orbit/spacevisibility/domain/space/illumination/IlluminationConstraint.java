package com.runisys.orbit.spacevisibility.domain.space.illumination;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 表示一次目标观测规划临时使用的“观测星—目标”光照组合约束。
 * WinTool 已确认圆柱影且不考虑半影，因此预置阴影条件只包含本影；自定义模式保留历史
 * PENUMBRA 枚举的反序列化兼容性，但圆柱影计算不会产生该状态。
 */
public final class IlluminationConstraint {

    private final Mode mode;
    private final Set<IlluminationState> observerStates;
    private final Set<IlluminationState> targetStates;

    private IlluminationConstraint(Mode mode,
                                   Set<IlluminationState> observerStates,
                                   Set<IlluminationState> targetStates) {
        this.mode = mode;
        this.observerStates = Collections.unmodifiableSet(EnumSet.copyOf(observerStates));
        this.targetStates = Collections.unmodifiableSet(EnumSet.copyOf(targetStates));
    }

    /**
     * 将接口中的预置模式或自定义状态值转换为不可变领域约束。
     *
     * @param modeValue 预置模式编码；空值按不限处理
     * @param observerValues 自定义模式下观测星允许的精确光照状态
     * @param targetValues 自定义模式下目标允许的精确光照状态
     * @return 可直接参与逐时刻判定的光照组合约束
     */
    public static IlluminationConstraint from(String modeValue,
                                              Collection<String> observerValues,
                                              Collection<String> targetValues) {
        Mode mode = Mode.fromApiValue(modeValue);
        if (mode == Mode.CUSTOM) {
            return new IlluminationConstraint(mode,
                    parseStates(observerValues, "观测星"), parseStates(targetValues, "目标"));
        }
        return new IlluminationConstraint(mode, mode.observerStates(), mode.targetStates());
    }

    /**
     * 判断当前观测星和目标的精确光照状态是否满足本次任务的组合约束。
     *
     * @param observerState 观测星精确光照状态
     * @param targetState 目标精确光照状态
     * @return 两端状态均被允许时返回 true
     */
    public boolean allows(IlluminationState observerState, IlluminationState targetState) {
        return observerState != null && targetState != null
                && observerStates.contains(observerState) && targetStates.contains(targetState);
    }

    /** @return 当前约束使用的模式编码。 */
    public String getMode() {
        return mode.name();
    }

    private static Set<IlluminationState> parseStates(Collection<String> values, String endpointName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("自定义光照约束必须至少选择一个" + endpointName + "状态");
        }
        Set<IlluminationState> states = EnumSet.noneOf(IlluminationState.class);
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(endpointName + "光照状态无效");
            }
            try {
                states.add(IlluminationState.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(endpointName + "光照状态无效: " + value, exception);
            }
        }
        return states;
    }

    /** 定义接口允许传入的预置光照组合及其展开后的状态集合。 */
    private enum Mode {
        ANY(allStates(), allStates()),
        TARGET_SUNLIT(allStates(), states(IlluminationState.SUNLIT)),
        OBSERVER_SHADOW_TARGET_SUNLIT(shadowStates(), states(IlluminationState.SUNLIT)),
        BOTH_SHADOW(shadowStates(), shadowStates()),
        CUSTOM(Collections.<IlluminationState>emptySet(), Collections.<IlluminationState>emptySet());

        private final Set<IlluminationState> observerStates;
        private final Set<IlluminationState> targetStates;

        Mode(Set<IlluminationState> observerStates, Set<IlluminationState> targetStates) {
            this.observerStates = observerStates;
            this.targetStates = targetStates;
        }

        private static Mode fromApiValue(String value) {
            if (value == null || value.trim().isEmpty()) {
                return ANY;
            }
            try {
                return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("光照组合模式无效: " + value, exception);
            }
        }

        private Set<IlluminationState> observerStates() {
            return observerStates;
        }

        private Set<IlluminationState> targetStates() {
            return targetStates;
        }

        private static Set<IlluminationState> allStates() {
            return EnumSet.allOf(IlluminationState.class);
        }

        private static Set<IlluminationState> shadowStates() {
            return states(IlluminationState.UMBRA);
        }

        private static Set<IlluminationState> states(IlluminationState... values) {
            return EnumSet.of(values[0], values);
        }
    }
}
