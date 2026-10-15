import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tx.alg.api.VisibilityRequest;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 原格式文档核验：绑定真实DTO，并在隔离运行目录完整执行拟合示例；不改动用户服务和生产结果。 */
public final class DocumentDtoProbe {
    private DocumentDtoProbe() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode examples = mapper.readTree(Files.readAllBytes(Paths.get(args[0])));
        int count = 0;
        int fitCount = 0;
        for (JsonNode example : examples) {
            JsonNode value = example.get("value");
            String kind = example.get("kind").asText();
            if ("visibility".equals(kind)) {
                VisibilityRequest request = mapper.treeToValue(value, VisibilityRequest.class);
                request.toSatellites();
                request.toSensorVisibilityConfig();
                request.toOrbitModel();
                if (request.getBallistics().isEmpty() && (request.getBallisticCsvPath() == null || request.getBallisticCsvPath().trim().isEmpty())) {
                    throw new AssertionError("窗口示例缺少目标");
                }
            } else if ("fit".equals(kind)) {
                TargetTrajectoryFitter.FitRequest request = mapper.treeToValue(value, TargetTrajectoryFitter.FitRequest.class);
                // 文档中的HTTP请求省略输出目录；直接Java调用必须显式改为本次隔离目录，避免写入工程根目录。
                request.outputDir = Paths.get(args[1], "fit-block-" + example.get("block").asInt()).toString();
                TargetTrajectoryFitter.FitRunResult result = TargetTrajectoryFitter.fitPackage102ToCsv(request);
                if (result.targetCount < 1 || result.files.size() != result.targetCount) {
                    throw new AssertionError("拟合示例没有产生完整目标结果");
                }
                for (TargetTrajectoryFitter.FileResult file : result.files) {
                    Path csv = Paths.get(file.csvPath);
                    if (file.pointCount < 2 || !Files.isRegularFile(csv) || Files.size(csv) == 0) {
                        throw new AssertionError("拟合示例没有生成非空CSV");
                    }
                    System.out.println("拟合执行成功：" + file.targetId + "，点数=" + file.pointCount + "，告警=" + file.warnings.size());
                }
                fitCount++;
            } else if ("simulate".equals(kind)) {
                TargetTrajectoryFitter.validateSimulationRequest(mapper.treeToValue(value, TargetTrajectoryFitter.SimulationRequest.class));
            } else if ("retime".equals(kind)) {
                TargetTrajectoryFitter.RetimeTrajectoryRequest request = mapper.treeToValue(value, TargetTrajectoryFitter.RetimeTrajectoryRequest.class);
                if (request.trajectoryCsvPath == null || request.trajectoryCsvPath.trim().isEmpty()) {
                    throw new AssertionError("重定时缺少示意源路径");
                }
                com.tx.alg.OrbitCalculator.utcToJ2000Sec(request.launchTimeUtc);
            } else if ("fit-result".equals(kind)) {
                mapper.treeToValue(value, TargetTrajectoryFitter.FitRunResult.class);
            }
            System.out.println(example.get("document").asText() + " #" + example.get("block").asInt() + " " + kind + " PASS");
            count++;
        }
        System.out.println("文档核验通过：" + count + "个DTO对象；" + fitCount + "个拟合示例完整计算并写入隔离目录，其余对象只校验绑定和前置参数。");
    }
}
