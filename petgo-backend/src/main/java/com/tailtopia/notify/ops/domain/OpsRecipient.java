package com.tailtopia.notify.ops.domain;

/**
 * 运营通知的一个收件人（V1.3.4 Story 6.1 · AC5）。
 *
 * @param userId 收件人 {@code users.id}
 * @param locale {@code users.locale} 原值，可空；{@code "en"}（忽略大小写）→ EN 文案，其余 → ID 文案
 */
public record OpsRecipient(long userId, String locale) {
}
