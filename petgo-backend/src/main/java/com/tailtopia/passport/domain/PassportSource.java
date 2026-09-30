package com.tailtopia.passport.domain;

/** 护照号来源（V1.3.2 Story 1.2 · D-6）。与 {@code ck_pet_passports_source} 同值域。 */
public enum PassportSource {
    /** 沿用该宠物 KTP 卡上的护照号。 */
    KTP,
    /** 首次进护照页 / 首次打卡时经同一计数器新发。 */
    ISSUED
}
