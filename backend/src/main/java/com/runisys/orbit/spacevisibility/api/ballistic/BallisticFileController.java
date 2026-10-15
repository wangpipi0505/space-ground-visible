package com.runisys.orbit.spacevisibility.api.ballistic;

import com.runisys.orbit.spacevisibility.application.ballistic.BallisticFileService;
import com.runisys.orbit.spacevisibility.application.ballistic.contract.BallisticFileModels;
import com.runisys.orbit.spacevisibility.infrastructure.ballistic.files.BallisticFileStorage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 正式 B/S 文件辅助入口；不承担窗口、拟合或其它后续事项的 HTTP 接入。 */
@RestController
@RequestMapping("/api/v1/ballistic/files")
public class BallisticFileController {
    private final BallisticFileStorage storage;
    private final BallisticFileService service;

    public BallisticFileController(BallisticFileStorage storage, BallisticFileService service) {
        this.storage = storage;
        this.service = service;
    }

    /** 上传 multipart 二进制，成功后返回可直接供旧计算路径字段使用的受管理引用。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BallisticFileModels.FileReference upload(@RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String purpose) throws IOException {
        try (InputStream input = file.getInputStream()) {
            return storage.upload(input, file.getOriginalFilename(), file.getContentType(), purpose);
        }
    }

    /** 登记管理结果根中的现有文件，显式旧 outputDir 越出管理根的文件不对浏览器开放。 */
    @PostMapping("/register-result")
    @ResponseStatus(HttpStatus.CREATED)
    public BallisticFileModels.FileReference register(@RequestBody BallisticFileModels.RegisterResultRequest request)
            throws IOException {
        return storage.registerResult(request.csvPath, request.taskName, request.targetId);
    }

    /** 分页原数据预览，不向旧算法响应添加包装字段。 */
    @GetMapping("/{fileId}/preview")
    public BallisticFileModels.Preview preview(@PathVariable String fileId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int limit) throws IOException {
        return service.preview(fileId, page, limit);
    }

    /**
     * 租约覆盖完整同步流传输，客户端中断也会释放。ASCII fallback 与 RFC 5987 filename* 同时提供，
     * Unicode 原名以 UTF-8 编码保留；原名已在登记处拒绝控制字符，fallback 另去除引号/反斜杠。
     */
    @GetMapping("/{fileId}/download")
    public void download(@PathVariable String fileId, HttpServletResponse response) throws IOException {
        try (BallisticFileStorage.Lease lease = storage.acquire(fileId);
             InputStream input = Files.newInputStream(lease.path)) {
            String name = lease.reference.originalName;
            String fallback = name.replaceAll("[^\\x20-\\x7E]|[\\\"\\\\]", "_");
            String encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
            response.setContentType(lease.reference.contentType);
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fallback
                    + "\"; filename*=UTF-8''" + encoded);
            response.setHeader("X-Content-Type-Options", "nosniff");
            long remaining = lease.reference.sizeBytes;
            response.setContentLengthLong(remaining);
            byte[] buffer = new byte[8192];
            int count;
            // 登记结果仍可能被外部写入者修改；最多发送租约获取时已校验的长度，不能在流期间突破限额。
            while (remaining > 0 && (count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                response.getOutputStream().write(buffer, 0, count);
                remaining -= count;
            }
        }
    }
}
