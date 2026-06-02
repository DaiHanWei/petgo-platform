package com.petgo.consult.domain;

import java.util.Set;

/**
 * 兽医咨询会话状态（Story 5.3 建，落库 varchar + UPPER_SNAKE）。
 *
 * <p>全机：{@code WAITING →(接单 5.5) IN_PROGRESS →(结束 5.6) PENDING_CLOSE →(评分/超时 5.6) CLOSED}；
 * 旁路 {@code WAITING →(取消，本故事) CANCELLED}、{@code IN_PROGRESS →(封禁 5.7) INTERRUPTED}。
 * 本故事只迁移 {@code →WAITING} 与 {@code WAITING→CANCELLED}。
 */
public enum SessionStatus {
    WAITING,
    IN_PROGRESS,
    PENDING_CLOSE,
    CLOSED,
    INTERRUPTED,
    CANCELLED;

    /** 「占用中」三态：任一存在即视为该用户已有进行中咨询（FR-4B「同时仅 1 个」）。 */
    public static final Set<SessionStatus> ACTIVE = Set.of(WAITING, IN_PROGRESS, PENDING_CLOSE);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
