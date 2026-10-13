package com.runisys.orbit.spacevisibility.domain.downlink;

import com.runisys.orbit.spacevisibility.domain.payload.IlluminationState;

/**
 * 下传链路对我方卫星光照状态的临时要求。
 * `SHADOW` 明确映射为地球本影 UMBRA，而非把仍存在太阳直射的半影一并纳入，
 * 以保持与 WinTool 阴影区输出及保护窗口边界一致。
 */
public enum IlluminationRequirement {
    ANY {
        @Override
        public boolean matches(IlluminationState state) {
            return true;
        }
    },
    SUNLIT {
        @Override
        public boolean matches(IlluminationState state) {
            return state == IlluminationState.SUNLIT;
        }
    },
    SHADOW {
        @Override
        public boolean matches(IlluminationState state) {
            // Word 文档中的 WinTool“阴影区”输出以地球本影为边界；半影仍有太阳直射，
            // 不可与本影混为同一可下传状态，否则会把前保护起点提前数分钟。
            return state == IlluminationState.UMBRA;
        }
    };

    /** 判断一个精确光照状态是否满足当前下传约束。 */
    public abstract boolean matches(IlluminationState state);

    /** 将可选接口值规范化为枚举；空值表示不限，非法值返回可展示的参数错误。 */
    public static IlluminationRequirement fromApiValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return ANY;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("卫星光照约束仅支持 ANY、SUNLIT 或 SHADOW");
        }
    }
}
