package com.tailtopia.shared.error;

import java.util.Map;

/**
 * 给 RFC 9457 ProblemDetail 追加<b>扩展成员</b>的异常契约（Story 3.7 引入）。
 *
 * <p>RFC 9457 §3.2 允许在标准字段之外携带扩展成员。少数错误光有一句 {@code detail} 不够用 ——
 * 典型如下单被库存挡住时，前端需要知道<b>是哪一件、还剩几件</b>才能让用户移除后继续（FR-95），
 * 笼统的一句话等于把排查成本转嫁给正在掏钱的人。
 *
 * <p>🔴 <b>为什么做成接口而不是让控制器自己拼 ProblemDetail：</b>
 * 控制器自拼会漏掉 {@code traceId} / {@code instance}（它们由 {@link GlobalExceptionHandler}
 * 统一注入），错误信封就此分叉 —— 而排障时最先看的就是 traceId。
 * 让业务异常实现本接口，信封仍由 handler 一处产出，扩展字段只是搭车。
 *
 * <p>🔒 扩展字段与 {@code detail} 同样会下发给客户端：<b>只放对用户安全的信息</b>，
 * 绝不放堆栈、SQL、内部 id 或任何 PII。
 */
public interface ProblemExtensions {

    /** 追加到 ProblemDetail 的扩展成员（键为 camelCase，与全平台 JSON 命名一致）。 */
    Map<String, Object> problemExtensions();
}
