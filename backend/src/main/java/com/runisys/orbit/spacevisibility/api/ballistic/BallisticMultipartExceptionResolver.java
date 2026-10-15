package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Servlet 解析 multipart 发生在 Controller 匹配之前，因此限定 Controller 的 Advice 此时不可用。
 * 本处理器只拦截文件上传精确路径的 multipart 异常，其它路径返回 null，继续原处理链。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BallisticMultipartExceptionResolver implements HandlerExceptionResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(BallisticMultipartExceptionResolver.class);
    private final ObjectMapper mapper;

    public BallisticMultipartExceptionResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception exception) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!(exception instanceof MultipartException) || !"/api/v1/ballistic/files".equals(path)) {
            return null;
        }
        boolean tooLarge = exception instanceof MaxUploadSizeExceededException;
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("code", tooLarge ? "FILE_TOO_LARGE" : "INVALID_ARGUMENT");
        body.put("error", tooLarge ? "文件或 multipart 请求超过配置字节上限" : "multipart 文件请求格式无效");
        response.setStatus(tooLarge ? 413 : 400);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            mapper.writeValue(response.getOutputStream(), body);
        } catch (IOException ex) {
            LOGGER.warn("写入 multipart 错误响应失败，客户端可能已断开", ex);
        }
        return new ModelAndView();
    }
}
