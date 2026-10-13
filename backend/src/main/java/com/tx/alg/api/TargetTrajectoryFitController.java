package com.tx.alg.api;

import com.tx.alg.trajectory.TargetTrajectoryFitter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原轨迹控制器 Java 兼容入口，保留原公开签名、注解及错误 DTO。此包不被主程序扫描；正式 Web 仅通过独立控制器按事项逐条接入。
 */
@RestController
@RequestMapping("/api/trajectory")
public class TargetTrajectoryFitController {

    /** 旧 Java 健康调用仍返回原纯文本 OK。 */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /** 旧 Java 拟合调用按原 outputDir 语义直接委托数值主体，默认仍为当前工作目录。 */
    @PostMapping(value = "/fit/package102",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> fitPackage102(@RequestBody TargetTrajectoryFitter.FitRequest request) {
        try {
            return ResponseEntity.ok(TargetTrajectoryFitter.fitPackage102ToCsv(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        }
    }

    /** 仅保留原 Java 模拟方法；不在正式主程序注册此 HTTP 路由。 */
    @PostMapping(value = "/simulate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> simulate(@RequestBody TargetTrajectoryFitter.SimulationRequest request) {
        try {
            return ResponseEntity.ok(TargetTrajectoryFitter.simulateToCsv(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        }
    }

    /** 仅保留原 Java 重定时方法；不在正式主程序注册此 HTTP 路由。 */
    @PostMapping(value = "/retime",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> retime(@RequestBody TargetTrajectoryFitter.RetimeTrajectoryRequest request) {
        try {
            return ResponseEntity.ok(TargetTrajectoryFitter.retimeTrajectoryToCsv(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(new ErrorResponse(TargetTrajectoryFitter.describeFailure(ex)));
        }
    }

    /** 原失败响应仅含 error，空错误转为空串；不增加文件辅助接口的 code 或 requestId。 */
    public static final class ErrorResponse {
        public final String error;

        public ErrorResponse(String error) {
            this.error = error == null ? "" : error;
        }
    }
}
