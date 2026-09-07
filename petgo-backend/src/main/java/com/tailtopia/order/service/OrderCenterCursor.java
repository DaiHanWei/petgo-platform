package com.tailtopia.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 订单中心游标 {@code (createdAt, sourceRank, id)}。
 *
 * <p>🔴🔴 <b>为什么不能直接用 {@code shared.paging.KeysetCursor}</b>：那个是
 * {@code (createdAt, id)}，只对<b>单表</b>成立。订单中心跨 4 个源归并，
 * 4 张表的 {@code id} 各排各的，拼在一起不构成任何顺序 —— 所以中间要插一位「源的先后位」。
 *
 * <p><b>它修的是什么</b>（{@code action_items: ORDER-CENTER-CURSOR-TIE}）：
 * 原游标是 {@code createdAt} 截断到毫秒、查询用严格 {@code <}。于是
 * <ul>
 *   <li>末条是 {@code 10:00:00.123456} → 游标写成 {@code .123} → 下一页要
 *       {@code created_at < .123} —— <b>落在 [.123000, .123456] 之间的订单被永久跳过</b>；</li>
 *   <li>同刻订单（同一事务写多行、或跨源同刻）整组被跳过。</li>
 * </ul>
 *
 * <p>🔒 编码为 base64url 不可枚举串，形态与既有 {@code FeedCursor}（Story 3.2）一致 ——
 * 客户端<b>原样回传，不要解析、不要自己拼</b>。
 *
 * @param createdAt  末条创建时刻（<b>微秒</b>精度 —— 截到毫秒就等于没修）
 * @param sourceRank 末条来自第几个源（见 {@code OrderCenterService} 的 {@code RANK_*}）
 * @param id         末条在该源里的主键（tie-breaker）
 */
record OrderCenterCursor(Instant createdAt, int sourceRank, long id) {

    String encode() {
        long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
        String raw = micros + ":" + sourceRank + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码；非法形态返回 {@code null}（由调用方决定按首页还是报错处理）。 */
    static OrderCenterCursor decodeOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 3) {
                return null;
            }
            long micros = Long.parseLong(parts[0]);
            return new OrderCenterCursor(
                    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                            Math.floorMod(micros, 1_000_000L) * 1_000L),
                    Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
