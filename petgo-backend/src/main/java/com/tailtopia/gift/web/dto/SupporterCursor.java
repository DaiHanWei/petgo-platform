package com.tailtopia.gift.web.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 粉丝榜游标（V1.4.0 gift Story 2.1 · AC2.7）。排序键 {@code total_amount DESC, last_sent_at DESC, sender_id DESC}
 * 与 {@code KeysetCursor} 不同，故另建；编码 / 解码照 {@code shared.paging.KeysetCursor} 的写法
 * （base64url 无填充，时刻以<b>微秒</b>存，与库里 {@code timestamptz} 精度一致）。
 *
 * <p>⚠️ 游标内含 {@code senderId}（tie-breaker）：与既有 PawCoin 流水游标内含流水 id 是同一档取舍 ——
 * 对客户端是<b>不透明串</b>、原样回传，API 上没有任何按 senderId 寻址的端点，不作为标识使用。
 *
 * @param totalAmount 上一页最后一行的累计金额
 * @param lastSentAt  上一页最后一行的最近送达时刻
 * @param senderId    上一页最后一行的送礼人（tie-breaker）
 * @param nextRank    下一页第一行的名次（名次跨页连续）
 */
public record SupporterCursor(long totalAmount, Instant lastSentAt, long senderId, int nextRank) {

    /** 编码为对外串：{@code base64url("<totalAmount>:<epochMicros>:<senderId>:<nextRank>")}。 */
    public String encode() {
        long micros = lastSentAt.getEpochSecond() * 1_000_000L + lastSentAt.getNano() / 1_000L;
        String raw = totalAmount + ":" + micros + ":" + senderId + ":" + nextRank;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码；任何非法形态（含空串、负数、名次 &lt; 1）返回 {@code null}，由调用方决定如何处理（礼物页 → 400）。 */
    public static SupporterCursor decodeOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 4) {
                return null;
            }
            long total = Long.parseLong(parts[0]);
            long micros = Long.parseLong(parts[1]);
            long sender = Long.parseLong(parts[2]);
            int rank = Integer.parseInt(parts[3]);
            if (total < 0 || sender <= 0 || rank < 1) {
                return null;
            }
            return new SupporterCursor(total, Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L), sender, rank);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
