package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.address.dto.ShippingAddressView;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.service.CheckoutService.CheckoutPreview;
import java.util.ArrayList;
import java.util.List;

/**
 * 结算页试算视图（Story 3.7，FR-97 / FR-99 / FR-100A / FR-104）。
 *
 * <p>🔴 <b>两段金额必须都下发</b>（FR-100A 规则 2）：{@code coinAmount} 与 {@code cashAmount}
 * 分开给，前端才能展示「PawCoin −60.000 / QRIS 310.000」。只回一个 {@code totalAmount}
 * 会逼前端自己拆，而拆分规则（余额、单笔上限、运费可否抵扣）全在服务端 —— 前端无从算起，
 * 算出来也必然与下单时固化的那份漂移。
 *
 * <p>🔴 <b>不含优惠券 / 促销码 / 会员折扣，也不预留会员价字段</b>（FR-97）——
 * 会员制 Roadmap 整体暂缓，不为暂缓功能提前埋成本。
 *
 * <p>🔴 <b>{@code shippingMethod} 恒为 {@code REGULER}</b>（C-14 把配送方式降为一维）。
 * 保留这个字段是为了让前端有个明确的「只有一档」来源，而不是各自写死一个字符串。
 */
public record CheckoutPreviewView(
        ShippingAddressView address,
        boolean serviceable,
        List<CheckoutLine> lines,
        List<CheckoutLine> unavailableLines,
        long goodsSubtotal,
        Long shippingFee,
        Long shippingDiscount,
        Long payableTotal,
        Long coinAmount,
        Long cashAmount,
        long coinBalance,
        long maxCoinPerOrder,
        boolean coinCapped,
        String strictestReturnPolicy,
        String shippingMethod) {

    /** 配送方式唯一档（C-14）。 */
    public static final String METHOD_REGULER = "REGULER";

    /** 结算页的一行商品。{@code returnPolicy} 为该行的**生效**规则（SKU 未设则继承商品）。 */
    public record CheckoutLine(
            String skuToken,
            String productToken,
            String productName,
            String specName,
            long price,
            int qty,
            String mainImageUrl,
            String returnPolicy,
            String invalidReason) {
    }

    public static CheckoutPreviewView of(CheckoutPreview p) {
        List<CheckoutLine> lines = new ArrayList<>();
        for (CartView.CartLine l : p.cart().lines()) {
            ReturnPolicy policy = p.returnPolicies().get(l.skuToken());
            lines.add(line(l, policy == null ? null : policy.name()));
        }
        List<CheckoutLine> unavailable = new ArrayList<>();
        for (CartView.CartLine l : p.cart().invalidLines()) {
            unavailable.add(line(l, null));
        }
        return new CheckoutPreviewView(
                ShippingAddressView.of(p.address()),
                p.serviceable(),
                lines,
                unavailable,
                p.cart().subtotal(),
                p.shipping() == null ? null : p.shipping().fee(),
                p.shipping() == null ? null : p.shipping().discount(),
                p.split() == null ? null : p.split().total(),
                p.split() == null ? null : p.split().coinAmount(),
                p.split() == null ? null : p.split().cashAmount(),
                p.coinBalance(),
                p.maxCoinPerOrder(),
                p.coinCapped(),
                strictest(p.returnPolicies().values()).name(),
                METHOD_REGULER);
    }

    private static CheckoutLine line(CartView.CartLine l, String policy) {
        return new CheckoutLine(l.skuToken(), l.productToken(), l.productName(), l.specName(),
                l.price(), l.qty(), l.mainImageUrl(), policy, l.invalidReason());
    }

    /**
     * 🔴 <b>多 SKU 订单取最严标识</b>（S-6）：{@code NON_RETURNABLE} &gt;
     * {@code NO_RETURN_AFTER_OPEN} &gt; {@code RETURNABLE}。
     *
     * <p>取最宽松的那一档会让用户以为整单都能退 —— 那是平台无法兑现的承诺，
     * 而 FR-104 的全部意义就是<b>在付款前把能退什么说清楚</b>。
     *
     * <p>🔴 <b>空集合同样落到最严</b>：拿不到任何一行的规则（如 SKU 已被物理删除）时
     * 说「可退」是凭空承诺。前端在没有商品行时本就不渲染这个提示位，
     * 所以这个取值只会在「有行但查不到规则」的异常路径上被看到 —— 那时保守才是对的。
     */
    static ReturnPolicy strictest(java.util.Collection<ReturnPolicy> policies) {
        if (policies.isEmpty()) {
            return ReturnPolicy.NON_RETURNABLE;
        }
        ReturnPolicy worst = ReturnPolicy.RETURNABLE;
        for (ReturnPolicy p : policies) {
            if (p == ReturnPolicy.NON_RETURNABLE) {
                return ReturnPolicy.NON_RETURNABLE;
            }
            if (p == ReturnPolicy.NO_RETURN_AFTER_OPEN) {
                worst = ReturnPolicy.NO_RETURN_AFTER_OPEN;
            }
        }
        return worst;
    }
}
