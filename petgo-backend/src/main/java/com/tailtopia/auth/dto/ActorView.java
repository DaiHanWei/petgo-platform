package com.tailtopia.auth.dto;

/**
 * 通知触发人投影（V1.3.4 follow · Story 3.3 · AC3 / AC7）：昵称 + 对外 token + 是否对外有效，<b>一次</b> {@code findAllById} 取齐。
 *
 * <p>{@code active = 未注销 && status = ACTIVE}（D-8：封号与注销同等处理）。不 active 时 {@code nickname / publicToken} 为 null。
 * 昵称规则同 {@code AuthorView}：{@code nickname ?: displayName}。
 */
public record ActorView(long userId, String nickname, String publicToken, boolean active) {
}
