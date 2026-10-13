package com.tx.alg.trajectory;

import com.tx.alg.OrbitCalculator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结原算法回归，断言及容差保持原版；参考样例位于独立test-data目录。 */
class TargetTrajectoryFitterInterpolationTest {

    @Test
    void package102观测点之间使用平滑插值并严格通过原始点() {
        List<TargetTrajectoryFitter.TrajectorySample> samples = new ArrayList<TargetTrajectoryFitter.TrajectorySample>();
        samples.add(sample(0.0, 7000000.0, 0.0, 0.0));
        samples.add(sample(10.0, 7000000.0, 1000.0, 0.0));

        TargetTrajectoryFitter.FittedTrajectory trajectory =
                TargetTrajectoryFitter.fitSamples(samples, 5.0, 0.0, 10.0);

        TargetTrajectoryFitter.FittedPoint start = pointAt(trajectory, 0.0);
        TargetTrajectoryFitter.FittedPoint middle = pointAt(trajectory, 5.0);
        TargetTrajectoryFitter.FittedPoint end = pointAt(trajectory, 10.0);

        assertNotNull(start);
        assertNotNull(middle);
        assertNotNull(end);
        assertTrue(start.original);
        assertFalse(middle.original);
        assertTrue(end.original);
        assertEquals(0.0, start.positionEciM.y, 1e-9);
        assertEquals(500.0, middle.positionEciM.y, 1e-6);
        assertEquals(1000.0, end.positionEciM.y, 1e-9);
        assertTrue(trajectory.warnings.get(0).startsWith("Package102拟合"));
        assertTrue(trajectory.warnings.get(0).contains("Hermite"));
    }

    @Test
    void outputEcefVelocityUsesStkFixedVelocityConvention() {
        OrbitCalculator.Vector3 velocity = new OrbitCalculator.Vector3(1000.0, 2000.0, 3000.0);
        List<TargetTrajectoryFitter.TrajectorySample> samples = new ArrayList<TargetTrajectoryFitter.TrajectorySample>();
        samples.add(sample(836166240.0, 7000000.0, 1200000.0, 3000000.0, velocity));
        samples.add(sample(836166250.0, 7010000.0, 1210000.0, 3005000.0, velocity));

        TargetTrajectoryFitter.FittedTrajectory trajectory =
                TargetTrajectoryFitter.fitSamples(samples, 10.0, 836166240.0, 836166250.0);

        TargetTrajectoryFitter.FittedPoint start = pointAt(trajectory, 836166240.0);
        OrbitCalculator.Vector3 rotatingFrameVelocity = OrbitCalculator.eciToEcefVel(
                start.velocityEciMs,
                start.positionEciM,
                start.absoluteTimeSec);
        OrbitCalculator.Vector3 positionEcef = OrbitCalculator.eciToEcefPos(
                start.positionEciM,
                start.absoluteTimeSec);
        OrbitCalculator.Vector3 earthRotationVelocity = new OrbitCalculator.Vector3(
                -OrbitCalculator.EARTH_ROTATION_RATE_RAD_S * positionEcef.y,
                OrbitCalculator.EARTH_ROTATION_RATE_RAD_S * positionEcef.x,
                0.0);
        OrbitCalculator.Vector3 expected = rotatingFrameVelocity.add(earthRotationVelocity);

        assertNotNull(start);
        assertEquals(expected.x, start.velocityEcefMs.x, 1e-9);
        assertEquals(expected.y, start.velocityEcefMs.y, 1e-9);
        assertEquals(expected.z, start.velocityEcefMs.z, 1e-9);
    }

    @Test
    void package102LessThanFortyPointsStillFitsAndReportsWarnings() throws Exception {
        TargetTrajectoryFitter.FitRequest request = new TargetTrajectoryFitter.FitRequest();
        request.taskName = "smallPackage102";
        request.taskStartTime = "2026-07-01 07:56:55.000";
        request.taskEndTime = "2026-07-01 07:57:45.000";
        request.interpolationStepSec = 5.0;
        request.outputDir = "target/test-output/small-package102";

        for (int i = 0; i < 6; i++) {
            TargetTrajectoryFitter.Package102Record record = new TargetTrajectoryFitter.Package102Record();
            record.track_tgt_id = "T-SMALL";
            record.tack3d_time_code = String.format("2026-07-01 07:57:%02d.000", i * 2);
            record.tack3d_tgt_pos_x = String.valueOf(70000000 + i * 20000);
            record.tack3d_tgt_pos_y = String.valueOf(i * 10000);
            record.tack3d_tgt_pos_z = "0";
            record.tack3d_tgt_vel_x = "10000000";
            record.tack3d_tgt_vel_y = "1000000";
            record.tack3d_tgt_vel_z = "0";
            record.tack3d_tgt_acc_x = "0";
            record.tack3d_tgt_acc_y = "0";
            record.tack3d_tgt_acc_z = "0";
            record.prop_tgt_type_8 = "0x01";
            record.prop_tgt_shape_8 = "0x02";
            record.prop_tgt_flight_phase_8 = "0x01";
            request.package102.add(record);
        }

        TargetTrajectoryFitter.FitRunResult result = TargetTrajectoryFitter.fitPackage102ToCsv(request);

        assertEquals(1, result.targetCount);
        assertEquals(1, result.files.size());
        assertTrue(result.files.get(0).pointCount >= request.package102.size());
        assertFalse(result.files.get(0).warnings.isEmpty());
    }

    @Test
    void package102InvalidPositionReportsReadableErrorBeforePropagation() {
        TargetTrajectoryFitter.FitRequest request = new TargetTrajectoryFitter.FitRequest();
        request.taskName = "invalidPackage102";
        request.taskStartTime = "2026-07-01 07:57:00.000";
        request.taskEndTime = "2026-07-01 07:57:10.000";
        request.interpolationStepSec = 1.0;
        request.outputDir = "target/test-output/invalid-package102";
        request.package102.add(package102Record("2026-07-01 07:57:00.000", "NaN", "0", "0"));
        request.package102.add(package102Record("2026-07-01 07:57:01.000", "70000010", "1000", "0"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TargetTrajectoryFitter.fitPackage102ToCsv(request));

        assertTrue(ex.getMessage().contains("位置数值无效"));
        assertTrue(ex.getMessage().contains("T-SMALL"));
    }

    @Test
    void describeFailureFallsBackToExceptionTypeWhenMessageIsEmpty() {
        String message = TargetTrajectoryFitter.describeFailure(new RuntimeException());

        assertTrue(message.contains("RuntimeException"));
        assertTrue(message.contains("无详细错误信息"));
    }

    @Test
    void 已生成弹道文件可按新发射时间重定时且保持地固轨迹状态不变() throws Exception {
        Path dir = Files.createDirectories(Paths.get("target/test-output/retime-trajectory"));
        Path sourceCsv = dir.resolve("source.csv");
        Files.write(sourceCsv, (
                "YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt,ECI_X,ECI_Y,ECI_Z,ECI_VX,ECI_VY,ECI_VZ,ECEF_Vx,ECEF_Vy,ECEF_Vz,isOriginal\n"
                        + "2026,07,01,08,00,00.000,1000.000,2000.000,3000.000,-1.000000000,0.000,1100.000,2100.000,3100.000,1.000000000,2.000000000,3.000000000,4.000000000,5.000000000,6.000000000,1\n"
                        + "2026,07,01,08,00,00.500,1001.000,2001.000,3001.000,-2.000000000,500.000,1101.000,2101.000,3101.000,1.100000000,2.100000000,3.100000000,4.100000000,5.100000000,6.100000000,0\n")
                .getBytes(StandardCharsets.UTF_8));

        TargetTrajectoryFitter.RetimeTrajectoryRequest request = new TargetTrajectoryFitter.RetimeTrajectoryRequest();
        request.taskName = "retimeTask";
        request.targetId = "RET-1";
        request.trajectoryCsvPath = sourceCsv.toString();
        request.launchTimeUtc = "2026/07/01 09:00:00.000";
        request.outputDir = dir.resolve("out").toString();

        TargetTrajectoryFitter.FitRunResult result = TargetTrajectoryFitter.retimeTrajectoryToCsv(request);

        assertEquals(1, result.targetCount);
        assertEquals("RETIMED_TRAJECTORY", result.files.get(0).model);
        List<String> outputLines = Files.readAllLines(Paths.get(result.files.get(0).csvPath), StandardCharsets.UTF_8);
        assertTrue(outputLines.get(0).endsWith(",timeIntervalMs"));
        assertTrue(outputLines.get(1).startsWith("2026,07,01,09,00,00.000,1000.000,2000.000"));
        assertTrue(outputLines.get(2).startsWith("2026,07,01,09,00,00.500,1001.000,2001.000"));
        assertTrue(outputLines.get(2).endsWith(",500.000"));
    }

    @Test
    void 重定时后应按新绝对时刻更新ECI位置和速度而保持ECEF状态不变() throws Exception {
        Path dir = Files.createDirectories(Paths.get("target/test-output/retime-trajectory-eci"));
        Path sourceCsv = dir.resolve("source.csv");
        Files.write(sourceCsv, (
                "YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt,ECI_X,ECI_Y,ECI_Z,ECI_VX,ECI_VY,ECI_VZ,ECEF_Vx,ECEF_Vy,ECEF_Vz,isOriginal\n"
                        + "2026,07,01,08,00,00.000,1000000.000,2000000.000,3000000.000,-1.000000000,0.000,0.000,0.000,0.000,0.000000000,0.000000000,0.000000000,4.000000000,5.000000000,6.000000000,1\n")
                .getBytes(StandardCharsets.UTF_8));

        TargetTrajectoryFitter.RetimeTrajectoryRequest request = new TargetTrajectoryFitter.RetimeTrajectoryRequest();
        request.trajectoryCsvPath = sourceCsv.toString();
        request.launchTimeUtc = "2026/07/01 09:00:00.000";
        request.outputDir = dir.resolve("out").toString();

        TargetTrajectoryFitter.FitRunResult result = TargetTrajectoryFitter.retimeTrajectoryToCsv(request);
        String[] row = Files.readAllLines(Paths.get(result.files.get(0).csvPath), StandardCharsets.UTF_8)
                .get(1).split(",", -1);

        double outputTime = OrbitCalculator.utcToJ2000Sec("2026/07/01 09:00:00.000");
        OrbitCalculator.Vector3 ecefPosition = new OrbitCalculator.Vector3(1_000_000.0, 2_000_000.0, 3_000_000.0);
        OrbitCalculator.Vector3 ecefVelocity = new OrbitCalculator.Vector3(4.0, 5.0, 6.0);
        OrbitCalculator.Vector3 expectedPosition = OrbitCalculator.ecefToEciPos(ecefPosition, outputTime);
        OrbitCalculator.Vector3 expectedVelocity = OrbitCalculator.ecefToEciVel(ecefVelocity, ecefPosition, outputTime);

        assertEquals(1_000_000.0, Double.parseDouble(row[6]), 1e-9);
        assertEquals(2_000_000.0, Double.parseDouble(row[7]), 1e-9);
        assertEquals(3_000_000.0, Double.parseDouble(row[8]), 1e-9);
        assertEquals(expectedPosition.x, Double.parseDouble(row[11]), 1e-3);
        assertEquals(expectedPosition.y, Double.parseDouble(row[12]), 1e-3);
        assertEquals(expectedPosition.z, Double.parseDouble(row[13]), 1e-3);
        assertEquals(expectedVelocity.x, Double.parseDouble(row[14]), 1e-8);
        assertEquals(expectedVelocity.y, Double.parseDouble(row[15]), 1e-8);
        assertEquals(expectedVelocity.z, Double.parseDouble(row[16]), 1e-8);
        assertNotEquals(0.0, Double.parseDouble(row[11]));
    }

    @Test
    void 系统模拟Wgs84端点使用经度纬度高度顺序() throws Exception {
        Path dir = Files.createDirectories(Paths.get("target/test-output/simulation-wgs84-order"));
        TargetTrajectoryFitter.SimulationRequest request = new TargetTrajectoryFitter.SimulationRequest();
        request.taskName = "wgs84Order";
        request.targetId = "SIM-WGS84";
        request.launchTimeUtc = "2026/07/01 08:22:30.000";
        request.impactTimeUtc = "2026/07/01 08:36:45.900";
        request.taskStartTime = request.launchTimeUtc;
        request.taskEndTime = request.impactTimeUtc;
        request.launchPointCoordFrame = "WGS84";
        request.launchPointUnit = "m";
        request.launchPointX = 111.57;
        request.launchPointY = 38.7;
        request.launchPointZ = 1600.0;
        request.impactPointCoordFrame = "WGS84";
        request.impactPointUnit = "m";
        request.impactPointX = 86.1746;
        request.impactPointY = 41.725;
        request.impactPointZ = 1000.0;
        request.outputPointDurationSec = 60.0;
        request.outputDir = dir.toString();

        TargetTrajectoryFitter.FitRunResult result = TargetTrajectoryFitter.simulateToCsv(request);

        List<String> lines = Files.readAllLines(Paths.get(result.files.get(0).csvPath), StandardCharsets.UTF_8);
        String[] first = lines.get(1).split(",", -1);
        double launchTime = OrbitCalculator.utcToJ2000Sec(request.launchTimeUtc);
        OrbitCalculator.Vector3 expectedEci = OrbitCalculator.geodeticToEci(38.7, 111.57, 1600.0, launchTime);
        OrbitCalculator.Vector3 expectedEcef = OrbitCalculator.eciToEcefPos(expectedEci, launchTime);
        assertEquals(expectedEcef.x, Double.parseDouble(first[6]), 1e-3);
        assertEquals(expectedEcef.y, Double.parseDouble(first[7]), 1e-3);
        assertEquals(expectedEcef.z, Double.parseDouble(first[8]), 1e-3);
    }

    private static TargetTrajectoryFitter.TrajectorySample sample(double timeSec, double x, double y, double z) {
        return sample(timeSec, x, y, z, new OrbitCalculator.Vector3(0.0, 0.0, 0.0));
    }

    private static TargetTrajectoryFitter.TrajectorySample sample(double timeSec,
                                                                  double x,
                                                                  double y,
                                                                  double z,
                                                                  OrbitCalculator.Vector3 velocityEciMs) {
        TargetTrajectoryFitter.TrajectorySample sample = new TargetTrajectoryFitter.TrajectorySample();
        sample.targetId = "T-1";
        sample.absoluteTimeSec = timeSec;
        sample.positionEciM = new OrbitCalculator.Vector3(x, y, z);
        sample.velocityEciMs = velocityEciMs;
        sample.accelerationEciMs2 = new OrbitCalculator.Vector3(0.0, 0.0, 0.0);
        sample.targetInfo = new TargetTrajectoryFitter.TargetInfo();
        sample.originalConstraint = true;
        return sample;
    }

    private static TargetTrajectoryFitter.Package102Record package102Record(String time,
                                                                            String x,
                                                                            String y,
                                                                            String z) {
        TargetTrajectoryFitter.Package102Record record = new TargetTrajectoryFitter.Package102Record();
        record.track_tgt_id = "T-SMALL";
        record.tack3d_time_code = time;
        record.tack3d_tgt_pos_x = x;
        record.tack3d_tgt_pos_y = y;
        record.tack3d_tgt_pos_z = z;
        record.tack3d_tgt_vel_x = "10000000";
        record.tack3d_tgt_vel_y = "1000000";
        record.tack3d_tgt_vel_z = "0";
        record.tack3d_tgt_acc_x = "0";
        record.tack3d_tgt_acc_y = "0";
        record.tack3d_tgt_acc_z = "0";
        record.prop_tgt_type_8 = "0x01";
        record.prop_tgt_shape_8 = "0x02";
        record.prop_tgt_flight_phase_8 = "0x01";
        return record;
    }

    private static TargetTrajectoryFitter.FittedPoint pointAt(TargetTrajectoryFitter.FittedTrajectory trajectory,
                                                              double timeSec) {
        for (TargetTrajectoryFitter.FittedPoint point : trajectory.points) {
            if (Math.abs(point.absoluteTimeSec - timeSec) < 1e-9) {
                return point;
            }
        }
        return null;
    }
}
