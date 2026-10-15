package com.runisys.orbit.spacevisibility.api.ballistic;

import com.runisys.orbit.spacevisibility.api.ballistic.VisibilityRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

/** 标准载荷完整配置辅助接口；不持久化、不新增旧计算响应字段。 */
@RestController
@RequestMapping("/api/v1/ballistic/payloads")
public class BallisticPayloadController {
    /** 按计算入口同一校验预览单个标准配置，保留原公里/角度单位及0/-1/null。 */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody VisibilityRequest.PayloadDef payload) {
        return payload.standardConfiguration();
    }

    /** 新辅助接口局部错误协议，不能覆盖原计算HTTP错误。 */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception exception) {
        Map<String, String> error = new LinkedHashMap<String, String>();
        error.put("code", "INVALID_ARGUMENT");
        error.put("error", exception instanceof IllegalArgumentException
                ? exception.getMessage() : "载荷JSON格式无效");
        return ResponseEntity.badRequest().body(error);
    }
}
