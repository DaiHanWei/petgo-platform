package com.tailtopia.entitlement.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/me/entitlements/history}（V1.5.0 gift-pack Story 4-4 · AC3 · D-28）：每页 20，
 * 排序 {@code at DESC, kindRank ASC (GRANT 0, USE 1), id DESC}；一次使用跨多批在服务端按 {@code use_key} 合并成一行。
 * 全局 non_null：与 kind 无关的字段省略。
 */
public record EntitlementHistoryPage(List<Item> items, String nextCursor) {

    /**
     * @param creditType 次数类型 wire（如 {@code KTP_HD:KTP}；USE 合并行取第一批的类型）
     * @param source     GRANT：PACK / ACTIVITY / ADMIN
     * @param packTier   GRANT 且 PACK：关联礼包档位
     * @param labelId    GRANT 且 ACTIVITY：活动名快照（印尼语）
     * @param useKind    USE：KTP_HD / TAILSONALITY / GIFT
     * @param petName    USE：「用在 {宠物}」快照
     */
    public record Item(String kind, String creditType, int quantity, Instant at, String source, Integer packTier,
            String labelId, String labelEn, String useKind, String petName) {
    }
}
