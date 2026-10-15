package com.runisys.orbit.spacevisibility.domain.space.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import java.time.Instant;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;

/**
 * 基于 Orekit SGP4/SDP4 传播器的 TLE 轨道状态提供器。
 *
 * <p>传播器通过 {@link ThreadLocal} 为每个计算线程独立创建，避免下传与目标观测批量并行时
 * 共享可变传播状态；对外只输出 TEME 位置，速度由上层使用相邻位置差分估计，保证两类轨道模型口径一致。</p>
 */
public final class TleOrbitStateProvider implements OrbitStateProvider {

    private final TLE tle;
    private final TimeScale utc;
    private final ThreadLocal<TLEPropagator> propagator;
    private final String firstOrbit;
    private final String secondOrbit;

    /** 仅当目录编号段（下标 2~6）含字母（alpha-5/十六进制扩展编号）时遮蔽为 00000；纯数字原样返回。 */
    static String maskCatalog(String line) {
        if (line == null || line.length() < 7) {
            return line;
        }
        String catalog = line.substring(2, 7);
        boolean numeric = catalog.chars().allMatch(Character::isDigit);
        return numeric ? line : line.substring(0, 2) + "00000" + line.substring(7);
    }

    /** 校验两行根数并准备线程隔离的 Orekit 传播器。 */
    public TleOrbitStateProvider(String firstOrbit, String secondOrbit) {
        // 目录编号扩展表示兼容（Space-Track/CelesTrak 扩展）：编号 ≥100000 时第 3~7 列为
        // alpha-5（A0000=100000…）或十六进制表示，含字母。Orekit 按整数解析该列会失败，
        // 因此校验与建 TLE 前仅把编号段遮蔽为 00000——SGP4 轨道元素与编号无关，传播结果不变。
        this.firstOrbit = maskCatalog(firstOrbit);
        this.secondOrbit = maskCatalog(secondOrbit);
        if (!TLE.isFormatOK(this.firstOrbit, this.secondOrbit)) {
            throw new IllegalArgumentException("TLE 两行根数格式无效");
        }
        OrekitDataInitializer.initialize();
        this.utc = TimeScalesFactory.getUTC();
        this.tle = new TLE(this.firstOrbit, this.secondOrbit, utc);
        this.propagator = new ThreadLocal<TLEPropagator>() {
            @Override
            protected TLEPropagator initialValue() {
                return TLEPropagator.selectExtrapolator(tle);
            }
        };
    }

    @Override
    /** 将 UTC 时刻传播到 TEME 坐标位置（米）。 */
    public Vector3 positionAt(Instant instant) {
        AbsoluteDate date = SpaceTimeFrames.utcToAbsoluteDate(instant, utc);
        PVCoordinates coordinates = propagator.get().getPVCoordinates(date);
        return new Vector3(
                coordinates.getPosition().getX(),
                coordinates.getPosition().getY(),
                coordinates.getPosition().getZ());
    }
}
