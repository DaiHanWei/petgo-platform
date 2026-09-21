package com.tailtopia.place.domain;

/**
 * 场所状态（V1.3.0 batch-b1 Story 1.1 → 2026-09-18 场所表对齐）。
 *
 * <p>值域归后台（{@code ck_places_status}），与 {@code com.tailtopia.admin.places.domain.PlaceStatus} 同三值。
 * 🔴 App 侧必须认全三个值：读到一行 App 不认识的状态，枚举解析会直接抛，整个列表 / 详情 500。
 *
 * <p>⚠️ 用户**不能编辑、也不能删除**自己标记的场所（2026-09-15 拍板），纠错只能走后台
 * AB-17A（下架 / 合并重复）。
 */
public enum PlaceStatus {
    /** 在架，进所有列表与详情。 */
    ACTIVE,
    /** 运营下架（AB-17A）：不进列表，详情与 H5 均落统一空态。（原 App 侧叫 TAKEN_DOWN。） */
    DELISTED,
    /** 运营合并进另一个场所：{@code merged_into_id} 指向保留场所，直链与分享页转过去（对齐决策 D4）。 */
    MERGED
}
