package com.tailtopia.gift.web.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 逐笔礼物列表游标（V1.5.0 gift-pack Story 1-4 · D-12）。排序键：主人视角
 * {@code (opened_at IS NULL) DESC, delivered_at DESC, id DESC}，访客视角 {@code delivered_at DESC, id DESC}
 * （访客 {@code unopened} 恒 0）。编码照 {@link SupporterCursor}：base64url 无填充，时刻按<b>微秒</b>存。对客户端不透明。
 *
 * @param unopened    上一页最后一行是否未拆（1 / 0）
 * @param deliveredAt 上一页最后一行的送达时刻
 * @param id          上一页最后一行的订单 id（tie-breaker，不作为标识对外使用）
 */
public record GiftOrderCursor(int unopened, Instant deliveredAt, long id) {

    public String encode() {
        long micros = deliveredAt.getEpochSecond() * 1_000_000L + deliveredAt.getNano() / 1_000L;
        String raw = unopened + ":" + micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码；任何非法形态返回 {@code null}（调用方 → 400 「游标无效」）。 */
    public static GiftOrderCursor decodeOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3) {
                return null;
            }
            int unopened = Integer.parseInt(parts[0]);
            long micros = Long.parseLong(parts[1]);
            long id = Long.parseLong(parts[2]);
            if ((unopened != 0 && unopened != 1) || id <= 0) {
                return null;
            }
            return new GiftOrderCursor(unopened, Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L), id);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
