package com.tailtopia.gift.dto;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发起送礼请求（V1.4.0 gift Story 1.1 · AC5）：{@code POST /api/v1/pets/{petId}/gifts}。
 *
 * <p>数量范围、渠道（MIXED 拒绝）、{@code source} 取值在服务端按 AC5.1 的固定顺序校验，这里只挡空值。
 *
 * @param giftToken 礼物 {@code public_token}
 * @param quantity  数量 1~99
 * @param channel   PAWCOIN / QRIS（MIXED 可反序列化，由服务拒成 422）
 * @param requestId App 每次点「送出」生成的 UUID（发起幂等键的一部分）
 * @param source    入口：diary_tab / content_detail / gift_page
 * @param postId    来源内容 id（只作来源记录，可空；内容帖子在 App 内按数字 id 寻址）
 */
public record GiftSendRequest(@NotBlank String giftToken, @NotNull Integer quantity, @NotNull PayChannel channel,
        @NotBlank @Size(max = 64) String requestId, @NotBlank String source, Long postId) {
}
