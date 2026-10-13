package com.runisys.orbit.spacevisibility.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.SatelliteMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.TargetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SatelliteMapperIntegrationTest {

    @Autowired
    private SatelliteMapper satelliteMapper;

    @Autowired
    private TargetMapper targetMapper;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearSatellites() {
        satelliteMapper.delete(null);
        targetMapper.delete(null);
    }

    @Test
    void persistsSameNameObjectsWithDifferentNoradCatalogNumbers() {
        satelliteMapper.insert(createSatellite("OBJECT A", 100237L));
        satelliteMapper.insert(createSatellite("OBJECT A", 100238L));

        List<SatelliteEntity> satellites = satelliteMapper.selectList(
                new LambdaQueryWrapper<SatelliteEntity>()
                        .eq(SatelliteEntity::getName, "OBJECT A"));

        assertThat(satellites).extracting(SatelliteEntity::getScid)
                .containsExactlyInAnyOrder(100237L, 100238L);
    }

    @Test
    void persistsSameNameTargetsWithDifferentNoradCatalogNumbers() {
        targetMapper.insert(createTarget("OBJECT A", 100237L));
        targetMapper.insert(createTarget("OBJECT A", 100238L));

        List<TargetEntity> targets = targetMapper.selectList(
                new LambdaQueryWrapper<TargetEntity>()
                        .eq(TargetEntity::getName, "OBJECT A"));

        assertThat(targets).extracting(TargetEntity::getScid)
                .containsExactlyInAnyOrder(100237L, 100238L);
    }

    @Test
    void mapsAllSpaceTrackSourceFieldsForBothSatelliteTypes() {
        assertSpaceTrackFieldMappings(SatelliteEntity.class);
        assertSpaceTrackFieldMappings(TargetEntity.class);
    }

    @Test
    void createsAllSpaceTrackSourceColumnsForBothSatelliteTables() throws Exception {
        assertSpaceTrackColumns("ZG_XZ_MANAGE");
        assertSpaceTrackColumns("ZG_XZ_MANAGE_TARGET");
    }

    @Test
    void mapsWeightForBothSatelliteTypes() {
        assertThat(tableFieldColumn(SatelliteEntity.class, "weight")).isEqualTo("WEIGHT_");
        assertThat(tableFieldColumn(TargetEntity.class, "weight")).isEqualTo("WEIGHT_");
    }

    @Test
    void persistsAllSpaceTrackSourceValuesForBothSatelliteTypes() {
        SatelliteEntity satellite = createSatellite("SATELLITE", 100245L);
        setSpaceTrackSourceValues(satellite);
        satelliteMapper.insert(satellite);

        TargetEntity target = createTarget("TARGET", 100246L);
        setSpaceTrackSourceValues(target);
        targetMapper.insert(target);

        assertSpaceTrackSourceValues(satelliteMapper.selectById(satellite.getId()));
        assertSpaceTrackSourceValues(targetMapper.selectById(target.getId()));
    }

    private void setSpaceTrackSourceValues(SatelliteEntity entity) {
        entity.setObjectId("2026-180C");
        entity.setObjectName("ELECTRON R/B");
        entity.setNoradCatId(100245L);
        entity.setCountry("US");
        entity.setPeriod(new BigDecimal("92.58"));
        entity.setCatalogInclination(new BigDecimal("42.01"));
        entity.setApogee(new BigDecimal("567"));
        entity.setPerigee(new BigDecimal("235"));
        entity.setRcsSize("LARGE");
        entity.setRcsValue(new BigDecimal("0.12"));
        entity.setLaunchDate(LocalDate.of(2026, 8, 6));
        entity.setCatalogComment("Space-Track source value");
        entity.setWeight(new BigDecimal("12.34"));
    }

    private void setSpaceTrackSourceValues(TargetEntity entity) {
        entity.setObjectId("2026-180C");
        entity.setObjectName("ELECTRON R/B");
        entity.setNoradCatId(100245L);
        entity.setCountry("US");
        entity.setPeriod(new BigDecimal("92.58"));
        entity.setCatalogInclination(new BigDecimal("42.01"));
        entity.setApogee(new BigDecimal("567"));
        entity.setPerigee(new BigDecimal("235"));
        entity.setRcsSize("LARGE");
        entity.setRcsValue(new BigDecimal("0.12"));
        entity.setLaunchDate(LocalDate.of(2026, 8, 6));
        entity.setCatalogComment("Space-Track source value");
        entity.setWeight(new BigDecimal("12.34"));
    }

    private void assertSpaceTrackSourceValues(SatelliteEntity entity) {
        assertThat(entity.getObjectId()).isEqualTo("2026-180C");
        assertThat(entity.getObjectName()).isEqualTo("ELECTRON R/B");
        assertThat(entity.getNoradCatId()).isEqualTo(100245L);
        assertThat(entity.getCountry()).isEqualTo("US");
        assertThat(entity.getPeriod()).isEqualByComparingTo("92.58");
        assertThat(entity.getCatalogInclination()).isEqualByComparingTo("42.01");
        assertThat(entity.getApogee()).isEqualByComparingTo("567");
        assertThat(entity.getPerigee()).isEqualByComparingTo("235");
        assertThat(entity.getRcsSize()).isEqualTo("LARGE");
        assertThat(entity.getRcsValue()).isEqualByComparingTo("0.12");
        assertThat(entity.getLaunchDate()).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(entity.getCatalogComment()).isEqualTo("Space-Track source value");
        assertThat(entity.getWeight()).isEqualByComparingTo("12.34");
    }

    private void assertSpaceTrackSourceValues(TargetEntity entity) {
        assertThat(entity.getObjectId()).isEqualTo("2026-180C");
        assertThat(entity.getObjectName()).isEqualTo("ELECTRON R/B");
        assertThat(entity.getNoradCatId()).isEqualTo(100245L);
        assertThat(entity.getCountry()).isEqualTo("US");
        assertThat(entity.getPeriod()).isEqualByComparingTo("92.58");
        assertThat(entity.getCatalogInclination()).isEqualByComparingTo("42.01");
        assertThat(entity.getApogee()).isEqualByComparingTo("567");
        assertThat(entity.getPerigee()).isEqualByComparingTo("235");
        assertThat(entity.getRcsSize()).isEqualTo("LARGE");
        assertThat(entity.getRcsValue()).isEqualByComparingTo("0.12");
        assertThat(entity.getLaunchDate()).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(entity.getCatalogComment()).isEqualTo("Space-Track source value");
        assertThat(entity.getWeight()).isEqualByComparingTo("12.34");
    }

    private void assertSpaceTrackFieldMappings(Class<?> entityType) {
        Map<String, String> columnByField = java.util.Arrays.stream(entityType.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(com.baomidou.mybatisplus.annotation.TableField.class))
                .collect(Collectors.toMap(Field::getName,
                        field -> field.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class).value()));

        assertThat(columnByField).containsEntry("objectId", "OBJECT_ID_")
                .containsEntry("objectName", "OBJECT_NAME_")
                .containsEntry("noradCatId", "NORAD_CAT_ID_")
                .containsEntry("country", "COUNTRY_")
                .containsEntry("period", "PERIOD_")
                .containsEntry("catalogInclination", "INCLINATION_")
                .containsEntry("apogee", "APOGEE_")
                .containsEntry("perigee", "PERIGEE_")
                .containsEntry("rcsSize", "RCS_SIZE_")
                .containsEntry("rcsValue", "RCSVALUE_")
                .containsEntry("launchDate", "LAUNCH_")
                .containsEntry("catalogComment", "COMMENT_");
    }

    private String tableFieldColumn(Class<?> entityType, String fieldName) {
        return java.util.Arrays.stream(entityType.getDeclaredFields())
                .filter(field -> field.getName().equals(fieldName))
                .filter(field -> field.isAnnotationPresent(com.baomidou.mybatisplus.annotation.TableField.class))
                .map(field -> field.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class).value())
                .findFirst()
                .orElse(null);
    }

    private void assertSpaceTrackColumns(String tableName) throws Exception {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            java.sql.DatabaseMetaData metadata = connection.getMetaData();
            String resolvedTableName = tableName;
            String tableSchema = null;
            try (java.sql.ResultSet resultSet = metadata.getTables(connection.getCatalog(), null, "%", null)) {
                while (resultSet.next()) {
                    if (tableName.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
                        resolvedTableName = resultSet.getString("TABLE_NAME");
                        tableSchema = resultSet.getString("TABLE_SCHEM");
                        break;
                    }
                }
            }
            List<String> availableColumns = new java.util.ArrayList<>();
            try (java.sql.ResultSet resultSet = metadata.getColumns(connection.getCatalog(), tableSchema, resolvedTableName, null)) {
                while (resultSet.next()) {
                    availableColumns.add(resultSet.getString("COLUMN_NAME"));
                }
            }
            List<String> normalizedColumns = availableColumns.stream()
                    .map(column -> column.toUpperCase(java.util.Locale.ROOT))
                    .collect(Collectors.toList());
            assertThat(normalizedColumns)
                    .as("%s 表应包含全部 Space-Track 来源字段，实际列：%s", tableName, availableColumns)
                    .contains("OBJECT_ID_", "OBJECT_NAME_", "NORAD_CAT_ID_", "COUNTRY_", "PERIOD_", "INCLINATION_",
                            "APOGEE_", "PERIGEE_", "RCS_SIZE_", "RCSVALUE_", "LAUNCH_", "COMMENT_", "WEIGHT_");
        }
    }

    private SatelliteEntity createSatellite(String name, long noradCatalogNumber) {
        SatelliteEntity satellite = new SatelliteEntity();
        satellite.setName(name);
        satellite.setScid(noradCatalogNumber);
        satellite.setModelType("TLE_SGP4");
        satellite.setFirstOrbit("1 10023U 26001A   26001.00000000  .00000000  00000-0  00000-0 0  9995");
        satellite.setSecondOrbit("2 10023  98.0000 100.0000 0010000 100.0000 260.0000 14.00000000100000");
        return satellite;
    }

    private TargetEntity createTarget(String name, long noradCatalogNumber) {
        TargetEntity target = new TargetEntity();
        target.setName(name);
        target.setScid(noradCatalogNumber);
        target.setModelType("TLE_SGP4");
        target.setFirstOrbit("1 10023U 26001A   26001.00000000  .00000000  00000-0  00000-0 0  9995");
        target.setSecondOrbit("2 10023  98.0000 100.0000 0010000 100.0000 260.0000 14.00000000100000");
        return target;
    }
}
