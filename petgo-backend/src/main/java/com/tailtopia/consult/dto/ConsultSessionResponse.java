package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ClosedReason;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.RatingPromptState;

/**
 * 咨询会话视图（Story 5.3）。
 *
 * <p>{@code timedOut}/{@code waitingElapsedSeconds} 由服务端据 {@code waitingStartedAt} 计算，
 * 供前端轮询判断是否弹「继续等待 / 先用 AI」（超时<b>不迁移状态</b>，仍 WAITING）。
 * {@code alreadyActive}=发起时已有占用态会话（前端据此显示「查看进行中 →」跳转）。
 *
 * <p>{@code rated}=本次会话是否已提交评分。前端据此<b>关闭评分入口</b>，避免对已评分会话再次评分
 * （后端 {@code submitRating} 已 409 兜底，但 closedReason 仍可能为 UNRATED——补评分只清补弹标记、
 * 不改 closedReason，故前端不能只看 closedReason 判断已评分）。
 */
public record ConsultSessionResponse(
        long id,
        String status,
        String source,
        Long vetId,
        long waitingElapsedSeconds,
        boolean timedOut,
        boolean alreadyActive,
        String imConversationId,
        String closedReason,
        String interruptedReason,
        boolean rated) {

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
                alreadyActive,
                s.getImConversationId(),
                s.getClosedReason() == null ? null : s.getClosedReason().name(),
                s.getInterruptedReason() == null ? null : s.getInterruptedReason().name(),
                isRated(s));
    }

    /**
     * 是否已评分（纯由会话终态推导，无需查评分表）：
     * RATED 关闭即已评分；UNRATED 关闭下补弹标记被清回 NONE 仅发生在「补评分成功」后
     * （超时未评为 PENDING/PROMPTED）——故 UNRATED+NONE 亦视为已评分。
     */
    private static boolean isRated(ConsultSession s) {
        ClosedReason reason = s.getClosedReason();
        if (reason == ClosedReason.RATED) {
            return true;
        }
        return reason == ClosedReason.UNRATED && s.getRatingPromptState() == RatingPromptState.NONE;
    }
}
