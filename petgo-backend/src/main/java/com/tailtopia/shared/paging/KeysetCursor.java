package com.tailtopia.shared.paging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 单源倒序列表的复合游标 {@code (createdAt, id)}。
 *
 * <p>🔴🔴 <b>只用 {@code createdAt} 做游标是错的</b>（2026-08-18，
 * {@code action_items: NOTIFY-CURSOR-TIE / ORDER-CENTER-CURSOR-TIE}）。两处都栽在同一件事上：
 * <ol>
 *   <li><b>时间不是唯一键</b>。同一事务里写多行时 Postgres 的 {@code now()} 是<b>事务开始时刻</b> ——
 *       一次批量触达、一笔订单结算拆多条流水，写出来的行时间戳<b>一模一样</b>。
 *       此时 {@code created_at < cursor} 会把整个同刻组一次跳过，
 *       用户<b>永久</b>看不到那几条。</li>
 *   <li><b>游标截断到毫秒更糟</b>。Postgres {@code timestamptz} 精度是微秒；
 *       截到毫秒会让「同刻」的判定连自己都对不上，等于把跳过窗口放大到 1 毫秒。</li>
 *   <li><b>只按 {@code created_at} 排序对同刻记录没有确定顺序</b> ——
 *       即便不撞游标，翻页时同一条也可能重复出现或消失。</li>
 * </ol>
 *
 * <p>所以：排序恒为 {@code created_at DESC, id DESC}，游标恒为该二元组，比较恒为
 * {@code createdAt < ts OR (createdAt = ts AND id < cursorId)}。
 *
 * <p>🔒 <b>对外是 base64url 不可枚举串</b>，与既有 {@code FeedCursor}（Story 3.2）同一形态 ——
 * 客户端<b>原样回传即可，不要解析、不要自己拼</b>。内部 id 只作为 tie-breaker 存在于游标里，
 * API 上<b>没有任何按 id 寻址的端点</b>，拿到它推不出别的东西。
 *
 * <p>⚠️ 用 {@code long} 而不是 {@code Instant} 存微秒是有意的：{@code Instant} 能表达纳秒，
 * 而库里存不下纳秒 —— 让类型只能表达库里存得下的精度，就不会有人写出「编码时带了纳秒、
 * 回来比不上」的代码。
 *
 * @param createdAt 该行创建时刻（<b>微秒</b>精度，UTC）
 * @param id        该行主键（tie-breaker）
 */
public record KeysetCursor(Instant createdAt, long id) {

    /** 编码为对外 token：{@code base64url("<epochMicros>:<id>")}。 */
    public String encode() {
        long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码；任何非法形态返回 {@code null}。
     *
     * <p>🔴 <b>不抛异常</b>：游标是客户端传回来的，坏值让整个列表 500 或 400 是<b>把用户锁在门外</b> ——
     * 调用方拿到 {@code null} 就当首页处理，用户至少看得到最新的那一页。
     */
    public static KeysetCursor decodeOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep < 0) {
                return null;
            }
            long micros = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            return new KeysetCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L), id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 首页哨兵：{@code (now + 60s, Long.MAX_VALUE)}。
     *
     * <p>⚠️ <b>+60s 不是随手加的余量</b>：库里的 {@code created_at} 是 Postgres 的 {@code now()}，
     * 与应用进程的 {@code Instant.now()} 不是同一个钟。取整点 {@code now()} 当上界时，
     * <b>刚写进去的那一条可能因为几毫秒的钟差被挡在列表外</b> —— 用户下完单刷新，看不到自己的单。
     */
    public static KeysetCursor firstPage() {
        return new KeysetCursor(Instant.now().plusSeconds(60), Long.MAX_VALUE);
    }
}
