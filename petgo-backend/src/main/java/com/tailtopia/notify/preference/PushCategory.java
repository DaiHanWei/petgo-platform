package com.tailtopia.notify.preference;

import com.tailtopia.notify.domain.NotificationType;

/**
 * 推送分类（V1.4.0 ④ Story 1.1 · 架构增量 AD-1 · PRD §3.1 分类对照表）。
 *
 * <p><b>声明顺序即 {@code GET /api/v1/me/push-settings} 的返回顺序</b>，不要调换。
 * 只有 {@link #ORDER_ACCOUNT}（订单与账号）不可关。本枚举不承载显示文案 —— 分类名称与说明归 App（不下发显示串）。
 *
 * <p>🔴 <b>新增通知类型必须在 {@link #of} 的 switch 里指定分类</b>（PRD §3.1「以后新增通知类型，必须在本表里指定分类」）。
 * switch 是穷尽的、不写 {@code default}：给 {@link NotificationType} 加值而不在这里归类，<b>编译即失败</b>；
 * {@code PushCategoryTest} 另逐值比对期望表，防「编译过了但归错类」。
 * 新增<b>可关</b>分类还要新起迁移重建 {@code ck_user_push_mutes_category}（DROP + ADD 重列全集）。
 */
public enum PushCategory {

    LIKE(true),
    COMMENT(true),
    MENTION(true),
    FOLLOWER(true),
    /** 追更宠物（V1.4.2 social-topic Story 1.2）：我追更的宠物发了新 Diary。紧跟新粉丝之后（App A13 展示顺序）。 */
    PET_DIARY(true),
    GIFT(true),
    PET_REMINDER(true),
    ACHIEVEMENT(true),
    PROMO(true),
    /** 订单与账号：恒开、不可关（库里 CHECK 也排除了它）。 */
    ORDER_ACCOUNT(false);

    private final boolean lockable;

    PushCategory(boolean lockable) {
        this.lockable = lockable;
    }

    /** 用户能否关闭本分类（字段名沿用架构 AD-1 的 {@code lockable}：true = 可关）。 */
    public boolean lockable() {
        return lockable;
    }

    /** 通知类型 → 推送分类（全覆盖、唯一归属）。 */
    public static PushCategory of(NotificationType type) {
        return switch (type) {
            case CONTENT_LIKED -> LIKE;
            case CONTENT_COMMENTED, PLACE_COMMENTED -> COMMENT;
            case CONTENT_MENTIONED -> MENTION;
            // V1.4.2：被追更与被关注同归「新粉丝」
            case USER_FOLLOWED, PET_FOLLOWED -> FOLLOWER;
            // V1.5.0 推送与召回：关注更新并入「追更宠物」
            case PET_DIARY_POSTED, USER_POST_PUBLISHED -> PET_DIARY;
            case PET_GIFT_RECEIVED -> GIFT;
            case PET_BIRTHDAY, COMPANION_ANNIVERSARY,
                    // V1.5.0 健康记录提醒：23 种提醒全归「宠物提醒」
                    HEALTH_VACCINE_D_MINUS_7,
                    HEALTH_VACCINE_D_DAY,
                    HEALTH_VACCINE_D_PLUS_7,
                    HEALTH_FIRST_VACCINE,
                    HEALTH_DEWORM_INTERNAL_D_DAY,
                    HEALTH_DEWORM_EXTERNAL_D_DAY,
                    HEALTH_DEWORM_FLEA_WORM_D_DAY,
                    HEALTH_DEWORM_HEARTWORM_D_DAY,
                    HEALTH_DEWORM_INTERNAL_D_PLUS_3,
                    HEALTH_DEWORM_EXTERNAL_D_PLUS_3,
                    HEALTH_DEWORM_LEGACY_D_DAY,
                    HEALTH_DEWORM_LEGACY_D_PLUS_3,
                    HEALTH_FIRST_DEWORM,
                    HEALTH_VET_VISIT_D_MINUS_1,
                    HEALTH_VET_VISIT_D_DAY,
                    HEALTH_STITCH_CHECK,
                    HEALTH_HEAT_DOG_D_MINUS_7,
                    HEALTH_PYOMETRA_WATCH,
                    HEALTH_HEAT_CAT_SECOND,
                    HEALTH_HEAT_MALE_FIRST,
                    HEALTH_SPAY_NEUTER_SUGGESTION,
                    HEALTH_CHECKUP,
                    HEALTH_CUSTOM_D_DAY -> PET_REMINDER;
            case MILESTONE_NODE, MILESTONE_SM_NODE, GROWTH_LEVEL_UP -> ACHIEVEMENT;
            case OPS_ANNOUNCEMENT, LIFECYCLE_D1, LIFECYCLE_D3, LIFECYCLE_D7, LIFECYCLE_WINBACK,
                    REPURCHASE_FOOD_LOW, CSAT_SURVEY,
                    // V1.4.2 shop：券快过期提醒归「促销与活动」（可关）
                    COUPON_EXPIRING,
                    // V1.5.0 超值礼包免费次数快到期 / 运营活动到账与获奖
                    PACK_CREDIT_EXPIRING, OPS_ACTIVITY_REWARDED, OPS_ACTIVITY_RANK_WON -> PROMO;
            // 订单与支付（V1.4.2 shop：迟到款已重新下单）
            case SHOP_ORDER_SHIPPED, SHOP_ORDER_EXCEPTION, SHOP_RETURN_UPDATED, SHOP_ORDER_REORDERED, REFUND_REJECTED,
                    PET_SLOT_EXPIRING, PET_SLOT_EXPIRED, GIFT_UNDELIVERED_REFUND,
                    // 客服
                    TICKET_RESOLVED,
                    // 审核与处置
                    CONTENT_REMOVED, CONTENT_REVIEW_REJECTED, CONTENT_REVIEW_TIMED_OUT, REPORT_REVIEWED,
                    NAME_RESET, AVATAR_RESET, ACCOUNT_WARNED, ACCOUNT_SUSPENDED,
                    // 兽医（NEW_CONSULT_REQUEST 发兽医账号，只为满足全覆盖）
                    VET_REPLY, NEW_CONSULT_REQUEST,
                    // 占位类型
                    CONSULT_CLOSED, CONTENT_REVIEW_APPROVED, IDENTITY_REQUIRE_MODIFY -> ORDER_ACCOUNT;
        };
    }
}
