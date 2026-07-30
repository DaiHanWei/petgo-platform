package com.tailtopia.consult.dto;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.validation.constraints.NotNull;

/**
 * 限时支付请求体（Story 3.4，{@code POST /consultations/{token}/pay}）。仅携支付渠道；
 * 请求归属/金额均服务端从 request/config 取（不信客户端）。
 *
 * @param channel QRIS | PAWCOIN
 */
public record ConsultPayRequest(@NotNull PayChannel channel) {
}
