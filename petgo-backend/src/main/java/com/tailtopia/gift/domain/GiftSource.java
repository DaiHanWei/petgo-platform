package com.tailtopia.gift.domain;

import java.util.Optional;

/**
 * 送礼入口（V1.4.0 gift Story 1.1 · AC1.3）。<b>落库与请求体都是小写</b>（PRD §4 埋点口径），
 * 经 {@link GiftSourceConverter} 与枚举互转。
 */
public enum GiftSource {
    DIARY_TAB("diary_tab"),
    CONTENT_DETAIL("content_detail"),
    GIFT_PAGE("gift_page");

    private final String wire;

    GiftSource(String wire) {
        this.wire = wire;
    }

    /** 落库 / 请求体里的小写值。 */
    public String wire() {
        return wire;
    }

    /** 按小写值解析；不在三值内 → 空（调用方抛 422 validation）。 */
    public static Optional<GiftSource> fromWire(String value) {
        for (GiftSource s : values()) {
            if (s.wire.equals(value)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
