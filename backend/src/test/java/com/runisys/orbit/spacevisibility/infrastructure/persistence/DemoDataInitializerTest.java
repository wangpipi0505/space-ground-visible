package com.runisys.orbit.spacevisibility.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.application.PayloadConstraintFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 演示初始化回归：验证 H2 JSON 类型、重复启动保护与正式环境隔离。 */
class DemoDataInitializerTest {
    @Test
    void jsonObjectRemainsReadableAndExistingRecordsAreNotOverwritten() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:demo_initializer;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE ZG_STATION (ID_ VARCHAR PRIMARY KEY, NAME_ VARCHAR, LONGITUDE_ DOUBLE, LATITUDE_ DOUBLE, HEIGHT_ DOUBLE, MIN_ELEVATION_ DOUBLE, MAX_ELEVATION_ DOUBLE)");
        jdbc.execute("CREATE TABLE ZG_XZ_MANAGE (ID_ BIGINT PRIMARY KEY,NAME_ VARCHAR,MODEL_TYPE_ VARCHAR,EPOCH_TIME_ TIMESTAMP,A_ DOUBLE,E_ DOUBLE,I_ DOUBLE,M_ DOUBLE,RAAN_ DOUBLE,ARG_PER_ DOUBLE,PAYLOAD_JSON_ JSON)");
        jdbc.execute("CREATE TABLE ZG_XZ_MANAGE_TARGET (ID_ BIGINT PRIMARY KEY,NAME_ VARCHAR,MODEL_TYPE_ VARCHAR,EPOCH_TIME_ TIMESTAMP,A_ DOUBLE,E_ DOUBLE,I_ DOUBLE,M_ DOUBLE,RAAN_ DOUBLE,ARG_PER_ DOUBLE,BELONG_ VARCHAR,WEIGHT_ DOUBLE,LAUNCH_ DATE)");
        DemoDataInitializer initializer = new DemoDataInitializer(jdbc);
        initializer.run(null);
        String json = jdbc.queryForObject("SELECT PAYLOAD_JSON_ FROM ZG_XZ_MANAGE", String.class);
        assertThat(json).startsWith("{");
        PayloadConstraintFactory.PayloadDefinition definition = new PayloadConstraintFactory(new ObjectMapper()).readDefinition(json);
        assertThat(definition.getDistance()).containsExactly(100000, 8000000);
        assertThat(definition.getAngleRate()).isEqualTo(0.5);
        assertThat(definition.getSunAvoidHalfAngle()).isEqualTo(15);
        assertThat(new PayloadConstraintFactory(new ObjectMapper()).parse(json).getMinDetectRangeM()).isEqualTo(100000);
        jdbc.update("UPDATE ZG_XZ_MANAGE SET NAME_ = '用户修改'");
        jdbc.update("UPDATE ZG_STATION SET NAME_ = '用户站点'");
        jdbc.update("UPDATE ZG_XZ_MANAGE_TARGET SET NAME_ = '用户目标'");
        initializer.run(null);
        assertThat(jdbc.queryForObject("SELECT NAME_ FROM ZG_XZ_MANAGE", String.class)).isEqualTo("用户修改");
        assertThat(jdbc.queryForObject("SELECT NAME_ FROM ZG_STATION", String.class)).isEqualTo("用户站点");
        assertThat(jdbc.queryForObject("SELECT NAME_ FROM ZG_XZ_MANAGE_TARGET", String.class)).isEqualTo("用户目标");
    }

    @Test
    void productionContextDoesNotRegisterDemoInitializer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DemoDataInitializer.class);
            context.refresh();
            assertThat(context.getBeansOfType(DemoDataInitializer.class)).isEmpty();
        }
    }
}
