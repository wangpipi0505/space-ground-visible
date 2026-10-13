package com.runisys.orbit.spacevisibility.infrastructure.files;

/** 文件辅助层可预期错误；仅新文件 Controller 将其映射为 HTTP，不影响旧计算异常语义。 */
public class ManagedFileException extends RuntimeException {
    public final int status;
    public final String code;

    public ManagedFileException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
