package com.tailtopia.profile.dto;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.validation.constraints.NotNull;

/**
 * 身份证高清图购买请求（Story 6.3，POST /pet-profiles/me/id-card/hd-download）。
 * [channel] = 支付渠道（QRIS 现金 / PAWCOIN 站内余额；DANA 已取消）。
 */
public record HdPurchaseRequest(@NotNull PayChannel channel) {
}
