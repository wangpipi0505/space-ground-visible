package com.runisys.orbit.spacevisibility.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus分页拦截配置；根据既有mysql/dm标识选择方言，演示H2使用MySQL兼容模式。
 */
@Configuration
public class MybatisPlusConfiguration {

    /**
     * 当前使用的数据库类型标识（mysql 或 dm），默认 mysql；
     * 集成方切换达梦数据库时将 space.visibility.datasource.db-type 配置为 dm 即可。
     */
    private final String dbType;

    public MybatisPlusConfiguration(@Value("${space.visibility.datasource.db-type:mysql}") String dbType) {
        this.dbType = dbType;
    }

    /**
     * 注册 MyBatis-Plus 分页拦截器，使 Lambda 分页查询由数据库完成计数和切页；
     * 分页方言按配置的数据库类型选择，避免切换达梦时改动代码。
     *
     * @return 已配置分页能力的 MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(resolveDbType()));
        return interceptor;
    }

    /** 将配置的数据库类型标识映射为 MyBatis-Plus 分页方言。 */
    private DbType resolveDbType() {
        if ("mysql".equalsIgnoreCase(dbType)) {
            return DbType.MYSQL;
        }
        if ("dm".equalsIgnoreCase(dbType)) {
            return DbType.DM;
        }
        throw new IllegalStateException("不支持的数据库类型: " + dbType + "（仅支持 mysql 或 dm）");
    }
}
