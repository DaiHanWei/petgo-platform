package com.tailtopia.profile.service;

import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * 成长时间线的**统一游标锚点**（Story 3.1 · AD-1 Rule 1）。
 *
 * <p><b>为什么需要它。</b>重构前 {@code getTimeline} 用 {@code createdAt} 作游标取数，却用
 * {@code effectiveDate}（快乐时刻取 {@code eventDate}）排序——**排序键与游标键不是同一把尺子**。
 * 用户补记一篇旧日期的日记（{@code eventDate} 旧、{@code createdAt} 新）时，该条在排序上落到旧位置、
 * 在翻页上被当作新记录，跨页时丢失或重复。本类把两者统一为同一个复合键。
 *
 * <p><b>全局序</b>：{@code eventDate} 倒序 → 同日 {@code sameDayKey}（发布/完成时刻）倒序。
 * 「取锚点之前的一批」= 严格小于本锚点的条目。
 *
 * <p><b>对外形态</b>：Base64URL 串，不暴露内部主键、不可枚举（继承架构对外标识护栏）。
 */
public record TimelineAnchor(LocalDate eventDate, Instant sameDayKey) {

    /** 首页（无游标）用的哨兵锚点：比任何真实条目都大，等价于「从最新开始」。 */
    private static final LocalDate SENTINEL_DATE = LocalDate.of(9999, 12, 31);
    private static final Instant SENTINEL_KEY = Instant.parse("9999-12-31T23:59:59Z");

    private static final String SEP = "|";

    public static TimelineAnchor newest() {
        return new TimelineAnchor(SENTINEL_DATE, SENTINEL_KEY);
    }

    /**
     * 本锚点在「按 createdAt 单键排序」的数据源上等价的上界。
     *
     * <p>健康事件没有独立的 event_date——其有效日期由 {@code createdAt} 的 UTC 日推导，
     * 因此 {@code (effDate, createdAt)} 的序与 {@code createdAt} 的序一致。要把复合锚点翻译成
     * 单键上界，需把 {@code sameDayKey} **夹紧到锚点所在自然日的区间内**：
     * <ul>
     *   <li>锚点落在当日内 → 上界 = {@code sameDayKey}</li>
     *   <li>锚点的 key 晚于当日末尾（补记旧日期的日记就是这种：eventDate 旧、createdAt 新）
     *       → 当日全部条目都在锚点之前，上界 = 次日零点</li>
     *   <li>锚点的 key 早于当日开头（防御）→ 当日无条目在锚点之前，上界 = 当日零点</li>
     * </ul>
     * 不做这层夹紧，跨类型翻页会整段错位——这正是重构前缺陷的另一面。
     */
    public Instant createdAtUpperBound() {
        Instant dayStart = eventDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant nextDayStart = eventDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (sameDayKey.isBefore(dayStart)) {
            return dayStart;
        }
        if (sameDayKey.isBefore(nextDayStart)) {
            return sameDayKey;
        }
        return nextDayStart;
    }

    /** 编码为对外游标串（Base64URL，无 padding）。 */
    public String encode() {
        String raw = eventDate + SEP + sameDayKey;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析对外游标串；{@code null}/空 → {@link #newest()}（首页）。
     *
     * @throws AppException 422 —— 非法游标（沿用重构前 {@code parseCursor} 的错误语义，不外泄内部细节）
     */
    public static TimelineAnchor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return newest();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf(SEP);
            if (sep <= 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new TimelineAnchor(
                    LocalDate.parse(raw.substring(0, sep)),
                    Instant.parse(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw AppException.validation("无效的分页游标");
        }
    }
}
