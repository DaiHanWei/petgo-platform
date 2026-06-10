package com.tailtopia.profile.domain;

/**
 * 里程碑级别（FR-42，落库单字符 S/M/L）。决定庆祝动效分量（8.5）与展示分区。
 *
 * <ul>
 *   <li>{@link #S} 小：普通里程碑 → 半屏庆祝弹层 1-2s。</li>
 *   <li>{@link #M} 中：有意义里程碑 → 全屏动效 3s + 徽章解锁。</li>
 *   <li>{@link #L} 大：重大节点 → Duolingo 开宝箱 + 分享卡 + 达成推送（8.6）。</li>
 * </ul>
 */
public enum MilestoneLevel {
    S,
    M,
    L
}
