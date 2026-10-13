package com.tx.alg.api;

import com.tx.alg.OrbitCalculator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 原轨道可见性控制器兼容类，供原Java调用及完整业务回归使用。
 * 当前包不在空间主程序组件扫描范围内；正式弹道HTTP服务于后续事项接入。
 *
 * <p>将 {@link OrbitCalculator#searchLaunchOffsetsV2} 封装为 HTTP POST 接口，
 * 输入/输出均为 JSON，输出格式与桌面应用导出的 JSON 文件完全一致。</p>
 */
@RestController
@RequestMapping("/api/orbit")
public class OrbitVisibilityController {

    /**
     * 健康检查。
     * <pre>GET /api/orbit/health</pre>
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * 轨道可见性搜索主接口。
     *
     * <pre>
     * POST /api/orbit/visibility/search
     * Content-Type: application/json
     *
     * 请求体见 {@link VisibilityRequest}，所有字段可选。
     *
     * 返回 JSON 与桌面应用导出的 visibility_result_*.json 格式完全一致，
     * 结构见 {@link OrbitCalculator.LaunchSearchResultV2#toJson()}。
     * </pre>
     */
    @PostMapping(value = "/visibility/search",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestBody VisibilityRequest request) {
        try {
            OrbitCalculator.utcOffsetSec = 0.0;

            // 1. 解析卫星列表（空则用默认星座）
            List<OrbitCalculator.OrbitElements> satellites = request.getSatellites().isEmpty()
                    ? defaultSatellites()
                    : request.toSatellites();

            OrbitCalculator.LaunchSearchResultV2 result;
            if (!request.getBallistics().isEmpty()) {
                result = OrbitCalculator.searchLaunchOffsetsV2(
                        request.toBallisticInputs(),
                        request.getStartOffsetSec(),
                        request.getStepSizeSec(),
                        request.getNumCalculations(),
                        satellites,
                        request.getSensorFovDeg(),
                        request.toOrbitModel(),
                        request.getTargetSampleStepSec(),
                        request.getTrajectoryPointOutput(),
                        request.toSensorVisibilityConfig(),
                        null);
            } else {
                // 2. 兼容旧单目标输入：解析弹道文件，支持CSV和Excel
                OrbitCalculator.ParsedBallisticCsv parsedBallistic =
                        OrbitCalculator.parseBallisticCsvWithReference(request.getBallisticCsvPath());
                List<OrbitCalculator.BallisticPoint> ballisticPoints = parsedBallistic.points;

                // 3. 确定基准发射时间
                String utcInput = request.getBaseLaunchUtc();
                double baseLaunchJ2000;
                if (utcInput != null && !utcInput.trim().isEmpty()) {
                    baseLaunchJ2000 = OrbitCalculator.utcToJ2000Sec(utcInput.trim());
                } else {
                    baseLaunchJ2000 = parsedBallistic.referenceEpochSec;
                }

                // 4. 执行单目标搜索
                result = OrbitCalculator.searchLaunchOffsetsV2(
                        baseLaunchJ2000,
                        request.getStartOffsetSec(),
                        request.getStepSizeSec(),
                        request.getNumCalculations(),
                        satellites,
                        ballisticPoints,
                        request.getSensorFovDeg(),
                        request.toOrbitModel(),
                        request.getTargetSampleStepSec(),
                        parsedBallistic.referenceEpochSec,
                        request.getTrajectoryPointOutput(),
                        request.toSensorVisibilityConfig(),
                        null);
            }

            // 5. 流式返回JSON（结构与导出文件一致，避免大结果一次性占用内存）
            StreamingResponseBody body = outputStream -> {
                OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                result.writeJsonTo(writer, true);
                writer.flush();
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);

        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"" + jsonEscape(ex.getMessage()) + "\"}");
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body("{\"error\":\"" + jsonEscape(ex.getMessage()) + "\"}");
        }
    }

    // ========== 内置默认星座 ==========

    private static List<OrbitCalculator.OrbitElements> defaultSatellites() {
        List<OrbitCalculator.OrbitElements> list = new ArrayList<>();
        list.add(defaultSat("SAT-A", 30.0));
        list.add(defaultSat("SAT-B", 0.0));
        return list;
    }

    private static OrbitCalculator.OrbitElements defaultSat(String name, double meanAnomalyDeg) {
        OrbitCalculator.OrbitElements e = new OrbitCalculator.OrbitElements();
        e.satelliteId = name;
        e.name = name;
        e.semiMajorAxisM = 7530612.0;
        e.eccentricity = 0.00085;
        e.inclinationDeg = 64.8;
        e.raanDeg = 120.0;
        e.argumentOfPerigeeDeg = 270.0;
        e.meanAnomalyDeg = meanAnomalyDeg;
        e.epochSec = OrbitCalculator.getDefaultSatelliteEpochSec();
        return e;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
