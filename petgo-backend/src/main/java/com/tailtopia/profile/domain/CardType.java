package com.tailtopia.profile.domain;

/**
 * 身份证卡种（Story 6-8，bug 20260721-330）。同一套建卡快照 + HD 购买机制，扩出不同可视卡面。
 *
 * <ul>
 *   <li>{@link #KTP}：宠物居民身份证（默认，存量卡）。</li>
 *   <li>{@link #PASSPORT}：宠物护照（Pet Passport）。</li>
 *   <li>{@link #STUDENT}：学生卡（TailTopia Academy Student Card）。</li>
 * </ul>
 *
 * <p>落库 UPPER_SNAKE 字符串。卡面展示字段全部由前端从快照 + serial + createdAt 派生（同 KTP 范式），
 * 后端仅透传卡种。
 */
public enum CardType {
    KTP,
    PASSPORT,
    STUDENT;

    /** 宽松解析：null/空/非法 → KTP（默认卡种，保建卡入参可选）。 */
    public static CardType fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return KTP;
        }
        try {
            return CardType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return KTP;
        }
    }
}
