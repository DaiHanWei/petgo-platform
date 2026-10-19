package com.tailtopia.growth.service;

/**
 * 「谁参与成长值」的唯一口径（V1.4.0 ① Story 1.1 · P-7）：与 {@code AccountQueryService#isActiveRegularUser} 同一判据——
 * {@code role = USER}（官方号 {@code ADMIN} 不参与）、{@code account_type = REAL}（虚拟号不参与）、未注销、未停用。
 *
 * <p>入账（{@link GrowthLedgerService}）与权益查询（{@link GrowthQueryService}）共用本谓词，不许各写一遍。
 *
 * <p>另加一条（Story 1.2 code-review）：<b>注销作业已开始</b>（{@code account_deletions.status <> 'PENDING'}）即不参与。
 * 注销编排在写 {@code deleted_at} 之前就删成长值（AD-18 定的位置），没有这一条，删完到写 {@code deleted_at} 之间
 * 还在排队的异步入账会把账户行 / 流水重新建出来、永远留下（D1/D2）。
 */
final class GrowthParticipation {

    /** 以 {@code u} 为 users 表别名的 SQL 谓词。 */
    static final String ACTIVE_REGULAR_USER = "(u.role = 'USER' AND u.account_type = 'REAL' "
            + "AND u.deleted_at IS NULL AND u.status = 'ACTIVE' "
            + "AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id AND ad.status <> 'PENDING'))";

    private GrowthParticipation() {
    }
}
