package com.tx.alg.api;

import com.tx.alg.OrbitCalculator;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticVisibilityService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 原轨道可见性控制器兼容类，供原Java调用及完整业务回归使用。
 * 当前包不在空间主程序组件扫描范围内；正式控制器继承此薄 HTTP 适配以共享原流式与错误协议。
 *
 * <p>将 {@link OrbitCalculator#searchLaunchOffsetsV2} 封装为 HTTP POST 接口，
 * 输入/输出均为 JSON，输出格式与桌面应用导出的 JSON 文件完全一致。</p>
 */
@RestController
@RequestMapping("/api/orbit")
public class OrbitVisibilityController {
    private final BallisticVisibilityService service;

    /** 原公开无参构造保留，直接 Java 调用使用同一编排及原文件解析入口。 */
    public OrbitVisibilityController() {
        this(new BallisticVisibilityService());
    }

    /** 正式 Web 子类注入具备文件租约/作用域的应用服务，避免复制业务与响应协议。 */
    protected OrbitVisibilityController(BallisticVisibilityService service) {
        this.service = service;
    }


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
            OrbitCalculator.LaunchSearchResultV2 result = service.search(request);

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

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
