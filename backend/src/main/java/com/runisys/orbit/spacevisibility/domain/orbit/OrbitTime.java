package com.runisys.orbit.spacevisibility.domain.orbit;

import java.time.Instant;
import java.util.Objects;

/**
 * 不可变的原生查询时刻或历元；只保存输入，不进行时标转换。
 * 旧弹道秒以 UTC 日历 2000-01-01 12:00:00 为起点，沿用原 utcOffsetSec 约定，
 * 不是 Orekit 的 J2000 TT。旧 double 必须原值传递，不能经 Instant 纳秒量化后再转回。
 */
public final class OrbitTime {
    /** 区分空间 Instant 与旧弹道日历秒；坐标标签中的 J2000 不决定时间口径。 */
    public enum Basis { UTC_INSTANT, LEGACY_J2000_SECONDS }

    public final Basis basis;
    private final Instant instant;
    private final double legacySeconds;
    private final double utcOffsetSec;

    private OrbitTime(Basis basis, Instant instant, double legacySeconds, double utcOffsetSec) {
        this.basis = basis;
        this.instant = instant;
        this.legacySeconds = legacySeconds;
        this.utcOffsetSec = utcOffsetSec;
    }

    /** 保存必填 UTC 时刻，包含全部纳秒精度。 */
    public static OrbitTime utc(Instant instant) {
        return new OrbitTime(Basis.UTC_INSTANT, Objects.requireNonNull(instant, "UTC时刻不能为空"), 0, 0);
    }

    /**
     * 保存原弹道秒和原 utcOffsetSec；两个值必填且有限，允许负秒及负零。
     * 偏移仅作口径标识，不在此方法加减；具体含义和运算仍由原弹道适配器负责。
     */
    public static OrbitTime legacyJ2000(double seconds, double utcOffsetSec) {
        if (!Double.isFinite(seconds) || !Double.isFinite(utcOffsetSec)) {
            throw new IllegalArgumentException("旧J2000秒及utcOffsetSec必须为有限数");
        }
        return new OrbitTime(Basis.LEGACY_J2000_SECONDS, null, seconds, utcOffsetSec);
    }

    /** 只允许读取原本以 UTC_INSTANT 构建的时刻，不提供隐式转换。 */
    public Instant instant() {
        requireBasis(Basis.UTC_INSTANT);
        return instant;
    }

    /** 只允许读取旧弹道秒；返回值的浮点 bits 与输入完全相同。 */
    public double legacySeconds() {
        requireBasis(Basis.LEGACY_J2000_SECONDS);
        return legacySeconds;
    }

    /** 返回旧弹道的原 utcOffsetSec（秒）；UTC Instant 不携带此约定。 */
    public double utcOffsetSec() {
        requireBasis(Basis.LEGACY_J2000_SECONDS);
        return utcOffsetSec;
    }

    /** 检查原生时间口径，避免将名称相近的 J2000 坐标、TT 历元和旧秒混用。 */
    void requireBasis(Basis expected) {
        if (basis != expected) {
            throw new IllegalArgumentException("时间口径不匹配，需要" + expected + "，实际" + basis);
        }
    }
}
