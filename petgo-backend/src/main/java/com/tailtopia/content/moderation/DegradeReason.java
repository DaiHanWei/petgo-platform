package com.tailtopia.content.moderation;

/**
 * fail-closed 降级原因（方案 §4.3）。三方无法给出明确结论时统一映射，绝不放行。
 *
 * <p>护栏：仅作枚举串落决策日志（{@code degradeReason}），<b>不携带原文 / 图 URL / AK / 上游堆栈</b>。
 */
public enum DegradeReason {
    /** 单次调用超时（文本 ≤1s / 图像 ≤2s SLA）。 */
    TIMEOUT,
    /** 4xx（含鉴权 / 参数错误）。 */
    HTTP_4XX,
    /** 5xx（三方服务端错误）。 */
    HTTP_5XX,
    /** 配额耗尽 / 限流。 */
    QUOTA,
    /** 进程内熔断打开，窗口期内短路（不打三方）。 */
    CIRCUIT_OPEN
}
