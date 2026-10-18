package com.tailtopia.auth.event;

import java.time.LocalDate;

/**
 * 用户当天（WIB）首次活跃领域事件（V1.4.0 ① Story 1.1 · 架构 AD-7，过去式）。
 *
 * <p>由 {@code UserActivityFilter} 在 {@code user_active_days} 当天首次写入成功时发布（请求链路 finally，
 * <b>不在事务里</b> → 消费方用 {@code @Async @EventListener}，不能用 {@code @TransactionalEventListener}）。
 * 消费方：① 每日登录成长值 {@code DAILY_LOGIN}。
 *
 * @param userId  用户 id
 * @param wibDate 活跃的 WIB 自然日
 */
public record UserDailyActiveEvent(long userId, LocalDate wibDate) {
}
