package com.runisys.orbit.spacevisibility.domain.ballistic.visibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticVisibilityService;
import com.runisys.orbit.spacevisibility.api.ballistic.VisibilityRequest;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * A11重构前后独立输出探针：同一源分别绑定两个版本class，不用当前输出生成期望值。
 * 覆盖真实三模型请求、完整/摘要轨迹、空结果、载荷与目标分组、中文格式及失败的Writer。
 * 比较文件逐字节执行；不对数字、数组、空值或空白作归一化。
 */
public final class SerializationProbe {
    private SerializationProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        ObjectMapper mapper = new ObjectMapper();
        try (Stream<Path> requests = Files.list(Paths.get(args[1]))) {
            for (Path request : (Iterable<Path>) requests.sorted()::iterator) {
                if (request.getFileName().toString().startsWith("visibility-") && request.toString().endsWith("-request.json")) {
                    VisibilityRequest input = mapper.readValue(Files.readAllBytes(request), VisibilityRequest.class);
                    emit(output, request.getFileName().toString().replace("-request.json", ""),
                            new BallisticVisibilityService().search(input));
                }
            }
        }
        emit(output, "empty", new OrbitCalculator.LaunchSearchResultV2(Collections.emptyList(), null, "引号\"与反斜杠\\"));
        OrbitCalculator.LaunchOffsetCalcResult calc = new OrbitCalculator.LaunchOffsetCalcResult();
        calc.launchTimeUtc = "2026/07/01 10:00:00";
        calc.launchTimeJ2000Sec = OrbitCalculator.utcToJ2000Sec(calc.launchTimeUtc);
        calc.sourceReferenceEpochSec = calc.launchTimeJ2000Sec;
        emit(output, "no-arcs", result(calc));
        for (int i = 0; i < 4; i++) {
            OrbitCalculator.SatelliteVisibilityResult arc = new OrbitCalculator.SatelliteVisibilityResult();
            arc.satelliteId = i == 3 ? null : "SAT-\"A";
            arc.satelliteName = "卫星\\测试";
            arc.satelliteIndex = i == 3 ? 2 : 1;
            arc.payloadId = "P" + (i % 2);
            arc.payloadName = "载荷" + (i % 2);
            arc.payloadType = "对地监视";
            arc.targetId = "T" + (i / 2);
            arc.targetName = "目标" + (i / 2);
            arc.visiblePointCount = i + 2;
            arc.startJ2000Sec = calc.launchTimeJ2000Sec + i;
            arc.endJ2000Sec = arc.startJ2000Sec + 5.0;
            arc.startRelativeTimeSec = i;
            arc.endRelativeTimeSec = i + 5.0;
            calc.visibleSatellites.add(arc);
        }
        emit(output, "metadata-groups", result(calc));
        for (OrbitCalculator.SatelliteVisibilityResult arc : calc.visibleSatellites) {
            arc.trajectoryPoints.add(new OrbitCalculator.VisibleTrajectoryPoint(120, 30, 1000,
                    arc.startJ2000Sec, arc.startRelativeTimeSec));
            arc.trajectoryPoints.add(new OrbitCalculator.VisibleTrajectoryPoint(121, 31, 2000,
                    arc.endJ2000Sec, arc.endRelativeTimeSec));
        }
        emit(output, "point-groups", result(calc));
        emit(output, "best-null", new OrbitCalculator.LaunchSearchResultV2(Arrays.asList(calc), null, calc.launchTimeUtc));
        System.out.println("输出探针完成；Writer故障均按IOException向上传递。");
    }

    private static OrbitCalculator.LaunchSearchResultV2 result(OrbitCalculator.LaunchOffsetCalcResult calc) {
        return new OrbitCalculator.LaunchSearchResultV2(Arrays.asList(calc), calc, "2026/07/01 10:00:00");
    }

    private static void emit(Path output, String name, OrbitCalculator.LaunchSearchResultV2 result) throws IOException {
        for (boolean includeBest : new boolean[]{true, false}) {
            String text = result.toJson(includeBest);
            StringWriter streamed = new StringWriter();
            result.writeJsonTo(streamed, includeBest);
            if (!text.equals(streamed.toString())) {
                throw new AssertionError("字符串与流式输出不一致：" + name + "/" + includeBest);
            }
            Files.write(output.resolve(name + "-" + includeBest + ".json"), text.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(output.resolve(name + "-chinese.txt"), result.toChineseText().getBytes(StandardCharsets.UTF_8));
        List<String> chinese = new ArrayList<>();
        for (OrbitCalculator.LaunchOffsetCalcResult calc : result.allResults) {
            chinese.add(calc.toChineseJson());
        }
        Files.write(output.resolve(name + "-chinese-json.txt"), String.join("\n", chinese).getBytes(StandardCharsets.UTF_8));
        try {
            result.writeJsonTo(new Writer() {
                @Override
                public void write(char[] buffer, int offset, int length) throws IOException {
                    throw new IOException("探针模拟写入失败");
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            }, true);
            throw new AssertionError("Writer异常被吞掉：" + name);
        } catch (IOException expected) {
            if (!"探针模拟写入失败".equals(expected.getMessage())) {
                throw expected;
            }
        }
    }
}
