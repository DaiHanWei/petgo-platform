package com.petgo.consult.dto;

import com.petgo.consult.domain.ConsultSession;

/**
 * 咨询会话视图（Story 5.3）。
 *
 * <p>{@code timedOut}/{@code waitingElapsedSeconds} 由服务端据 {@code waitingStartedAt} 计算，
 * 供前端轮询判断是否弹「继续等待 / 先用 AI」（超时<b>不迁移状态</b>，仍 WAITING）。
 * {@code alreadyActive}=发起时已有占用态会话（前端据此显示「查看进行中 →」跳转）。
 */
public record ConsultSessionResponse(
        long id,
        String status,
        String source,
        Long vetId,
        long waitingElapsedSeconds,
        boolean timedOut,
        boolean alreadyActive) {

    public static ConsultSessionResponse of(ConsultSession s, long timeoutSeconds, boolean alreadyActive) {
        long elapsed = s.getWaitingStartedAt() == null
                ? 0L
                : Math.max(0L, (System.currentTimeMillis() - s.getWaitingStartedAt().toEpochMilli()) / 1000L);
        return new ConsultSessionResponse(
                s.getId(),
                s.getStatus().name(),
                s.getSource().name(),
                s.getVetId(),
                elapsed,
                s.isTimedOut(timeoutSeconds),
                alreadyActive);
    }
}
