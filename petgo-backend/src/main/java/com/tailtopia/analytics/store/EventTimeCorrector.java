package com.tailtopia.analytics.store;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * 明细层时间校正与可疑判定（analytics-pipeline Story 1.6 AC5 / AC6，架构 D-3 / D-4 / FR-9）。无状态、无 IO、无 Spring。
 *
 * <ul>
 *   <li>{@code eventTime = clientTs + (receivedAt − sentAt)}：用「发送 → 收到」的差抵消设备时钟偏差。{@code sentAt} 缺失按
 *       {@code receivedAt} 处理（偏移为 0），不抛。</li>
 *   <li>可疑：{@code eventTime} 晚于 {@code receivedAt} 超过 {@link #FUTURE_TOLERANCE}，或早于 {@code receivedAt} 超过
 *       {@link #PAST_LIMIT}（与 App 本地队列保留时限 A2 联动，不做配置）。</li>
 *   <li>{@code eventDate}：正常取 {@code eventTime} 的 WIB 日期；可疑、或该日期不在已建明细分区内时，取 {@code receivedAt} 的
 *       WIB 日期（{@code eventTime} 本身原样保留）。</li>
 * </ul>
 *
 * <p>🔴 <b>WIB 日期全模块只在 {@link #wibDate} 一处换算</b>（架构实现规范 §4）。别处要 WIB 日期一律调它。
 */
public final class EventTimeCorrector {

    /** 印尼西部时间。 */
    public static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    /** 「晚于收到时间」的容差：超过即可疑。 */
    public static final Duration FUTURE_TOLERANCE = Duration.ofSeconds(5);
    /** 「早于收到时间」的上限：超过即可疑（= App 本地队列保留 7 天，A2）。 */
    public static final Duration PAST_LIMIT = Duration.ofDays(7);

    /** 校正结果。 */
    public record Corrected(Instant eventTime, LocalDate eventDate, boolean suspicious) {
    }

    private EventTimeCorrector() {
    }

    /**
     * @param clientTs         客户端声明的发生时间（非空）
     * @param sentAt           客户端声明的发送时间；可空（按 {@code receivedAt}）
     * @param receivedAt       服务端收到时间（非空）
     * @param builtPartitions  当前已建的明细分区日期（WIB）
     */
    public static Corrected correct(Instant clientTs, Instant sentAt, Instant receivedAt,
            Set<LocalDate> builtPartitions) {
        Instant sent = sentAt == null ? receivedAt : sentAt;
        Instant eventTime = clientTs.plus(Duration.between(sent, receivedAt));
        boolean suspicious = eventTime.isAfter(receivedAt.plus(FUTURE_TOLERANCE))
                || eventTime.isBefore(receivedAt.minus(PAST_LIMIT));
        LocalDate byEvent = wibDate(eventTime);
        LocalDate eventDate = suspicious || !builtPartitions.contains(byEvent) ? wibDate(receivedAt) : byEvent;
        return new Corrected(eventTime, eventDate, suspicious);
    }

    /** 某一时刻的 WIB 日期。全模块唯一的 WIB 换算点。 */
    public static LocalDate wibDate(Instant t) {
        return LocalDate.ofInstant(t, WIB);
    }
}
