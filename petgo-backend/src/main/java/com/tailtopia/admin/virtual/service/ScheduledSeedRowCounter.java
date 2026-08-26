package com.tailtopia.admin.virtual.service;

import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import org.springframework.stereotype.Component;

/**
 * {@link PendingPublishScheduleCounter} 的真实实现（V1.1.6 Story 13.1 接上）。
 *
 * <p>📌 <b>这就是 Story 12.1 里写明的那个接线点</b>。12-1 交付移出/禁用前的提示机制时，
 * 内容排期这个概念还不存在，所以当时的实现 {@code NoContentScheduleYet} 恒返回 0 ——
 * 而那个 0 当时是**正确答案**（系统里确实没有排期）。
 *
 * <p>13-1 建好 {@code seed_batch_rows} 之后，那个 0 就从"正确答案"变成了
 * <b>一个等着变错的硬编码</b>：13-4/13-5 一旦开始产生 SCHEDULED 行，
 * 运营移出账号时会看到"该账号当前有 0 条待发布排期"，然后那些内容在之后几天里陆续失败。
 * 所以接线放在**建表这一条**，不等到 13-5。
 *
 * <p>🛡 提示的全部护栏（不阻止移出 / 排期不自动取消 / 不自动转草稿）都在 12-1，
 * 本类**只负责数数** —— 不要在这里顺手做任何处置。
 */
@Component
public class ScheduledSeedRowCounter implements PendingPublishScheduleCounter {

    private final SeedBatchRowRepository rows;

    public ScheduledSeedRowCounter(SeedBatchRowRepository rows) {
        this.rows = rows;
    }

    /**
     * 🔴 <b>只数 {@code SCHEDULED}</b>：草稿与待确认还没被安排出去，
     * 运营移出账号时它们不会"到点失败" —— 混进来会把提示的数字说大，
     * 而一个说大的数字会让运营对这个提示失去信任。
     */
    @Override
    public long countPendingFor(long authorUserId) {
        return rows.countByAuthorUserIdAndStatus(authorUserId, SeedBatchRowStatus.SCHEDULED);
    }
}
