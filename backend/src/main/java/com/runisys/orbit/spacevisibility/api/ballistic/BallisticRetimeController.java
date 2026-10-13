package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.application.ballistic.BallisticRetimeService;
import com.tx.alg.api.TargetTrajectoryFitController.ErrorResponse;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 正式重定时 HTTP 边界：保留五字段 DTO 和原响应，记录目录字段存在性；健康沿用既有唯一入口。 */
@RestController
@RequestMapping("/api/trajectory")
public class BallisticRetimeController {
    private final BallisticRetimeService service;
    private final ObjectMapper mapper;

    public BallisticRetimeController(BallisticRetimeService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 计算参数异常返回原 400 error，其余计算/存储异常返回原 500 error；绑定失败沿用项目实际 MVC 策略。 */
    @PostMapping(value = "/retime", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> retime(@RequestBody JsonNode body) throws java.io.IOException {
        TargetTrajectoryFitter.RetimeTrajectoryRequest request;
        try {
            request = mapper.treeToValue(body, TargetTrajectoryFitter.RetimeTrajectoryRequest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new org.springframework.http.converter.HttpMessageNotReadableException("重定时请求字段无法解析", ex);
        }
        try {
            return ResponseEntity.ok(service.retime(request, body.has("outputDir")));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        }
    }
}
