package com.runisys.orbit.spacevisibility.application.space;

import java.util.Locale;

/**
 * 将轨道库、并发框架等技术异常转换为规划人员可执行的中文说明。
 *
 * <p>该类是所有“跳过组合/链路”对外原因的唯一出口：日志可以保留异常对象和堆栈，
 * 但接口和任务结果不应泄露 Orekit 的英文实现细节，也不能让使用人员根据异常类名猜测处理方式。</p>
 */
final class PlanningFailureReasonFormatter {

    private static final String ECCENTRICITY_OUT_OF_RANGE =
            "轨道传播失败：传播过程中的偏心率超出椭圆轨道有效范围，请核对星历时效和轨道参数。";
    private static final String GENERIC_ORBIT_FAILURE =
            "轨道传播失败：星历数据无法在当前任务时段完成计算，请核对星历时效和轨道参数。";

    private PlanningFailureReasonFormatter() {
    }

    /**
     * 提取异常链末端根因并映射为稳定的中文提示。
     * 对已由业务层给出的中文校验错误予以保留；未知的英文或混合错误统一降级为通用提示，
     * 防止版本升级后再次把底层库消息直接展示到任务结果页面。
     *
     * @param exception 当前捕获的异常，可为空
     * @return 面向规划人员的中文处理建议
     */
    static String toUserMessage(Throwable exception) {
        Throwable root = rootCause(exception);
        String message = root == null ? null : root.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("too large eccentricity")) {
            return ECCENTRICITY_OUT_OF_RANGE;
        }
        if (containsChinese(message)) {
            return message.trim();
        }
        return GENERIC_ORBIT_FAILURE;
    }

    /** 去除异步包装异常，确保分类依据是实际失败点而非 CompletionException。 */
    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** 业务层已提供中文说明时保留它，避免把明确的输入校验原因模糊化。 */
    private static boolean containsChinese(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '\u4e00' && character <= '\u9fff') {
                return true;
            }
        }
        return false;
    }
}
