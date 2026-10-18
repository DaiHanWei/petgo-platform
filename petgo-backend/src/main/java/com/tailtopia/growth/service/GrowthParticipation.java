package com.tailtopia.growth.service;

/**
 * 「谁参与成长值」的唯一口径（V1.4.0 ① Story 1.1 · P-7）：与 {@code AccountQueryService#isActiveRegularUser} 同一判据——
 * {@code role = USER}（官方号 {@code ADMIN} 不参与）、{@code account_type = REAL}（虚拟号不参与）、未注销、未停用。
 *
 * <p>入账（{@link GrowthLedgerService}）与权益查询（{@link GrowthQueryService}）共用本谓词，不许各写一遍。
 */
final class GrowthParticipation {

    /** 以 {@code u} 为 users 表别名的 SQL 谓词。 */
    static final String ACTIVE_REGULAR_USER = "(u.role = 'USER' AND u.account_type = 'REAL' "
            + "AND u.deleted_at IS NULL AND u.status = 'ACTIVE')";

    private GrowthParticipation() {
    }
}
