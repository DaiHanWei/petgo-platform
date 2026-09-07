package com.tailtopia.shop.returns.domain;

/**
 * 退货类型（Story 5.1 / 5.3，FR-104A）。
 *
 * <p>🔴 <b>类型决定回程（寄回）运费由谁承担</b>，而<b>去程运费是否退</b>由
 * {@code isFullReturn} 决定（C-12）—— 两件事分开，不可合并。
 */
public enum ReturnType {

    /** 质量问题（破损 / 临期 / 错发）→ 回程运费<b>平台</b>承担，且触发平台责任补偿溢价（C-9）。 */
    QUALITY_ISSUE(ShippingFeeBearer.PLATFORM, true, false),

    /** 非质量问题（不想要 / 买错规格）→ 回程运费<b>用户</b>承担，且需未拆封。 */
    NON_QUALITY_ISSUE(ShippingFeeBearer.USER, false, false),

    /** 拒收（S-8 ①）→ 平台承担；🔴 <b>跳过寄回与质检</b>，货本来就没离开承运商。 */
    REFUSED_ON_DELIVERY(ShippingFeeBearer.PLATFORM, true, true),

    /** 发货前取消 → 无实物往返，🔴 <b>跳过寄回与质检</b>。 */
    CANCEL_BEFORE_SHIPMENT(ShippingFeeBearer.PLATFORM, false, true);

    private final ShippingFeeBearer returnShipBearer;
    private final boolean platformFault;
    private final boolean skipsShipback;

    ReturnType(ShippingFeeBearer returnShipBearer, boolean platformFault, boolean skipsShipback) {
        this.returnShipBearer = returnShipBearer;
        this.platformFault = platformFault;
        this.skipsShipback = skipsShipback;
    }

    /**
     * 回程运费归属。
     *
     * <p>🔴 <b>由类型自动得出，不给客服手工开关</b> —— 与去程运费同理：
     * 手工可调就会因人而异，而这直接是钱。
     */
    public ShippingFeeBearer returnShipBearer() {
        return returnShipBearer;
    }

    /**
     * 「未交付」—— 货<b>没有真正到过用户手上</b>：拒收（货没离开承运商）、发货前取消（无实物往返）。
     *
     * <h2>🔴 这是 C-1 反套利那道门</h2>
     * {@code premium_rate} / {@code premium_fixed} 这对配置是<b>只给这一支</b>的转币激励 ——
     * 迁移 {@code V20260817_2330} 的头注释（「①激励溢价…『未交付+转币』分支的反套利激励」）
     * 与 {@link com.tailtopia.config.domain.PawCoinConfig#refundPawcoinPremium} 的 javadoc
     * 两处都写明了。
     *
     * <p>已交付的退货若也给转币激励，等于<b>付钱请人「买 → 收货 → 退 → 转币」</b>：
     * 质量问题那一档尤其糟 —— 平台还要承担回程运费并另给补偿溢价。
     *
     * <p>⚠️ 与 {@link #skipsShipback()} <b>当前取值相同，但语义不同，不要合并</b>：
     * 那个说的是「流程上跳过寄回与质检」（结果），这个说的是「货没到过用户手上」（原因）。
     * 将来若出现「要寄回但仍算未交付」或反之，两者会分道扬镳，而门控必须跟着**原因**走。
     */
    public boolean isUndelivered() {
        return skipsShipback;
    }

    /** 是否平台责任 → 决定是否给平台责任补偿溢价（C-9，读独立配置项）。 */
    public boolean isPlatformFault() {
        return platformFault;
    }

    /** 🔴 跳过寄回与质检，批准后直接进入退款执行（AB-12A）。 */
    public boolean skipsShipback() {
        return skipsShipback;
    }

    /**
     * 宽松解析。🔴 <b>未知值抛错而不是默认到某一类</b> ——
     * 猜成 QUALITY_ISSUE 等于平台白担运费又白发溢价，猜成 NON_QUALITY_ISSUE 则是让
     * 有质量问题的用户自付运费。两个方向都不可接受。
     */
    public static ReturnType parse(String raw) {
        if (raw != null) {
            for (ReturnType t : values()) {
                if (t.name().equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
        }
        throw com.tailtopia.shared.error.AppException.validation("退货类型不合法");
    }
}
