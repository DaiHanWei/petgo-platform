package com.tailtopia.profile.domain;

/**
 * 身份证卡种（V1.3.4 ktp AD-1）。落库 {@code id_cards.card_type} 为枚举名字符串；
 * 与 {@code CreateIdCardRequest.cardType} 的 {@code @Pattern} 白名单、迁移 {@code ck_id_cards_card_type} 同源
 * （{@code IdCardTypeWhitelistTest} 断言一致）。KITAS = 居住证。
 */
public enum IdCardType {
    KTP, PASSPORT, KITAS, SIM, STUDENT
}
