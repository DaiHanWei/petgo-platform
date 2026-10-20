package com.tailtopia.collect.dto;

/** 收藏 / 取消收藏的响应（V1.4.0 ④ Story 2.1）：{@code {"collected": true|false}}。不下发计数。 */
public record CollectResponse(boolean collected) {
}
