package com.tailtopia.growth.domain;

/**
 * 头像框档位（V1.4.0 ① FR-144）。{@code growth_accounts.frame_choice} 为 NULL = 自动（取最高可用档）；
 * {@link #NONE} = 用户主动不戴。读写在 Story 1.2 / Epic 3。
 */
public enum FrameChoice {
    NONE,
    BASIC,
    UPGRADED,
    ANIMATED
}
