package com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;

/**
 * MyBatis-Plus目标表访问接口，筛选条件由ReferenceDataService组织，继承原CRUD。
 */
public interface TargetMapper extends BaseMapper<TargetEntity> {
}
