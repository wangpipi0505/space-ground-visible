package com.runisys.orbit.spacevisibility.api.ballistic;

import com.runisys.orbit.spacevisibility.application.ballistic.BallisticVisibilityService;
import com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** 窗口搜索 HTTP 边界：绑定请求、调用应用服务、输出既有 JSON；计算及文件下载由各自层负责。 */
@RestController
@RequestMapping("/api/orbit")
public class BallisticVisibilityController {
    private final BallisticVisibilityService service;

    public BallisticVisibilityController(BallisticVisibilityService service) {
        this.service = service;
    }

    /** 健康检查沿用纯文本 OK。 */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /**
     * 明确声明流式泛型，使 MVC 使用流式处理器，避免将成功回调作为普通对象序列化。
     * 成功直接输出领域结果；参数错误为 400，其余执行错误为 500，沿用单字段 error JSON。
     */
    @PostMapping(value = "/visibility/search", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> search(@RequestBody VisibilityRequest request) {
        try {
            OrbitCalculator.LaunchSearchResultV2 result = service.search(request);
            StreamingResponseBody body = output -> {
                OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                result.writeJsonTo(writer, true);
                writer.flush();
            };
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (IllegalArgumentException ex) {
            return error(400, ex.getMessage());
        } catch (Exception ex) {
            return error(500, ex.getMessage());
        }
    }

    /** 保留既有转义规则和 UTF-8 字节，不增加错误包装或字段。 */
    private static ResponseEntity<StreamingResponseBody> error(int status, String message) {
        String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        byte[] json = ("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(output -> output.write(json));
    }
}
