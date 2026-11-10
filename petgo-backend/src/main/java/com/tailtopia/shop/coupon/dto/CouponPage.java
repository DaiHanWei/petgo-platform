package com.tailtopia.shop.coupon.dto;

import java.util.List;

/**
 * 我的券一页（Story 2.2 AC8）：每页 20；{@code nextCursor} 末页为 null；
 * {@code availableCount} = AVAILABLE 且未过期的张数（G1「Bisa dipakai ({n})」，不含 LOCKED）。
 */
public record CouponPage(List<CouponView> items, String nextCursor, long availableCount) {
}
