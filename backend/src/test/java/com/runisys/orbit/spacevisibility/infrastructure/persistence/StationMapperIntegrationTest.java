package com.runisys.orbit.spacevisibility.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.StationEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.StationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StationMapperIntegrationTest {

    @Autowired
    private StationMapper stationMapper;

    @BeforeEach
    void clearStations() {
        stationMapper.delete(null);
    }

    @Test
    void queriesStationByNameWithLambdaWrapper() {
        StationEntity station = new StationEntity();
        station.setId("STA-001");
        station.setName("测试站");
        station.setLongitude(new BigDecimal("116.391000"));
        station.setLatitude(new BigDecimal("39.907000"));
        station.setHeight(new BigDecimal("43.00"));
        station.setMinElevation(new BigDecimal("10.00"));
        stationMapper.insert(station);

        List<StationEntity> stations = stationMapper.selectList(
                new LambdaQueryWrapper<StationEntity>()
                        .eq(StationEntity::getName, "测试站"));

        assertThat(stations).extracting(StationEntity::getId).containsExactly("STA-001");
    }
}
