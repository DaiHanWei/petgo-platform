package com.tailtopia.shop.cart.dto;

import java.util.List;

/**
 * 购物车视图（Story 3.1，FR-96）。
 *
 * <p>🔴 <b>失效商品单独成组</b>（{@code invalidLines}）：已下架或已售罄的行
 * <b>不参与合计、不可勾选</b>，但<b>也不静默消失</b>——用户加过什么应该看得见，
 * 悄悄删掉会让他以为自己记错了，而下架/售罄恰恰是最需要解释的时刻。
 *
 * <p>🔴 <b>{@code itemCount} 是件数不是种类数</b>：Tab 角标显示 3 而车里有 3 种商品共 7 件，
 * 会让用户以为漏加了。角标要跟用户脑子里的"我买了几件"对上。
 *
 * <p>🔴 <b>单店模型：没有店铺分组</b>——平台自营是唯一卖家。
 *
 * <p>🔴 <b>{@code subtotal} 与 {@code selectedSubtotal} 是两个字段，不是一个字段的两种语义</b>
 * （Story 4-1 / AD-S6）：
 * <ul>
 *   <li>{@code subtotal} / {@code itemCount} = 全部<b>有效</b>行（与勾选无关）。
 *       线上老版本 App 的购物车底栏读的就是它，而那个界面上<b>没有勾选框可点</b> ——
 *       把它改成选中合计，老用户会看到一个自己既无法解释也无法纠正的金额。
 *       <b>它的算法在本 story 里一个字符都没改。</b></li>
 *   <li>{@code selectedSubtotal} / {@code selectedCount} = 「{@code selected=true}
 *       <b>且</b> {@code invalidReason == null}」的行。新语义一律进新字段。</li>
 * </ul>
 * 注意 {@code subtotal} 本来就不含失效行（{@code CartService.view} 只在 {@code reason == null}
 * 时累加）—— 「全车合计」是文档笔误，以代码为准。
 */
public record CartView(
        List<CartLine> lines,
        List<CartLine> invalidLines,
        long subtotal,
        long selectedSubtotal,
        int itemCount,
        int selectedCount) {

    /**
     * @param invalidReason 失效原因；有效行为 {@code null}。
     *     🔴 <b>要区分「下架」与「售罄」</b>——前者是永久的（换个商品吧），
     *     后者是暂时的（可以等），给同一句话会让用户做错决定。
     */
    public record CartLine(
            String skuToken,
            String productToken,
            String productName,
            String specName,
            long price,
            int qty,
            String mainImageUrl,
            Long availableStock,
            String invalidReason,
            String entrySource,
            String triggerType,
            boolean selected) {

        /**
         * 不带归因的 9 参构造（Story 3.10 追加两列时保留）。
         *
         * <p>🔴 归因只在下单抄写时用得上，<b>不下发给客户端</b>：它是后台看板的口径，
         * 对用户没有意义，而多下发一个字段就多一处可能被误当成展示数据的地方。
         *
         * <p>Story 4-1 追加 {@code selected} 时，这个重载补的默认值是 {@code true} ——
         * 与列的 {@code DEFAULT TRUE} 同一个理由：没有明确取消过勾选，就是选中。
         */
        public CartLine(String skuToken, String productToken, String productName, String specName,
                long price, int qty, String mainImageUrl, Long availableStock,
                String invalidReason) {
            this(skuToken, productToken, productName, specName, price, qty, mainImageUrl,
                    availableStock, invalidReason, null, null, true);
        }

        /**
         * 带归因、不带选择位的 11 参构造（Story 4-1 追加 {@code selected} 时保留）。
         *
         * <p>🔴 <b>既有重载一个都不删</b>：本仓已有多处调用点按 11 参签名构造行
         * （测试与后台组装），删掉它们等于把一次 DTO 扩字段变成一次大范围返工。
         */
        public CartLine(String skuToken, String productToken, String productName, String specName,
                long price, int qty, String mainImageUrl, Long availableStock,
                String invalidReason, String entrySource, String triggerType) {
            this(skuToken, productToken, productName, specName, price, qty, mainImageUrl,
                    availableStock, invalidReason, entrySource, triggerType, true);
        }
    }

    /**
     * 「勾选且有效」的行 —— <b>结算与下单的唯一输入集合</b>（Story 4-1，SHOP-FR-04 / SD-6）。
     *
     * <p>🔴 <b>过滤条件只写在这一处。</b>{@code CheckoutService.preview} /
     * {@code placeOrder} / {@code CheckoutPreviewView.of} 三个地方都调它 ——
     * 各自过滤一遍必然漂移，表现为「结算页显示买 2 件，提交后买了 3 件」，
     * 而那是会造成资损的谎，不是显示问题。
     *
     * <p>🔴 条件是「{@code selected} <b>且</b> 在 {@code lines()} 里」。{@code lines()} 本身
     * 已经只含 {@code invalidReason == null} 的行，所以这里<b>不重列</b>「没下架 && 没售罄」——
     * Epic 6 追加第三种失效原因（停用品类，SHOP-FR-19）时，那种写法会漏。
     *
     * <p>配套的金额是 {@link #selectedSubtotal()}，由 {@code CartService.view} 在同一个
     * 循环里算出：<b>全仓只有那一处在求和</b>，这里不再算第二遍。
     */
    public List<CartLine> selectedLines() {
        return lines.stream().filter(CartLine::selected).toList();
    }

    /** 失效原因：商品已下架。 */
    public static final String REASON_DELISTED = "DELISTED";
    /** 失效原因：该规格已售罄。 */
    public static final String REASON_OUT_OF_STOCK = "OUT_OF_STOCK";
}
