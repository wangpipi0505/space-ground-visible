package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.api.ballistic.BallisticVisibilityController;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticVisibilityService;
import com.runisys.orbit.spacevisibility.api.ballistic.VisibilityRequest;
import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import com.runisys.orbit.spacevisibility.domain.ballistic.trajectory.TargetTrajectoryFitter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.file.*;
import java.io.*;
import java.util.List;

/**
 * T04 原版业务入口基准：直接反序列化冻结 DTO，调用原控制器/算法，不建立 Spring 或桌面窗口。
 * 请求中的采样、载荷、时间、模型字段原样传入；仅生成文件的路径和文件名规范化，数值和 CSV 字节不改。
 * args 为操作名、请求 JSON、输出目录；未来迁入后使用相同入口编译执行。
 */
public class BallisticBaseline {
    /** 执行一个真实业务案例；异常或非 200 响应立即失败，防止错误响应被误存为成功基准。 */
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path request = Paths.get(args[1]);
        Path output = Paths.get(args[2]);
        Files.createDirectories(output);
        if ("visibility-legacy".equals(args[0])) {
            VisibilityRequest input = mapper.readValue(request.toFile(), VisibilityRequest.class);
            OrbitCalculator.utcOffsetSec = 0.0;
            List<OrbitCalculator.OrbitElements> satellites = input.toSatellites();
            // 原 Java 兼容入口的 140° 简单视场锥不属于 PayloadDef 字段，不向 DTO 添加虚构字段。
            for (OrbitCalculator.OrbitElements satellite : satellites) {
                for (OrbitCalculator.SatellitePayloadConfig payload : satellite.payloads) {
                    payload.sensorConfig = OrbitCalculator.SensorVisibilityConfig.legacySimpleCone(input.getSensorFovDeg());
                }
            }
            OrbitCalculator.LaunchSearchResultV2 result = OrbitCalculator.searchLaunchOffsetsV2(
                    input.toBallisticInputs(), input.getStartOffsetSec(), input.getStepSizeSec(),
                    input.getNumCalculations(), satellites, input.getSensorFovDeg(), input.toOrbitModel(),
                    input.getTargetSampleStepSec(), input.getTrajectoryPointOutput(),
                    OrbitCalculator.SensorVisibilityConfig.legacySimpleCone(input.getSensorFovDeg()), null,
                    com.runisys.orbit.spacevisibility.infrastructure.ballistic.files.TrajectoryFileResolver::parseBallisticCsvWithReference);
            try (Writer writer = Files.newBufferedWriter(output.resolve("result.json"))) {
                result.writeJsonTo(writer, true);
            }
            return;
        }
        if ("visibility".equals(args[0])) {
            VisibilityRequest input = mapper.readValue(request.toFile(), VisibilityRequest.class);
            ResponseEntity<?> response = new BallisticVisibilityController(new BallisticVisibilityService()).search(input);
            if (response.getStatusCodeValue() != 200) {
                throw new IllegalStateException("原入口失败：" + response.getBody());
            }
            try (OutputStream stream = Files.newOutputStream(output.resolve("result.json"))) {
                ((StreamingResponseBody) response.getBody()).writeTo(stream);
            }
            return;
        }
        TargetTrajectoryFitter.FitRunResult result;
        if ("fit".equals(args[0])) {
            TargetTrajectoryFitter.FitInput input = mapper.readValue(request.toFile(), TargetTrajectoryFitter.FitInput.class);
            input.outputDir = output.toString();
            result = TargetTrajectoryFitter.fitPackage102ToCsv(input);
        } else if ("simulate".equals(args[0])) {
            TargetTrajectoryFitter.SimulationInput input = mapper.readValue(request.toFile(), TargetTrajectoryFitter.SimulationInput.class);
            input.outputDir = output.toString();
            result = TargetTrajectoryFitter.simulateToCsv(input);
        } else if ("retime".equals(args[0])) {
            TargetTrajectoryFitter.RetimeInput input = mapper.readValue(request.toFile(), TargetTrajectoryFitter.RetimeInput.class);
            input.outputDir = output.toString();
            result = TargetTrajectoryFitter.retimeTrajectoryToCsv(input);
        } else {
            throw new IllegalArgumentException("未知基准操作：" + args[0]);
        }
        // 文件名中的运行时间戳不属于数值合同。按原 files 顺序编号，CSV 内容逐字节保持。
        for (int index = 0; index < result.files.size(); index++) {
            TargetTrajectoryFitter.FileResult file = result.files.get(index);
            Path generated = Paths.get(file.csvPath);
            Path canonical = output.resolve("trajectory-" + index + ".csv");
            if (!generated.equals(canonical)) {
                Files.move(generated, canonical, StandardCopyOption.REPLACE_EXISTING);
            }
            file.csvPath = canonical.getFileName().toString();
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), result);
    }
}
