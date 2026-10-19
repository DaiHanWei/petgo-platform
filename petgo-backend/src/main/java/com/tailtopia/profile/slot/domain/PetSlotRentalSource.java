package com.tailtopia.profile.slot.domain;

/** 名额来源（V1.4.0 multi-pet Story 2.2 · AD-5）。 */
public enum PetSlotRentalSource {
    /** 用户租用（含续租时名额已过期改为新建的那一行）。 */
    RENT,
    /** 运营补发（Story 4.3）。 */
    ADMIN_GRANT
}
