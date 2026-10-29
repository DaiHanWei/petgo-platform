package com.tailtopia.ops.audience;

/**
 * 活跃时间档（V1.4.1 运营推送人群，2026-10-29）：按 {@code users.last_active_at}（每个 WIB 自然日首次打开 App 刷新）。
 *
 * <p>多选为「或」。{@link #WITHIN_7D} 是 {@link #WITHIN_15D} 的子集；{@link #OTHER} = 15 天以上没打开过
 * （含从没打开过、{@code last_active_at} 为空）。时间点按<b>实际发送 / 判定的那一刻</b>往前推 N×24 小时。
 */
public enum ActivityBand {
    /** 7 天内打开过 App。 */
    WITHIN_7D,
    /** 15 天内打开过 App。 */
    WITHIN_15D,
    /** 15 天以上没打开过（含从没打开过）。 */
    OTHER
}
