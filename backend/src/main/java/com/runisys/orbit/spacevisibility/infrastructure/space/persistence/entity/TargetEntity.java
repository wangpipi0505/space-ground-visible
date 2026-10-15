package com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ZG_XZ_MANAGE_TARGET目标目录及原轨道实体；筛选和根数/两行数解释由应用服务负责。
 * 原字段名称/类型及Jackson标注保持；不假定任意导入目录数据的未知物理单位。
 */
@TableName("ZG_XZ_MANAGE_TARGET")
public class TargetEntity {

    @TableId(value = "ID_", type = IdType.AUTO)
    private Long id;
    @TableField("TYPE_") private String type;
    @TableField("LIBRARY_MODEL_ID_") private Long libraryModelId;
    @TableField("NAME_") private String name;
    @TableField("SCID_") private Long scid;
    @TableField("OBJECT_ID_") private String objectId;
    @TableField("OBJECT_NAME_") private String objectName;
    @TableField("NORAD_CAT_ID_") private Long noradCatId;
    @TableField("COUNTRY_") private String country;
    @TableField("PERIOD_") private BigDecimal period;
    @TableField("INCLINATION_") private BigDecimal catalogInclination;
    @TableField("APOGEE_") private BigDecimal apogee;
    @TableField("PERIGEE_") private BigDecimal perigee;
    @TableField("RCS_SIZE_") private String rcsSize;
    @TableField("RCSVALUE_") private BigDecimal rcsValue;
    @TableField("LAUNCH_") private LocalDate launchDate;
    @TableField("COMMENT_") private String catalogComment;
    @TableField("MODEL_TYPE_") private String modelType;
    @TableField("EPOCH_TIME_") private LocalDateTime epochTime;
    @TableField("A_") private BigDecimal semiMajorAxis;
    @TableField("E_") private BigDecimal eccentricity;
    @TableField("I_") private BigDecimal inclination;
    @TableField("M_") private BigDecimal meanAnomaly;
    @TableField("RAAN_") private BigDecimal raan;
    @TableField("ARG_PER_") private BigDecimal argumentOfPerigee;
    @TableField("FIRST_ORBIT_") private String firstOrbit;
    @TableField("SECOND_ORBIT_") private String secondOrbit;
    @TableField("BELONG_") private String belong;
    @TableField("WEIGHT_") private BigDecimal weight;
    @TableField("CREATE_BY_") private String createBy;
    @TableField("UPDATE_BY_") private String updateBy;
    @TableField("CREATE_ORG_ID_") private String createOrgId;
    @TableField("CREATE_TIME_") private LocalDateTime createTime;
    @TableField("UPDATE_TIME_") private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getLibraryModelId() { return libraryModelId; }
    public void setLibraryModelId(Long libraryModelId) { this.libraryModelId = libraryModelId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getScid() { return scid; }
    public void setScid(Long scid) { this.scid = scid; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getObjectName() { return objectName; }
    public void setObjectName(String objectName) { this.objectName = objectName; }
    public Long getNoradCatId() { return noradCatId; }
    public void setNoradCatId(Long noradCatId) { this.noradCatId = noradCatId; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public BigDecimal getPeriod() { return period; }
    public void setPeriod(BigDecimal period) { this.period = period; }
    public BigDecimal getCatalogInclination() { return catalogInclination; }
    public void setCatalogInclination(BigDecimal catalogInclination) { this.catalogInclination = catalogInclination; }
    public BigDecimal getApogee() { return apogee; }
    public void setApogee(BigDecimal apogee) { this.apogee = apogee; }
    public BigDecimal getPerigee() { return perigee; }
    public void setPerigee(BigDecimal perigee) { this.perigee = perigee; }
    public String getRcsSize() { return rcsSize; }
    public void setRcsSize(String rcsSize) { this.rcsSize = rcsSize; }
    public BigDecimal getRcsValue() { return rcsValue; }
    public void setRcsValue(BigDecimal rcsValue) { this.rcsValue = rcsValue; }
    public LocalDate getLaunchDate() { return launchDate; }
    public void setLaunchDate(LocalDate launchDate) { this.launchDate = launchDate; }
    public String getCatalogComment() { return catalogComment; }
    public void setCatalogComment(String catalogComment) { this.catalogComment = catalogComment; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public LocalDateTime getEpochTime() { return epochTime; }
    public void setEpochTime(LocalDateTime epochTime) { this.epochTime = epochTime; }
    public BigDecimal getSemiMajorAxis() { return semiMajorAxis; }
    public void setSemiMajorAxis(BigDecimal semiMajorAxis) { this.semiMajorAxis = semiMajorAxis; }
    public BigDecimal getEccentricity() { return eccentricity; }
    public void setEccentricity(BigDecimal eccentricity) { this.eccentricity = eccentricity; }
    public BigDecimal getInclination() { return inclination; }
    public void setInclination(BigDecimal inclination) { this.inclination = inclination; }
    public BigDecimal getMeanAnomaly() { return meanAnomaly; }
    public void setMeanAnomaly(BigDecimal meanAnomaly) { this.meanAnomaly = meanAnomaly; }
    public BigDecimal getRaan() { return raan; }
    public void setRaan(BigDecimal raan) { this.raan = raan; }
    public BigDecimal getArgumentOfPerigee() { return argumentOfPerigee; }
    public void setArgumentOfPerigee(BigDecimal argumentOfPerigee) { this.argumentOfPerigee = argumentOfPerigee; }
    public String getFirstOrbit() { return firstOrbit; }
    public void setFirstOrbit(String firstOrbit) { this.firstOrbit = firstOrbit; }
    public String getSecondOrbit() { return secondOrbit; }
    public void setSecondOrbit(String secondOrbit) { this.secondOrbit = secondOrbit; }
    public String getBelong() { return belong; }
    public void setBelong(String belong) { this.belong = belong; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public String getCreateOrgId() { return createOrgId; }
    public void setCreateOrgId(String createOrgId) { this.createOrgId = createOrgId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
