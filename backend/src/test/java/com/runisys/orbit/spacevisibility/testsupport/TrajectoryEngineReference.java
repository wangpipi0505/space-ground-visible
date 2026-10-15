package com.runisys.orbit.spacevisibility.testsupport;

import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试专用的直接拟合、模拟和重定时适配器，用于与 HTTP 编排结果对照。
 * 本类不注册 Spring 组件、不进入产品交付；黄金和冻结原版提供独立参考。
 */


public class TrajectoryEngineReference {

    /** 旧 Java 健康调用仍返回原纯文本 OK。 */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    /** 旧 Java 拟合调用按原 outputDir 语义直接委托数值主体，默认仍为当前工作目录。 */
    @PostMapping(value = "/fit/package102",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> fitPackage102(@RequestBody TargetTrajectoryFitter.FitInput request) {
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
    public ResponseEntity<?> simulate(@RequestBody TargetTrajectoryFitter.SimulationInput request) {
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
    public ResponseEntity<?> retime(@RequestBody TargetTrajectoryFitter.RetimeInput request) {
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
