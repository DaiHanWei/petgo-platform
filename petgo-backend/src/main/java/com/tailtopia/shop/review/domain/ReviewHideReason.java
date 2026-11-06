package com.tailtopia.shop.review.domain;

import java.util.Locale;
import java.util.Optional;

/** 隐藏评价原因（V1.4.2 shop-admin Story 3.3 · AD-16 · J3）：辱骂 / 广告 / 隐私 / 其他。落库 {@code shop_reviews.hidden_reason}。 */
public enum ReviewHideReason {
    ABUSE, AD, PRIVACY, OTHER;

    /** 宽松解析（大小写 / 空白不敏感）；空或不认识 → 空（调用方据此 422，不默认成 OTHER）。 */
    public static Optional<ReviewHideReason> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
