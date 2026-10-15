package com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ZG_STATION站点实体；经纬度/仰角为度，高度为米，ID为字符串。
 * 一次任务临时站点约束独立于这里的持久化基础参数。
 */
@TableName("ZG_STATION")
public class StationEntity {

    @TableId(value = "ID_", type = IdType.INPUT)
    private String id;
    @TableField("NAME_")
    private String name;
    @TableField("LONGITUDE_")
    private BigDecimal longitude;
    @TableField("LATITUDE_")
    private BigDecimal latitude;
    @TableField("HEIGHT_")
    private BigDecimal height;
    @TableField("MIN_ELEVATION_")
    private BigDecimal minElevation;
    @TableField("MAX_ELEVATION_")
    private BigDecimal maxElevation;
    @TableField("CREATE_BY_")
    private String createBy;
    @TableField("CREATE_ORG_ID_")
    private String createOrgId;
    @TableField("CREATE_TIME_")
    private LocalDateTime createTime;
    @TableField("UPDATE_BY_")
    private String updateBy;
    @TableField("UPDATE_TIME_")
    private LocalDateTime updateTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getHeight() { return height; }
    public void setHeight(BigDecimal height) { this.height = height; }
    public BigDecimal getMinElevation() { return minElevation; }
    public void setMinElevation(BigDecimal minElevation) { this.minElevation = minElevation; }
    public BigDecimal getMaxElevation() { return maxElevation; }
    public void setMaxElevation(BigDecimal maxElevation) { this.maxElevation = maxElevation; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getCreateOrgId() { return createOrgId; }
    public void setCreateOrgId(String createOrgId) { this.createOrgId = createOrgId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
