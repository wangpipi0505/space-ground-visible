package com.runisys.orbit.spacevisibility.domain.payload;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrekitDataInitializer;
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.JPLEphemeridesLoader;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WinTool 共用光照几何。
 *
 * <p>口径已由用户确认并固定：太阳方向来自 JPL DE405，转换至与 SGP4 状态相同的 TEME；
 * 地球为 WGS84 旋转椭球；太阳按无限远平行光处理，故地影为圆柱影；不计大气折射、
 * 不计算半影。下传和空间目标观测都只能经本类得到光照状态，避免两条路径再次出现不同的
 * 星历、地球半径或阴影边界。</p>
 */
public final class SolarGeometry {

    /**
     * 历史重载的兼容常量。圆柱影不使用太阳视半径，故固定为 0；保留名称仅避免旧的工具构造器
     * 在本轮口径切换中失去二进制/源码兼容，生产逻辑不读取其值。
     */
    public static final double DEFAULT_SUN_ANGULAR_RADIUS_DEG = 0.0;

    /** WGS84 长半轴，单位米。 */
    private static final double WGS84_EQUATORIAL_RADIUS_M = 6_378_137.0;
    /** WGS84 扁率。 */
    private static final double WGS84_FLATTENING = 1.0 / 298.257223563;
    /** WGS84 短半轴，单位米。 */
    private static final double WGS84_POLAR_RADIUS_M = WGS84_EQUATORIAL_RADIUS_M * (1.0 - WGS84_FLATTENING);
    private static final double WGS84_EQUATORIAL_RADIUS_SQUARED = WGS84_EQUATORIAL_RADIUS_M * WGS84_EQUATORIAL_RADIUS_M;
    private static final double WGS84_POLAR_RADIUS_SQUARED = WGS84_POLAR_RADIUS_M * WGS84_POLAR_RADIUS_M;

    /** 仅保留一个生产太阳源，避免 DE440、解析式或运行时配置回退被误认为是 WinTool 口径。 */
    public enum SunSource {
        JPL_DE405_TEME
    }

    /** 按 UTC 整秒缓存太阳方向；扫描细化到秒以下时仍复用同一秒的 DE405/TEME 方向。 */
    private static final Map<Long, Vector3> TEME_SUN_BY_UTC_SECOND = new ConcurrentHashMap<Long, Vector3>();
    /** 达到上限后整体清空，限制长时间服务的缓存占用。 */
    private static final int TEME_SUN_CACHE_MAX_ENTRIES = 1 << 20;

    private static volatile CelestialBody de405Sun;
    private static volatile Frame temeFrame;

    private SolarGeometry() {
    }

    /** @return 唯一允许的生产太阳方向来源。 */
    public static SunSource currentSunSource() {
        return SunSource.JPL_DE405_TEME;
    }

    /**
     * 返回由 DE405 计算并转换至 TEME 的太阳单位方向。
     *
     * <p>方法名为兼容既有调用保留，但返回向量实际位于 TEME，正好与 TLE/SGP4 的卫星位置
     * 同参考系；调用方不能将其与 J2000/GCRF 位置混用。</p>
     */
    public static Vector3 sunDirectionEci(Instant instant) {
        return sunDirectionTeme(instant);
    }

    /**
     * 返回 DE405 在 TEME 中的地心指向太阳的单位方向。
     *
     * <p>缓存键取 UTC 整秒；本影边界的精选精度为秒级，秒内太阳视方向变化远小于 3 秒
     * 窗口验收阈值。卫星位置仍始终按真实采样时刻计算，因此缓存不改变轨道传播精度。</p>
     */
    public static Vector3 sunDirectionTeme(Instant instant) {
        long epochSecond = instant.getEpochSecond();
        Vector3 cached = TEME_SUN_BY_UTC_SECOND.get(epochSecond);
        if (cached != null) {
            return cached;
        }
        ensureDe405Handles();
        AbsoluteDate date = new AbsoluteDate(instant, TimeScalesFactory.getUTC());
        PVCoordinates sunPv = de405Sun.getPVCoordinates(date, temeFrame);
        Vector3 direction = new Vector3(sunPv.getPosition().getX(), sunPv.getPosition().getY(),
                sunPv.getPosition().getZ()).normalize();
        if (TEME_SUN_BY_UTC_SECOND.size() >= TEME_SUN_CACHE_MAX_ENTRIES) {
            TEME_SUN_BY_UTC_SECOND.clear();
        }
        TEME_SUN_BY_UTC_SECOND.put(epochSecond, direction);
        return direction;
    }

    /** 使用当前时刻的 DE405/TEME 太阳方向判定光照。 */
    public static IlluminationState illumination(Vector3 objectPositionTeme, Instant instant) {
        return illumination(objectPositionTeme, sunDirectionTeme(instant));
    }

    /**
     * 判定 WGS84 椭球圆柱影。
     *
     * <p>从卫星位置沿“指向太阳”的正向射线求与 WGS84 椭球的交点：存在非负实根即表示
     * 地球挡住平行太阳光，状态为 {@link IlluminationState#UMBRA}；否则为
     * {@link IlluminationState#SUNLIT}。模型没有有限太阳盘，故绝不会产生半影。</p>
     */
    public static IlluminationState illumination(Vector3 objectPositionTeme, Vector3 sunDirectionTeme) {
        if (objectPositionTeme == null || sunDirectionTeme == null || sunDirectionTeme.norm() == 0.0) {
            throw new IllegalArgumentException("卫星位置和太阳方向必须有效");
        }
        return rayIntersectsWgs84Ellipsoid(objectPositionTeme, sunDirectionTeme.normalize())
                ? IlluminationState.UMBRA : IlluminationState.SUNLIT;
    }

    /**
     * 兼容历史工具方法签名。太阳视半径在已确认的圆柱影口径中不参与计算，任何传入值都不会
     * 改变结果；生产调用将在后续清理中逐步迁移至无该参数的重载，避免旧调用静默回到圆锥影。
     */
    public static IlluminationState illumination(Vector3 objectPositionTeme,
                                                  Instant instant,
                                                  double ignoredSunAngularRadiusDeg) {
        return illumination(objectPositionTeme, instant);
    }

    /** 同上，兼容直接传入太阳方向的历史工具调用。 */
    public static IlluminationState illumination(Vector3 objectPositionTeme,
                                                  Vector3 sunDirectionTeme,
                                                  double ignoredSunAngularRadiusDeg) {
        return illumination(objectPositionTeme, sunDirectionTeme);
    }

    /**
     * 求射线 {@code p + λs, λ>=0} 与 WGS84 椭球的交点。
     *
     * <p>椭球在 TEME 中绕地轴旋转对称，因此与 ECEF 仅差绕 Z 轴的地球自转时，二次型不变；
     * 不需要将每个采样点额外转换到 ECEF。忽略极移和折射正是用户确认的计算口径。</p>
     */
    private static boolean rayIntersectsWgs84Ellipsoid(Vector3 position, Vector3 direction) {
        double quadratic = (direction.getX() * direction.getX() + direction.getY() * direction.getY())
                / WGS84_EQUATORIAL_RADIUS_SQUARED
                + direction.getZ() * direction.getZ() / WGS84_POLAR_RADIUS_SQUARED;
        double linear = 2.0 * ((position.getX() * direction.getX() + position.getY() * direction.getY())
                / WGS84_EQUATORIAL_RADIUS_SQUARED
                + position.getZ() * direction.getZ() / WGS84_POLAR_RADIUS_SQUARED);
        double constant = (position.getX() * position.getX() + position.getY() * position.getY())
                / WGS84_EQUATORIAL_RADIUS_SQUARED
                + position.getZ() * position.getZ() / WGS84_POLAR_RADIUS_SQUARED - 1.0;
        double discriminant = linear * linear - 4.0 * quadratic * constant;
        if (discriminant < 0.0) {
            return false;
        }
        double root = Math.sqrt(Math.max(0.0, discriminant));
        double firstIntersection = (-linear - root) / (2.0 * quadratic);
        double secondIntersection = (-linear + root) / (2.0 * quadratic);
        return firstIntersection >= 0.0 || secondIntersection >= 0.0;
    }

    /**
     * 安装只匹配已校验 DE405 文件的 JPL 加载器，并显式清理 Orekit 默认加载器/缓存。
     *
     * <p>默认加载器会从数据目录自动挑选 DE440，本方法通过精确文件名注册太阳、太阳系质心和
     * 地月质心三类加载器，保证 Sun 的参考系链也完全来自同一份 DE405 文件。</p>
     */
    private static synchronized void ensureDe405Handles() {
        if (de405Sun != null && temeFrame != null) {
            return;
        }
        OrekitDataInitializer.initialize();
        String de405FileName = OrekitDataInitializer.requireDe405FileName();
        CelestialBodyFactory.clearCelestialBodyCache();
        CelestialBodyFactory.clearCelestialBodyLoaders();
        registerDe405Loader(CelestialBodyFactory.SOLAR_SYSTEM_BARYCENTER,
                JPLEphemeridesLoader.EphemerisType.SOLAR_SYSTEM_BARYCENTER, de405FileName);
        registerDe405Loader(CelestialBodyFactory.EARTH_MOON,
                JPLEphemeridesLoader.EphemerisType.EARTH_MOON, de405FileName);
        registerDe405Loader(CelestialBodyFactory.SUN, JPLEphemeridesLoader.EphemerisType.SUN, de405FileName);
        de405Sun = CelestialBodyFactory.getSun();
        temeFrame = FramesFactory.getTEME();
    }

    private static void registerDe405Loader(String bodyName,
                                            JPLEphemeridesLoader.EphemerisType ephemerisType,
                                            String de405FileName) {
        CelestialBodyFactory.addCelestialBodyLoader(bodyName,
                new JPLEphemeridesLoader("^" + java.util.regex.Pattern.quote(de405FileName) + "$", ephemerisType));
    }
}
