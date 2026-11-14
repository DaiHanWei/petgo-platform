package com.tailtopia.starterpack.dto;

import com.tailtopia.pay.domain.PayChannel;

/** {@code POST /api/v1/starter-pack/purchases} 请求体（D-22）：渠道只接受 PAWCOIN / QRIS；{@code requestId} 非空 ≤ 64。 */
public record StarterPackPurchaseRequest(Integer tier, PayChannel channel, String requestId) {
}
