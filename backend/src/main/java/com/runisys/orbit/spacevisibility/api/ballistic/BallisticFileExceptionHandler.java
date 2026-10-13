package com.runisys.orbit.spacevisibility.api.ballistic;

import com.runisys.orbit.spacevisibility.infrastructure.files.ManagedFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 只作用于新增文件 Controller；旧计算和空间接口继续执行各自原有错误协议。 */
@RestControllerAdvice(assignableTypes = BallisticFileController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BallisticFileExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(BallisticFileExceptionHandler.class);

    @ExceptionHandler(ManagedFileException.class)
    public ResponseEntity<Map<String, String>> managed(ManagedFileException exception) {
        return error(exception.status, exception.code, exception.getMessage(), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException exception) {
        return error(413, "FILE_TOO_LARGE", "文件或 multipart 请求超过配置字节上限", null);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception exception) {
        return error(400, "INVALID_ARGUMENT", "文件请求参数无效：" + exception.getClass().getSimpleName(), null);
    }

    /** 未预期错误仅返回追踪号和安全文案，完整日志保留在服务端。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        String requestId = UUID.randomUUID().toString();
        LOGGER.error("文件请求处理失败，requestId={}", requestId, exception);
        return error(500, "INTERNAL_ERROR", "文件服务处理失败", requestId);
    }

    private ResponseEntity<Map<String, String>> error(int status, String code, String message, String requestId) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("code", code);
        body.put("error", message);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(status).body(body);
    }
}
