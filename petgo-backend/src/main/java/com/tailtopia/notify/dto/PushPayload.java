package com.tailtopia.notify.dto;

/**
 * 推送 payload（Story 6.1）。客户端据 {@code type + deepLinkToken} 映射 go_router location。
 * <b>绝不放顺序 id / 健康数据明文</b>。
 */
public record PushPayload(String type, String deepLinkToken, String title, String body) {
}
