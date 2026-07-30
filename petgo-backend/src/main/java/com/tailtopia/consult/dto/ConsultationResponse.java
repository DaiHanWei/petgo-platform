package com.tailtopia.consult.dto;

import com.tailtopia.consult.service.ConsultRequestService;
import java.time.Instant;

/**
 * 免费入队响应（Story 3.2，{@code POST /consultations}）。{@code alreadyActive}=true 表示命中已有 live 请求
 * （前端跳「进行中」）。不透传订单/扣费信息（本阶段未扣费不建单，A-5）。
 *
 * @param requestToken    不可枚举请求号
 * @param state           QUEUEING | ACCEPTED_AWAIT_PAY
 * @param queueDeadlineAt 入队截止（服务端权威，前端倒计时）
 * @param alreadyActive   命中已有 live 请求
 */
public record ConsultationResponse(String requestToken, String state, Instant queueDeadlineAt,
        boolean alreadyActive) {

    public static ConsultationResponse of(ConsultRequestService.CreateResult r) {
        return new ConsultationResponse(
                r.request().getRequestToken(),
                r.request().getState().name(),
                r.request().getQueueDeadlineAt(),
                r.alreadyActive());
    }
}
