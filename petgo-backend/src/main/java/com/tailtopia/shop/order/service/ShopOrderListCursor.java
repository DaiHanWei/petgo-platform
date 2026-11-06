package com.tailtopia.shop.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 电商订单列表游标（v1.4.2 shop Story 6.2 AC1.3）：不透明 Base64url，内容 {@code <epochSecond>:<nano>:<id>}。
 *
 * <p>🔴 时刻带纳秒而非毫秒：{@code created_at} 是微秒精度，截到毫秒的游标会让同一毫秒内、微秒更小的订单在分页边界被跳过
 * （{@code ORDER-CENTER-CURSOR-TIE} 同类问题）。坏游标 / 空游标 / 时刻超出 [2000-01-01, 9999-12-31T23:59:59Z] → {@link #FIRST}（按首页）。
 */
public record ShopOrderListCursor(Instant beforeTs, long beforeId) {

    /** 首页哨兵：比任何真实订单都晚。 */
    /** 可接受的游标时刻下界（含）；早于它的按首页（防 PG timestamp out of range 500）。 */
    static final Instant MIN_TS = Instant.parse("2000-01-01T00:00:00Z");

    /** 可接受的游标时刻上界（含）= 首页哨兵时刻。 */
    static final Instant MAX_TS = Instant.parse("9999-12-31T23:59:59Z");

    public static final ShopOrderListCursor FIRST =
            new ShopOrderListCursor(Instant.parse("9999-12-31T23:59:59Z"), Long.MAX_VALUE);

    public boolean isFirst() {
        return this.equals(FIRST);
    }

    public static ShopOrderListCursor after(Instant createdAt, long id) {
        return new ShopOrderListCursor(createdAt, id);
    }

    public String encode() {
        String raw = beforeTs.getEpochSecond() + ":" + beforeTs.getNano() + ":" + beforeId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ShopOrderListCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return FIRST;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3) {
                return FIRST;
            }
            long sec = Long.parseLong(parts[0]);
            int nano = Integer.parseInt(parts[1]);
            long id = Long.parseLong(parts[2]);
            if (nano < 0 || nano > 999_999_999 || id <= 0) {
                return FIRST;
            }
            Instant ts = Instant.ofEpochSecond(sec, nano);
            if (ts.isBefore(MIN_TS) || ts.isAfter(MAX_TS)) {
                return FIRST;
            }
            return new ShopOrderListCursor(ts, id);
        } catch (RuntimeException e) {
            return FIRST;
        }
    }
}
