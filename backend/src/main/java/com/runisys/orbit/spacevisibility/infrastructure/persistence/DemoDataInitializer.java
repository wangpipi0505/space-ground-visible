package com.runisys.orbit.spacevisibility.infrastructure.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 仅 demo 环境使用的可复现目录数据。轨道采用 T04 合成回归样例的圆轨道参数，
 * 不代表真实卫星、站点或生产资料；固定 UTC 历元为 2026-07-01 10:00:00。
 * 初始化只补充固定 ID 的缺失记录，重启不覆盖浏览器修改，生产配置不注册此组件。
 */
@Component
@Profile("demo")
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    /** 依赖当前 demo 数据源；Flyway 在运行器执行前完成原版表结构迁移。 */
    public DemoDataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 补充一站、一观测星和一目标；半长轴单位米，角度单位度，时间使用 UTC。 */
    @Override
    public void run(ApplicationArguments args) {
        if (missing("ZG_STATION", "DEMO-STATION")) {
            jdbc.update("INSERT INTO ZG_STATION (ID_,NAME_,LONGITUDE_,LATITUDE_,HEIGHT_,MIN_ELEVATION_,MAX_ELEVATION_) VALUES (?,?,?,?,?,?,?)",
                    "DEMO-STATION", "演示站（非生产资料）", 100, 30, 0, 0, 90);
        }
        // H2 1.4.200 的 JSON 列会把 JDBC 字符串当作 JSON 字符串值；FORMAT JSON 明确按对象解析，
        // 避免目录返回双重转义而触发原解析器的历史兼容回退。此语法只在 demo 初始化器使用。
        if (missing("ZG_XZ_MANAGE", 1L)) {
            jdbc.update("INSERT INTO ZG_XZ_MANAGE (ID_,NAME_,MODEL_TYPE_,EPOCH_TIME_,A_,E_,I_,M_,RAAN_,ARG_PER_,PAYLOAD_JSON_) VALUES (?,?,?,?,?,?,?,?,?,?,? FORMAT JSON)",
                    1L, "演示观测星（非生产资料）", "KEPLER_6", "2026-07-01 10:00:00", 7078137, 0, 50, 0, 30, 0,
                    "{\"payloadType\":\"SPACE\",\"distance\":[100000,8000000],\"distanceUnit\":\"M\",\"azimuth\":[10,350],\"elevation\":[-80,80],\"angleRate\":0.5,\"sunAvoidHalfAngle\":15}");
        }
        if (missing("ZG_XZ_MANAGE_TARGET", 2L)) {
            jdbc.update("INSERT INTO ZG_XZ_MANAGE_TARGET (ID_,NAME_,MODEL_TYPE_,EPOCH_TIME_,A_,E_,I_,M_,RAAN_,ARG_PER_,BELONG_,WEIGHT_,LAUNCH_) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    2L, "演示目标星（非生产资料）", "KEPLER_6", "2026-07-01 10:00:00", 7278137, 0, 50, 30, 30, 0, "US", 120, "2020-01-01");
        }
    }

    /** 表名仅来自本类固定常量；参数化 ID 防止把目录输入拼入查询。 */
    private boolean missing(String table, Object id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE ID_ = ?", Integer.class, id) == 0;
    }
}
