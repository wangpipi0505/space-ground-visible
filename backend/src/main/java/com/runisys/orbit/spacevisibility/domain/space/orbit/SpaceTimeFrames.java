package com.runisys.orbit.spacevisibility.domain.space.orbit;

import java.time.Instant;
import org.orekit.frames.Frame;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.utils.PVCoordinates;

/**
 * 空间原Orekit时间和EME2000→TEME适配；保留调用方已经选择的UTC时标与帧实例。
 * 本类不初始化或重置全局数据上下文，不选择新的闰秒/EOP来源，不使用弹道日历秒。
 * provider持有的UTC对象在每次查询中原样传入，太阳调用仍使用其原来的当前UTC来源。
 */
public final class SpaceTimeFrames {
    private SpaceTimeFrames() {
    }

    /** 用原UTC TimeScale把含纳秒的Instant构造成AbsoluteDate，不经double秒往返量化。 */
    public static AbsoluteDate utcToAbsoluteDate(Instant instant, TimeScale utc) {
        return new AbsoluteDate(instant, utc);
    }

    /**
     * 把原EME2000积分位置/速度变换到TEME，位置单位米、速度单位米/秒。
     * 两个帧参数是原provider持有的EME2000与TEME实例，保留其数据上下文和实例生命周期；
     * 沿用原getTransformTo/transformPVCoordinates链，不换成地面GMST或弹道GAST。
     */
    public static PVCoordinates eme2000ToTeme(PVCoordinates coordinates, AbsoluteDate date,
                                             Frame eme2000, Frame teme) {
        return eme2000.getTransformTo(teme, date).transformPVCoordinates(coordinates);
    }
}
