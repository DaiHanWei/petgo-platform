package com.tailtopia.place.domain;

/**
 * 场所状态（V1.3.0 batch-b1 Story 1.1）。
 *
 * <p>只有两态：在架与运营下架。**没有软删列**——下架即 {@link #TAKEN_DOWN}，
 * 分享 H5 落「场所不存在」空态（AD-5 Rule 2），链接仍长期有效。
 *
 * <p>⚠️ 用户**不能编辑、也不能删除**自己标记的场所（2026-09-15 拍板），纠错只能走后台
 * AB-17A（下架 / 合并重复）—— 所以这里不需要 "作者已删" 这一态。
 */
public enum PlaceStatus {
    /** 在架，进所有列表与详情。 */
    ACTIVE,
    /** 运营下架（AB-17A）：不进列表、详情与 H5 均落统一空态。 */
    TAKEN_DOWN
}
