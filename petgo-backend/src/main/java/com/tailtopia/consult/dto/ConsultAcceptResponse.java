package com.tailtopia.consult.dto;

import com.tailtopia.consult.service.ConsultRequestService;
import java.time.Instant;

/**
 * 兽医接单响应（Story 3.3，{@code POST /vet/consultations/{token}/accept}）。接单成功 → 请求进
 * {@code ACCEPTED_AWAIT_PAY}、开 1.5min 限时支付窗；兽医侧据 {@code payDeadlineAt}（服务端权威 timestamptz）
 * 渲染「等待支付」倒计时中间态（FR-53A，前端 3-6）。<b>不透传订单/会话</b>——接单阶段不建单（A-5 红线）。
 *
 * @param requestToken  不可枚举请求号
 * @param state         恒 {@code ACCEPTED_AWAIT_PAY}
 * @param payDeadlineAt 支付截止（服务端权威 = 接单时刻 + 90s，前端倒计时；不信客户端计时）
 */
public record ConsultAcceptResponse(String requestToken, String state, Instant payDeadlineAt) {

    public static ConsultAcceptResponse of(ConsultRequestService.AcceptResult r) {
        return new ConsultAcceptResponse(r.requestToken(), r.state().name(), r.payDeadlineAt());
    }
}
