package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ConsultRequest;
import java.time.Instant;

/**
 * 请求状态轮询响应（Story 3.5，{@code GET /consultations/{token}}）。前端下单三屏据此驱动
 * 待接单→待支付跃迁 + 服务端权威倒计时。请求已消失（超时删/转单删）→ 端点 404，不在本 DTO 表达。
 *
 * @param state           QUEUEING | ACCEPTED_AWAIT_PAY（服务端权威）
 * @param queueDeadlineAt 入队 1min 截止（QUEUEING 倒计时；ACCEPTED 后可为历史值）
 * @param payDeadlineAt   支付 1.5min 截止（ACCEPTED_AWAIT_PAY 倒计时，服务端权威）
 * @param pausedAt        跳充值暂停锚（A-4；非空=暂停中，前端暂停显示）
 */
public record ConsultRequestStatusResponse(String state, Instant queueDeadlineAt,
        Instant payDeadlineAt, Instant pausedAt) {

    public static ConsultRequestStatusResponse of(ConsultRequest r) {
        return new ConsultRequestStatusResponse(
                r.getState().name(), r.getQueueDeadlineAt(), r.getPayDeadlineAt(), r.getPausedAt());
    }
}
