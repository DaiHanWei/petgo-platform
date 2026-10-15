package com.tailtopia.profile.domain;

/**
 * 学生卡配色（V1.3.4 ktp AD-1）。落库 {@code id_cards.card_variant}；仅 {@link IdCardType#STUDENT} 有值，
 * 其余卡种为 NULL（{@code ck_id_cards_variant_only_student}）。存量学生卡与旧版 App 建的学生卡 = {@link #KLASIK}（D-12）。
 */
public enum StudentVariant {
    BINTANG, MALAM, CERIA, KLASIK
}
