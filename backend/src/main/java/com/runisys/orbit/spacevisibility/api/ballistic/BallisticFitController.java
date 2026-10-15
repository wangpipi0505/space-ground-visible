package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticFitService;
import com.runisys.orbit.spacevisibility.domain.ballistic.trajectory.TargetTrajectoryFitter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 正式 102 拟合 HTTP 边界。原 FitInput 默认 outputDir="."，仅靠反序列化后值无法判断是否提交，
 * 因此在此检查原 JSON 字段存在性后仍绑定原 DTO；字段、缺省值和数值均由原 DTO/拟合器解释。
 * 拟合、模拟和重定时各自拥有唯一控制器，共用领域输入字段和错误响应。
 */
@RestController
@RequestMapping("/api/trajectory")
public class BallisticFitController {
    private final BallisticFitService service;
    private final ObjectMapper mapper;

    public BallisticFitController(BallisticFitService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 保留原健康检查纯文本响应，不加入统一响应包装。 */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /** 只把字段存在性作为 Web 存储选项；成功原样返回 FitRunResult，错误沿用原状态和 describeFailure。 */
    @PostMapping(value = "/fit/package102", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> fitPackage102(@RequestBody JsonNode body) throws java.io.IOException {
        // DTO 转换失败仍交给 MVC 的消息转换异常策略，不能当成拟合器内部 500。
        BallisticTrajectoryRequests.FitRequest request;
        try {
            request = mapper.treeToValue(body, BallisticTrajectoryRequests.FitRequest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new org.springframework.http.converter.HttpMessageNotReadableException("拟合请求字段无法解析", ex);
        }
        try {
            return ResponseEntity.ok(service.fitPackage102(request, body.has("outputDir")));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new BallisticErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(new BallisticErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        }
    }
}
