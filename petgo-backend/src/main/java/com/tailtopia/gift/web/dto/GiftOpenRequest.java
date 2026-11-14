package com.tailtopia.gift.web.dto;

import java.util.List;

/** 拆礼物请求（V1.5.0 gift-pack Story 1-4 · AC4）：{@code orderTokens}（1..50）与 {@code all = true} 二选一。 */
public record GiftOpenRequest(List<String> orderTokens, Boolean all) {
}
