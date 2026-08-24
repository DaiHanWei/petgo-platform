package com.tailtopia.admin.virtual.service;

import org.springframework.stereotype.Component;

/**
 * {@link PendingPublishScheduleCounter} 的当前实现：恒 0（V1.1.6 Story 12.1）。
 *
 * <p>🔴 <b>这个 0 是正确答案，不是占位</b>：内容排期能力由 Story 13.1 / 13.5 交付，
 * 在它们落地之前，系统里确实不存在任何待发布排期。
 *
 * <p>📌 <b>13.5 的接线点就是这里</b>：换成按排期表统计的实现即可，
 * 移出/禁用提示与它的全部护栏（不阻止、不自动取消、不自动转草稿）无需改动。
 * 用 {@code @Primary} 或直接替换本类，两种都行 —— 但**不要**在 13.5 里另加一处提示。
 */
@Component
public class NoContentScheduleYet implements PendingPublishScheduleCounter {

    @Override
    public long countPendingFor(long authorUserId) {
        return 0;
    }
}
