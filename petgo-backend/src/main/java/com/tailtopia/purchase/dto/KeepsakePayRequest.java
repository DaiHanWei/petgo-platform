package com.tailtopia.purchase.dto;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.validation.constraints.NotNull;

/**
 * 一次性解锁发起请求（V1.3.2 Story 3.2 起，3.4 / 3.5 复用）。与 {@code HdPurchaseRequest} 同形：
 * {@code {"channel":"PAWCOIN"|"QRIS"}}；MIXED 可反序列化，由 {@code KeepsakePurchaseService.start} 拒成 422。
 */
public record KeepsakePayRequest(@NotNull PayChannel channel) {
}
