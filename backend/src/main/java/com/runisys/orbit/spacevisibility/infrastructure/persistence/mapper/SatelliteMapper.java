package com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;

/**
 * MyBatis-Plus观测卫星表访问接口，继承BaseMapper既有CRUD，不添加SQL或业务计算。
 */
public interface SatelliteMapper extends BaseMapper<SatelliteEntity> {
}
