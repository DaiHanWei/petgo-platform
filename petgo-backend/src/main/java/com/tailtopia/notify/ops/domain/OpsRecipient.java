package com.tailtopia.notify.ops.domain;

/**
 * 运营通知的一个收件人（V1.3.4 Story 6.1 · AC5；V1.4.2 Story 7.1 · D-16 扩昵称 / 宠物名）。
 *
 * <p>🔒 昵称 / 宠物名是 PII 级内容：只用于逐人替换文案，不得进日志。
 *
 * @param userId 收件人 {@code users.id}
 * @param locale {@code users.locale} 原值，可空；{@code "en"}（忽略大小写）→ EN 文案，其余 → ID 文案
 * @param nickname 去首尾空白后的昵称，空 → {@code null}（替换时按语言兜底，见 {@link OpsTextVariables#render}）
 * @param petName 按「默认宠物」口径选出的宠物名（有物种筛选时只在该物种内选）；没有宠物 → {@code null}
 */
public record OpsRecipient(long userId, String locale, String nickname, String petName) {

    /** 兼容构造（V1.3.4 起的调用方 / 测试）：昵称与宠物名为空。 */
    public OpsRecipient(long userId, String locale) {
        this(userId, locale, null, null);
    }
}
