package com.runisys.orbit.spacevisibility.application.ballistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileConfiguration;
import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileStorage;
import com.runisys.orbit.spacevisibility.infrastructure.files.ManagedFileException;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import com.tx.alg.trajectory.TargetTrajectoryFitter.SimulationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实模拟及磁盘作用域验证：不得修改调用方 DTO，失败清理只回滚缺省独占目录，不影响显式调用者。 */
class BallisticSimulationServiceTest {
    @TempDir
    Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 全部原请求字段和事件保持不变且Java默认仍为点目录() throws Exception {
        BallisticFileConfiguration config = config(10000000);
        BallisticSimulationService service = new BallisticSimulationService(new BallisticFileStorage(config, mapper));
        SimulationRequest request = fixture();
        request.taskName = "完整请求";
        request.targetId = "完整目标";
        request.targetTypeCode = "0x03";
        request.targetShapeCode = "0x04";
        request.flightPhaseCode = "0x05";
        request.launchLatDeg = 25;
        request.launchLonDeg = 115;
        request.launchHeightM = 1300;
        request.initialSpeedMs = 3000;
        request.azimuthDeg = 95;
        request.elevationDeg = 60;
        request.durationSec = 777;
        request.integrationStepSec = 0.25;
        request.outputStepSec = 2;
        request.boostDurationSec = 10;
        request.boostAccelerationMs2 = 25;
        request.dragCdAreaOverMass = 0.25;
        TargetTrajectoryFitter.PhaseEvent event = new TargetTrajectoryFitter.PhaseEvent();
        event.phaseType = "BOOST";
        event.eventTime = "元数据";
        event.deltaVms = 20;
        request.phaseEvents.add(event);
        request.outputDir = ".";
        JsonNode before = mapper.valueToTree(request);
        TargetTrajectoryFitter.FitRunResult result = service.simulate(request, false);
        assertEquals(before, mapper.valueToTree(request));
        assertSame(event, request.phaseEvents.get(0));
        assertEquals(".", new SimulationRequest().outputDir);
        assertTrue(Paths.get(result.files.get(0).csvPath).toRealPath().startsWith(config.results.toRealPath()));
        assertEquals("完整请求", result.taskName);
        assertEquals("完整目标", result.files.get(0).targetId);
        assertTrue(result.files.get(0).warnings.stream().anyMatch(value -> value.contains("phaseEvents") && value.contains("数量=1")));
    }

    @Test
    void null以及步长发射目录组合错误均保持原先后且不创建工作区() throws Exception {
        BallisticFileConfiguration config = config(10000000);
        BallisticSimulationService service = new BallisticSimulationService(new BallisticFileStorage(config, mapper));
        SimulationRequest missing = fixture();
        missing.launchTimeUtc = null;
        missing.outputDir = "invalid\u0000directory";
        SimulationRequest tiny = fixture();
        tiny.outputPointDurationSec = 0.0001;
        tiny.launchTimeUtc = null;
        tiny.outputDir = missing.outputDir;
        for (SimulationRequest request : new SimulationRequest[]{null, missing, tiny}) {
            Exception original = assertThrows(Exception.class, () -> TargetTrajectoryFitter.simulateToCsv(request));
            Exception actual = assertThrows(Exception.class, () -> service.simulate(request, true));
            assertEquals(original.getClass(), actual.getClass());
            assertEquals(TargetTrajectoryFitter.describeFailure(original), TargetTrajectoryFitter.describeFailure(actual));
        }
        assertEmpty(config.results);
        assertEmpty(config.metadata);
    }

    @Test
    void 计算失败和登记超限清理自身而显式旧文件及其他结果保留() throws Exception {
        BallisticFileConfiguration config = config(10000000);
        BallisticFileStorage storage = new BallisticFileStorage(config, mapper);
        BallisticSimulationService service = new BallisticSimulationService(storage);
        SimulationRequest invalid = fixture();
        invalid.impactTimeUtc = invalid.launchTimeUtc;
        JsonNode before = mapper.valueToTree(invalid);
        assertThrows(IllegalArgumentException.class, () -> service.simulate(invalid, false));
        assertEquals(before, mapper.valueToTree(invalid));
        assertEmpty(config.results);
        assertEmpty(config.metadata);
        Path explicit = Files.createDirectory(root.resolve("external"));
        Path preserved = Files.write(explicit.resolve("keep.csv"), new byte[]{7});
        invalid.outputDir = explicit.toString();
        assertThrows(IllegalArgumentException.class, () -> service.simulate(invalid, true));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(preserved));

        BallisticFileConfiguration limitedConfig = config(16);
        BallisticFileStorage limited = new BallisticFileStorage(limitedConfig, mapper);
        Path other = Files.write(limitedConfig.results.resolve("other.csv"), new byte[]{3});
        String id = limited.registerResult(other.toString(), "other", "other").fileId;
        assertThrows(ManagedFileException.class, () -> new BallisticSimulationService(limited).simulate(fixture(), false));
        try (BallisticFileStorage.Lease lease = limited.acquire(id)) {
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(lease.path));
        }
        try (Stream<Path> files = Files.list(limitedConfig.results)) {
            assertEquals(1, files.count(), "超限失败必须回滚本请求独占目录，不能删除已登记的其他结果");
        }
    }

    private BallisticFileConfiguration config(long limit) throws Exception {
        return new BallisticFileConfiguration(root.toString(), "", "", "", "", limit, 60000, 500);
    }

    private SimulationRequest fixture() throws Exception {
        return mapper.readValue(Files.readAllBytes(Paths.get("../test-data/reference/ballistic/target_trajectory_simulate_default.json")), SimulationRequest.class);
    }

    private void assertEmpty(Path path) throws Exception {
        try (Stream<Path> files = Files.list(path)) {
            assertEquals(0, files.count());
        }
    }
}
