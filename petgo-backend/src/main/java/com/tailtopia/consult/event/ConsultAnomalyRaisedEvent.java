package com.tailtopia.consult.event;

import java.time.Instant;

/**
 * 问诊异常事件（Story 2.5，AB-2E）。封禁兽医强制中断进行中会话时，每个被中断会话发布一条，
 * 供 Epic 5 {@code ConsultAnomalyService} 订阅落运营工单（本事件不落表，工单表在 Epic 5）。
 *
 * <p>与 {@link ConsultInterruptedEvent} 并存、各司其职：Interrupted→推送/历史（Epic 6）；AnomalyRaised→运营工单（Epic 5）。
 *
 * @param anomalyType 异常类型（UPPER_SNAKE，如 {@code VET_BANNED}）
 */
public record ConsultAnomalyRaisedEvent(long sessionId, long userId, long vetId,
        Instant startedAt, Instant endedAt, String anomalyType) {
}
