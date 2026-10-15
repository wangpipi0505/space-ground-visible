package com.runisys.orbit.spacevisibility.infrastructure.space.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity.StationEntity;

/**
 * MyBatis-Plus站点表访问接口；字符串主键与StationEntity映射保持，继承原CRUD。
 */
public interface StationMapper extends BaseMapper<StationEntity> {
}
