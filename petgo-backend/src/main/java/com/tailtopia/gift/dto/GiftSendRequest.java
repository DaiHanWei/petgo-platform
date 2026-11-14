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
 * @param message   V1.5.0 gift-pack Story 1-3：祝福语（可空；{@code @Size} 只防超大包体，真正上限 500 码点在服务里算）
 */
public record GiftSendRequest(@NotBlank String giftToken, @NotNull Integer quantity, @NotNull PayChannel channel,
        @NotBlank @Size(max = 64) String requestId, @NotBlank String source, Long postId,
        @Size(max = 2000) String message) {

    /** 不带祝福语的旧形状（≤ 1.4.x App / 既有调用点）。 */
    public GiftSendRequest(String giftToken, Integer quantity, PayChannel channel, String requestId, String source,
            Long postId) {
        this(giftToken, quantity, channel, requestId, source, postId, null);
    }
}
