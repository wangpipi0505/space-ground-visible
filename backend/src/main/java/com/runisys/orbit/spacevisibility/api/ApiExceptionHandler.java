package com.runisys.orbit.spacevisibility.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 既有空间HTTP异常转换边界：业务IllegalArgumentException返回400，其他异常返回500及requestId。
 * 原消息绑定异常也可能走500；不以弹道文件辅助接口错误协议覆盖此兼容行为。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 将参数校验和业务输入错误转换为 400 响应，错误文本可直接展示给集成界面。
     *
     * @param exception 业务层抛出的非法参数异常
     * @return 包含 error 字段的统一错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException exception) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("code", "INVALID_ARGUMENT");
        body.put("error", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 兜底处理未预期异常，避免向外暴露内部堆栈、数据库信息或敏感配置。
     *
     * <p>历史版本仅返回固定文案，导致生产现场无法把前端 500 与服务端异常对应。本处理器
     * 为每次失败生成 requestId，并以 ERROR 级别记录完整异常链。调用方只收到安全文案和
     * requestId；运维人员可使用该标识检索日志，而不会把数据库地址、SQL 或堆栈泄露到浏览器。</p>
     *
     * @param exception 未被其他处理器捕获的异常
     * @return 固定的服务处理失败响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception exception) {
        String requestId = UUID.randomUUID().toString();
        LOGGER.error("请求处理失败，requestId={}", requestId, exception);
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("code", "INTERNAL_ERROR");
        body.put("error", "服务处理失败");
        body.put("requestId", requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
