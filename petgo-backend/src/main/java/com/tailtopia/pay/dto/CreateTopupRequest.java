package com.tailtopia.pay.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 充值下单请求（Story 1.3）。{@code tierId} 为档位对外 id（10k/25k/50k/100k）；{@code channel} 为
 * 支付渠道（QRIS）。服务端权威校验，非法值 → 422。
 *
 * @param tierId  档位 id
 * @param channel 支付渠道（QRIS）
 */
public record CreateTopupRequest(
        @NotBlank(message = "请选择充值档位") String tierId,
        @NotBlank(message = "请选择支付方式") String channel) {
}
