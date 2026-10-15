package com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ZG_XZ_MANAGE观测卫星实体，目录与原轨道/载荷字段保持数据库映射。
 * 六根数A为米、角元素为度，EPOCH_TIME由应用层解释为UTC；目录字段不代替根数。
 */
@TableName("ZG_XZ_MANAGE")
public class SatelliteEntity {

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
    @TableField("WEIGHT_") private BigDecimal weight;
    @TableField("MODEL_TYPE_") private String modelType;
    @TableField("EPOCH_TIME_") private LocalDateTime epochTime;
    @TableField("A_") private BigDecimal semiMajorAxis;
    @TableField("E_") private BigDecimal eccentricity;
    @TableField("I_") private BigDecimal inclination;
    @TableField("M_") private BigDecimal meanAnomaly;
    @TableField("RAAN_") private BigDecimal raan;
    @TableField("ARG_PER_") private BigDecimal argumentOfPerigee;
    @TableField("PAYLOAD_JSON_") private String payloadJson;
    @TableField("FIRST_ORBIT_") private String firstOrbit;
    @TableField("SECOND_ORBIT_") private String secondOrbit;
    @TableField("CREATED_BY_") private String createdBy;
    @TableField("UPDATE_BY_") private String updateBy;
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
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
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
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getFirstOrbit() { return firstOrbit; }
    public void setFirstOrbit(String firstOrbit) { this.firstOrbit = firstOrbit; }
    public String getSecondOrbit() { return secondOrbit; }
    public void setSecondOrbit(String secondOrbit) { this.secondOrbit = secondOrbit; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
