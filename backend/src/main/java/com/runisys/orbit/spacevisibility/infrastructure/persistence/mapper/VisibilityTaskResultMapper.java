package com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.VisibilityTaskResultEntity;

/**
 * MyBatis-Plus任务记录表访问接口；原Long主键在API读取边界转字符串，继承原CRUD。
 */
public interface VisibilityTaskResultMapper extends BaseMapper<VisibilityTaskResultEntity> {
}
