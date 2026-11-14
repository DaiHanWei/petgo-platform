package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.NewbieTask;

/**
 * 新手任务标记「真正新写入」之后的扩展点（V1.5.0 onboarding-recall Story 2.1）。
 *
 * <p>本 story 没有实现（{@link NewbieMarkService} 经 {@code ObjectProvider} 取，0 个即不调）；Story 2-2 提供毕业判定实现
 * （7 项全满 → 解锁账号级 Lulus Pemula）。做成接口是为了让 2-1 不依赖 2-2。
 * 调用时机：{@link NewbieMarkService#markIfAbsent} 新写入后、同一个 {@code REQUIRES_NEW} 事务内；
 * {@code NewbieWeekJob} 写 {@link NewbieTask#ONE_WEEK} 后。
 */
public interface NewbieMarkHooks {

    void afterMarked(long userId, NewbieTask task);
}
