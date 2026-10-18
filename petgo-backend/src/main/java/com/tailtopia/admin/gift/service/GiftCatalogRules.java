package com.tailtopia.admin.gift.service;

import com.tailtopia.admin.config.service.AdminConfigService;

/**
 * 后台礼物目录的校验边界（V1.4.0 gift Story 3.1 · AC4 / AC8，AD-1 / AD-4）。
 *
 * <p>下限与库级 CHECK 同值（{@code ck_gift_catalog_price} / {@code ck_pricing_gift_qris_min} 均为 ≥100）——
 * 服务层先拦成人话 422，别让 CHECK 以 500 露出来。上限复用 {@link AdminConfigService#MAX_TIER_AMOUNT}，
 * 防多打几个 0 让 QRIS 单笔超限。
 */
public final class GiftCatalogRules {

    /** 礼物单价下限（IDR，= DB CHECK）。 */
    public static final long MIN_PRICE = 100L;
    /** 礼物单价上限（IDR，同充值档位上限）。 */
    public static final long MAX_PRICE = AdminConfigService.MAX_TIER_AMOUNT;

    /** 名称上限（{@code VARCHAR(40)}）。 */
    public static final int NAME_MAX = 40;

    /** 排序值范围。 */
    public static final int SORT_ORDER_MAX = 9999;

    /**
     * 专属礼物最低等级的上限（PRD 等级曲线 Lv1–20）。
     *
     * <p>⚠️ 本分支没有 ① 的等级表：先用常量兜底。4.1 对齐时改读 ① 等级表最高级。
     */
    public static final int MAX_LEVEL_FALLBACK = 20;

    /** 送礼 QRIS 最低金额下限（= DB CHECK，AD-4）。 */
    public static final long MIN_QRIS_AMOUNT = 100L;
    /** 送礼 QRIS 最低金额上限（同礼物单价上限）。 */
    public static final long MAX_QRIS_AMOUNT = AdminConfigService.MAX_TIER_AMOUNT;

    private GiftCatalogRules() {
    }
}
