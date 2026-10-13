package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticSimulationService;
import com.tx.alg.api.TargetTrajectoryFitController.ErrorResponse;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 正式模拟 HTTP 边界：记录 outputDir 是否提交再绑定原 DTO，健康入口继续由拟合控制器唯一注册。 */
@RestController
@RequestMapping("/api/trajectory")
public class BallisticSimulationController {
    private final BallisticSimulationService service;
    private final ObjectMapper mapper;

    public BallisticSimulationController(BallisticSimulationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 原成功响应 FitRunResult 与 400/500 describeFailure 错误体保持；字段绑定错误沿用 MVC 消息转换策略。 */
    @PostMapping(value = "/simulate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> simulate(@RequestBody JsonNode body) throws java.io.IOException {
        TargetTrajectoryFitter.SimulationRequest request;
        try {
            request = mapper.treeToValue(body, TargetTrajectoryFitter.SimulationRequest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new org.springframework.http.converter.HttpMessageNotReadableException("模拟请求字段无法解析", ex);
        }
        try {
            return ResponseEntity.ok(service.simulate(request, body.has("outputDir")));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        }
    }
}
