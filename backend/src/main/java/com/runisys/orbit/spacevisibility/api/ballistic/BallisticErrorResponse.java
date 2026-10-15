package com.runisys.orbit.spacevisibility.api.ballistic;

/** 拟合、模拟和重定时失败响应；HTTP 合同仅含 error，空错误仍输出空字符串。 */
public final class BallisticErrorResponse {
    /** 面向调用方的失败原因，沿用计算器的错误描述和顺序。 */
    public final String error;

    public BallisticErrorResponse(String error) {
        this.error = error == null ? "" : error;
    }
}
