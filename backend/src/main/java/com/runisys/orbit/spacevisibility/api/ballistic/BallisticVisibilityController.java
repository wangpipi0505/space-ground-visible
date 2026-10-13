package com.runisys.orbit.spacevisibility.api.ballistic;

import com.runisys.orbit.spacevisibility.application.ballistic.BallisticVisibilityService;
import com.tx.alg.api.OrbitVisibilityController;
import com.tx.alg.api.VisibilityRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 统一 Spring Boot 扫描范围内的正式弹道窗口端点。
 * 继承原健康检查及 search 薄适配，保留原 DTO、400/500 手写 error 对象和 StreamingResponseBody；
 * 仅注入 Web 文件生命周期的应用服务。原 com.tx.alg.api 包不扫描，故两个路由只登记一次。
 */
@RestController
@RequestMapping("/api/orbit")
public class BallisticVisibilityController extends OrbitVisibilityController {
    /** 由 Spring 注入唯一应用编排，业务和数值逻辑不放入控制器。 */
    public BallisticVisibilityController(BallisticVisibilityService service) {
        super(service);
    }

    /**
     * MVC 依据声明的泛型选择流式处理器，原 ResponseEntity<?> 会将成功 lambda 当作普通对象序列化。
     * 因此正式 HTTP 明确声明 StreamingResponseBody；原 Java 方法的签名保持不变。
     * 成功直接透传原 writeJsonTo 流；失败只将原 error 字符串按 UTF-8 写出，不增加错误包装或更改状态。
     */
    @Override
    @PostMapping(value = "/visibility/search", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> search(@RequestBody VisibilityRequest request) {
        ResponseEntity<?> response = super.search(request);
        Object value = response.getBody();
        StreamingResponseBody body = value instanceof StreamingResponseBody
                ? (StreamingResponseBody) value
                : output -> output.write(((String) value).getBytes(StandardCharsets.UTF_8));
        // 流式处理器不会像原字符串消息转换器那样推断 produces；错误流也必须保持原 JSON 媒体类型。
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, headers, response.getStatusCode());
    }
}
