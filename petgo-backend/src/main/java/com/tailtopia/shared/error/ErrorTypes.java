package com.tailtopia.shared.error;

import java.net.URI;

/**
 * RFC 9457 ProblemDetail 的 {@code type} URI 常量。
 * 各业务模块抛 {@link AppException} 时引用，保证全平台错误 type 一致、可枚举。
 */
public final class ErrorTypes {

    private static final String BASE = "https://petgo/errors/";

    public static final URI VALIDATION = URI.create(BASE + "validation");
    public static final URI UNAUTHORIZED = URI.create(BASE + "unauthorized");
    public static final URI FORBIDDEN = URI.create(BASE + "forbidden");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI RATE_LIMITED = URI.create(BASE + "rate-limited");
    public static final URI INTERNAL = URI.create(BASE + "internal");

    /** Story 2.1：媒体凭证 / 签名 URL 签发异常（绝不外泄 OSS 原始错误/堆栈）。 */
    public static final URI MEDIA_CREDENTIAL = URI.create(BASE + "media-credential");

    /** Story 2.2：单账号单宠物——已存在档案时再建（409）。 */
    public static final URI PROFILE_EXISTS = URI.create(BASE + "profile-exists");

    /** V1.4.0 multi-pet Story 1.4（AD-3）：建档时名额已满（409），扩展成员见 {@code PetSlotFullException}。 */
    public static final URI PET_SLOT_FULL = URI.create(BASE + "pet-slot-full");

    /** V1.4.0 multi-pet Story 1.4（AD-3）：已达每账号 5 只上限（409，优先于 {@link #PET_SLOT_FULL}）。 */
    public static final URI PET_LIMIT_REACHED = URI.create(BASE + "pet-limit-reached");

    /**
     * V1.4.0 multi-pet Story 2.1（AD-8）：对只读宠物做写操作（409），ProblemDetail 扩展成员 {@code petId}，
     * 见 {@link PetReadOnlyException}。
     */
    public static final URI PET_READ_ONLY = URI.create(BASE + "pet-read-only");

    /**
     * V1.4.0 上线前旧版兼容检查：多宠物账号编辑 / 删除宠物档案未带 {@code petId}（409）。旧版 App 只认「默认宠物」且整会话缓存，
     * 默认宠物中途变化时会改错 / 删错另一只 —— 多宠账号必须显式指定，只能经新版 App（恒带 petId）操作。
     */
    public static final URI PET_ID_REQUIRED = URI.create(BASE + "pet-id-required");

    /** V1.4.0 multi-pet Story 2.1（D13）：账号宠物数 ≤ 名额，无需选择保持正常的宠物（409）。 */
    public static final URI PET_SELECTION_NOT_NEEDED = URI.create(BASE + "pet-selection-not-needed");

    /**
     * V1.4.0 multi-pet Story 2.2（AD-6）：租用个数超出可租数 / 续租个数超出「5 − 1 − 等级名额」（409），
     * 扩展成员 {@code rentable}（续租另带 {@code renewableMax}），见 {@link PetSlotRentUnavailableException}。
     */
    public static final URI PET_SLOT_RENT_UNAVAILABLE = URI.create(BASE + "pet-slot-rent-unavailable");

    /** V1.4.0 multi-pet Story 2.2（AD-6）：所选名额已过期 / 非生效中，不能续租（409，走新租）。 */
    public static final URI PET_SLOT_RENEW_INVALID = URI.create(BASE + "pet-slot-renew-invalid");

    /** Story 2.3 R2（F10）：发布时三方自动审核——文字关键词命中违规（422，不落库）。 */
    public static final URI CONTENT_TEXT_BLOCKED = URI.create(BASE + "content-text-blocked");

    /** Story 2.3 R2（F10）：发布时三方自动审核——图像识别命中违规（422，不落库）。 */
    public static final URI CONTENT_IMAGE_BLOCKED = URI.create(BASE + "content-image-blocked");

    /** 内容审核 story 3：评论发送同步过滤命中（L1 硬拦截 / 风险 ≥0.8，422，从未落库/不发事件/不入队）。 */
    public static final URI COMMENT_BLOCKED = URI.create(BASE + "comment-blocked");

    /** bug 20260806：PawCoin 余额不足（409）。专属 type——409 在支付链路多因复用（支付窗过期/守卫不符），
     * 前端须按 type 精确映射「余额不足」文案，不能只看 status。 */
    public static final URI PAWCOIN_INSUFFICIENT = URI.create(BASE + "pawcoin-insufficient");

    /** Story 1.1（V1.1.4）：请求他人主页但已主动拉黑对方（403）。专属 type——前端须据此把
     * 「已拉黑」与「网络失败」两种不弹卡的情况区分开（UI 稿 A4 / A5 两个不同 Toast），
     * 不能混为一谈。响应体不含被拉黑者的任何展示字段。 */
    public static final URI BLOCKED_USER = URI.create(BASE + "blocked-user");

    /** V1.3.2 Story 1.1：场所打卡——不在场所 500m 内（422）。🔴 detail / 扩展字段不得带距离值（防试探边界）。 */
    public static final URI CHECKIN_TOO_FAR = URI.create(BASE + "checkin-too-far");

    /** V1.3.2 Story 1.1：场所打卡——该宠物今天（WIB 自然日）已在该场所打过卡（409）。 */
    public static final URI CHECKIN_ALREADY_TODAY = URI.create(BASE + "checkin-already-today");

    /** V1.3.2 Story 1.1：场所打卡——账号还没有宠物档案（422）。 */
    public static final URI CHECKIN_NO_PET = URI.create(BASE + "checkin-no-pet");

    /** V1.3.2 Story 1.1：场所打卡——petIds 含非本人宠物（403）。 */
    public static final URI CHECKIN_PET_FORBIDDEN = URI.create(BASE + "checkin-pet-forbidden");

    /** V1.3.2 Story 1.5：发帖关联的打卡不存在或不是本人的（422）。 */
    public static final URI POST_CHECKIN_INVALID = URI.create(BASE + "post-checkin-invalid");

    /** V1.3.2 Story 3.1：一次性解锁对象已解锁 / 已付款，无需（不能）再次购买（409）。 */
    public static final URI KEEPSAKE_ALREADY_UNLOCKED = URI.create(BASE + "keepsake-already-unlocked");
    /** V1.3.2 Story 3.3：佩戴未解锁的 Tailsonality 结果（422）。 */
    public static final URI TAILSONALITY_BADGE_LOCKED = URI.create(BASE + "tailsonality-badge-locked");

    /**
     * V1.3.4 follow Story 1.1：关注一个<b>我已拉黑</b>的人（409）。
     * 只在「我拉黑了对方」时出现；<b>对方拉黑了我不出现</b>（D-4，不暴露被拉黑）。
     */
    public static final URI FOLLOW_BLOCKED = URI.create(BASE + "follow-blocked");

    /**
     * 头像框未解锁（V1.4.0 ① Story 3.3 · AD-16）：所选档不在当前段位可用档内，{@code frame_choice} 不变（409）。
     */
    public static final URI FRAME_LOCKED = URI.create(BASE + "frame-locked");

    /** V1.4.0 gift Story 1.1（FR-148 / PRD AC1）：给自己的宠物送礼（422）。 */
    public static final URI GIFT_OWN_PET = URI.create(BASE + "gift-own-pet");

    /**
     * V1.4.0 gift Story 1.1（FR-148 / PRD AC9）：送礼人<b>拉黑了</b>宠物主人（403）。
     * 只拦单向 {@code isBlocked(sender, owner)}；主人拉黑送礼人<b>不拦</b>。
     */
    public static final URI GIFT_BLOCKED = URI.create(BASE + "gift-blocked");

    /** V1.4.0 gift Story 1.1（FR-148 / PRD AC3）：专属礼物未解锁（送礼人等级不足或不参与成长体系，403）。 */
    public static final URI GIFT_LOCKED = URI.create(BASE + "gift-locked");

    /** V1.4.0 gift Story 1.1（FR-148 / PRD AC4 · AD-4）：QRIS 单笔低于送礼最低金额（422）。 */
    public static final URI GIFT_QRIS_BELOW_MIN = URI.create(BASE + "gift-qris-below-min");

    /** V1.4.0 gift Story 1.1（FR-148）：收礼宠物不存在或主人已注销（404）。封号主人不算。 */
    public static final URI GIFT_PET_GONE = URI.create(BASE + "gift-pet-gone");

    /** V1.4.0 gift Story 1.1（FR-148）：礼物不存在或已下线（409）。 */
    public static final URI GIFT_UNAVAILABLE = URI.create(BASE + "gift-unavailable");

    /** V1.4.0 ④ Story 1.1：试图关闭「订单与账号」类推送（422，该分类不可关）。 */
    public static final URI PUSH_CATEGORY_LOCKED = URI.create(BASE + "push-category-locked");

    /** V1.4.0 ④ Story 2.1：收藏私密帖（422，收藏只限公开帖 P-2）。 */
    public static final URI COLLECT_NOT_PUBLIC = URI.create(BASE + "collect-not-public");

    /** V1.4.1 商家平台：填的门店邀请码不存在或门店已停用（422，契约 FR-7 / FR-40 —— 停用门店的码同样提示无效）。 */
    public static final URI MERCHANT_CODE_INVALID = URI.create(BASE + "merchant-code-invalid");

    /** V1.4.1 商家平台：用户已绑定过门店，不能再填码（409，FR-7 —— 绑定永久，不能换店）。 */
    public static final URI MERCHANT_ALREADY_BOUND = URI.create(BASE + "merchant-already-bound");

    /** v1.4.2 shop Story 1.1（D-10 / FR-172.5）：SKU 包裹参数不全，暂不可售（加购 / 立即购买 422）。 */
    public static final URI SKU_NOT_SELLABLE = URI.create(BASE + "sku-not-sellable");

    /** v1.4.2 shop Story 1.1（D-9 / C-5）：Biteship 询价失败（503，不回退固定运费，结算不可提交）。 */
    public static final URI QUOTE_FAILED = URI.create(BASE + "quote-failed");

    /**
     * v1.4.2 shop Story 2.1（D-3 / P-5 / C-2）：提交订单时所选用户券已不可用（被占用 / 过期 / 作废 / 非本人，409），
     * 扩展成员 {@code userCouponTokens}，见 {@code CouponInvalidException}。App 走 D17「券已失效、已为你重新选择」。
     */
    public static final URI COUPON_INVALID = URI.create(BASE + "coupon-invalid");

    /** v1.4.2 shop Story 2.2（P-5 / C-2）：兑换码不存在；领券时券 token 不存在或不是可领券（404，G7「Kode kupon tidak valid」）。 */
    public static final URI COUPON_NOT_FOUND = URI.create(BASE + "coupon-not-found");

    /** v1.4.2 shop Story 2.2：一次性兑换码已被使用（409，G7「Kode ini sudah dipakai」）。 */
    public static final URI COUPON_CODE_USED = URI.create(BASE + "coupon-code-used");

    /** v1.4.2 shop Story 2.2：发放期未开始 / 已结束，或券已停用（409，G7「Kode ini tidak dalam masa berlaku」）。 */
    public static final URI COUPON_NOT_IN_PERIOD = URI.create(BASE + "coupon-not-in-period");

    /** v1.4.2 shop Story 2.2：已达每人限领（409，A5 / G7「Kamu sudah klaim kupon ini」）。 */
    public static final URI COUPON_ALREADY_CLAIMED = URI.create(BASE + "coupon-already-claimed");

    /** v1.4.2 shop Story 2.2：发放总量已领完（409，G7「Kupon sudah habis」）。 */
    public static final URI COUPON_SOLD_OUT = URI.create(BASE + "coupon-sold-out");

    /** v1.4.2 shop Story 2.2（D-4）：1 小时内输错 10 次，兑换锁定 1 小时（429，G8）。 */
    public static final URI COUPON_REDEEM_LOCKED = URI.create(BASE + "coupon-redeem-locked");

    /**
     * v1.4.2 shop Story 3.2（P-5 / C-2）：提交订单时有行不可买（409），扩展成员 {@code unavailableLines}
     * （见 {@code CheckoutUnavailableException}）。旧版 App 按 409 + 该成员识别，不看 type。
     */
    public static final URI UNAVAILABLE_LINES = URI.create(BASE + "unavailable-lines");

    /** v1.4.2 shop Story 3.2：无地址 / 区域未开通 / 无可结算行 / 旧结算模式询价 0 家（422）。 */
    public static final URI NOT_PLACEABLE = URI.create(BASE + "not-placeable");

    /** v1.4.2 shop Story 3.2（I7）：报价不存在 / 已过期 / 未绑定本单地址与商品 / 所选快递不在报价中（409，App 重新试算）。 */
    public static final URI QUOTE_EXPIRED = URI.create(BASE + "quote-expired");

    /**
     * v1.4.2 shop Story 3.3（S-7 / I3）：订单已超过付款时间（含 60~65 分钟宽限期），或已过出码窗口（建单 10 分钟后
     * 且无可复用的码）→ 409，App 显示「付款超时·订单已取消」。
     */
    public static final URI SHOP_ORDER_NOT_PAYABLE = URI.create(BASE + "shop-order-not-payable");

    /** v1.4.2 shop Story 4.1 AC4.3：取消退款单已不在可选去向的状态（已选 PawCoin / 打款中 / 已完成）（409，App 重取详情）。 */
    public static final URI REFUND_DESTINATION_LOCKED = URI.create(BASE + "refund-destination-locked");

    /** v1.4.2 shop Story 4.1 AC4.5：退到银行 / e-wallet 时现金段不高于渠道手续费（422，App 提示改选其他方式）。 */
    public static final URI REFUND_BELOW_PAYOUT_FEE = URI.create(BASE + "refund-below-payout-fee");

    /** v1.4.2 shop Story 4.1：订单已无可退金额，不生成 0 元取消退款单（409）。 */
    public static final URI NOTHING_TO_REFUND = URI.create(BASE + "nothing-to-refund");

    /** v1.4.2 shop Story 4.2 AC1（D-11）：该笔迟到款已处置（已重下单 / 已退款）或订单无迟到款（409）。 */
    public static final URI LATE_PAYMENT_ALREADY_RESOLVED = URI.create(BASE + "late-payment-already-resolved");

    /** v1.4.2 shop Story 4.2 AC2.1：原单不是已取消（如重复付款单正在履约），只能退款不能重新下单（409）。 */
    public static final URI LATE_PAYMENT_NOT_REORDERABLE = URI.create(BASE + "late-payment-not-reorderable");

    /** v1.4.2 shop Story 4.2 AC2.4（D-3）：原单用过的券已被别单占用 / 用掉 / 作废，不能重新下单（409）。 */
    public static final URI REORDER_COUPON_UNAVAILABLE = URI.create(BASE + "reorder-coupon-unavailable");

    /** v1.4.2 shop Story 4.2 AC2.5（S-6）：需要扣的 PawCoin 超过用户当前余额，只能退款（409）。 */
    public static final URI REORDER_COIN_INSUFFICIENT = URI.create(BASE + "reorder-coin-insufficient");

    /**
     * v1.4.2 shop Story 6.1（D-17 / P-5 / C-2）：商品存在但已下架（{@code is_active = false}）→ 410，
     * App 按 type 分流到 B13「商品已下架」页（不存在仍是 404 {@code not-found}）。
     */
    public static final URI SHOP_PRODUCT_DELISTED = URI.create(BASE + "product-delisted");

    private ErrorTypes() {
    }
}
