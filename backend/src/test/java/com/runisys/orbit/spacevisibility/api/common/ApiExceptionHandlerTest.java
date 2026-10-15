package com.runisys.orbit.spacevisibility.api.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    /**
     * 未预期异常不能向调用方泄露堆栈，但必须提供稳定错误码与请求标识，
     * 使部署人员能够从服务端日志反查 Word 中的“服务处理失败”根因。
     */
    @Test
    void returnsSafeErrorWithTraceableRequestIdForUnexpectedFailure() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Map<String, String>> response = handler.handleException(new IllegalStateException("database detail"));

        assertThat(response.getStatusCodeValue()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", "服务处理失败")
                .containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody().get("requestId")).isNotBlank();
        assertThat(response.getBody().toString()).doesNotContain("database detail");
    }
}
