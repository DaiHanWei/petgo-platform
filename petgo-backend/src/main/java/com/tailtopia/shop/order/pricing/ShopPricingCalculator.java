package com.tailtopia.shop.order.pricing;

import com.tailtopia.shop.order.domain.PaymentSplit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 订单计价器（v1.4.2 Story 1.2，架构 D-7 / P-1）。
 *
 * <p>🔴 <b>金额单点</b>：应付、券减免、运费减免、保价费、PawCoin 与现金拆分只在这里算；
 * 结算试算、提交、订单详情一律调用它，Controller 与 App 不算钱。
 *
 * <p>🔴 <b>纯函数</b>：无状态、无 IO——不注入任何仓库、不读时钟（{@code now} 由入参给）、不读配置；
 * 不依赖券 / 物流领域类与 JPA 实体（m6）。全部 {@code long} 整数运算，加乘用
 * {@link Math#addExact} / {@link Math#multiplyExact}，溢出抛 {@link ArithmeticException}；
 * 除保价费 ceil 外不做任何取整。
 *
 * <p>计算顺序（PRD FR-130、FR-172）：
 * <ol>
 *   <li>商品金额 = Σ 单价 × 数量；</li>
 *   <li>满减券：门槛按<b>券前、范围内</b>金额，抵扣 ≤ 适用商品金额；</li>
 *   <li>商品实付 = 商品金额 − 满减；</li>
 *   <li>满额免运（按商品实付判门槛）与免运券（门槛按商品实付、有范围时全部商品在范围内）取减得多，
 *       相等用满额免运；减免 ≤ 所选运费，封顶 = min(本单最低报价, 封顶)；</li>
 *   <li>保价费 = ceil(商品实付 × 费率 / 10000)，不受运费减免影响；</li>
 *   <li>应付 = 商品实付 + 运费 − 运费减免 + 保价费；</li>
 *   <li>PawCoin 拆分复用 {@link PaymentSplit}；</li>
 *   <li>已省 = 满减 + 运费减免（不含 PawCoin，FR-131）。</li>
 * </ol>
 *
 * <p>默认组合（AUTO）是 PRD 原文的<b>贪心口径</b>（S-10）：先取抵扣最大的满减券，
 * 再按定下后的商品实付取优于满额免运的最大免运券，不做全局最优搜索。
 */
public final class ShopPricingCalculator {

    private static final long BP_DENOMINATOR = 10_000L;

    private ShopPricingCalculator() {
    }

    public static PricingResult price(PricingInput in) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(in.now(), "now");
        Objects.requireNonNull(in.coin(), "coin");
        List<PricingLine> lines = in.lines() == null ? List.of() : in.lines();
        List<PricingCoupon> coupons = in.coupons() == null ? List.of() : in.coupons();
        CouponSelection selection = in.selection() == null ? CouponSelection.auto() : in.selection();
        boolean manual = selection.mode() == CouponSelection.Mode.MANUAL;
        Instant now = in.now();

        // ① 商品金额
        long goods = 0;
        for (PricingLine l : lines) {
            goods = Math.addExact(goods, lineAmount(l));
        }

        Eval[] evals = new Eval[coupons.size()];
        boolean manualInvalid = false;

        // ② 满减券：评估全部，再选定
        for (int i = 0; i < coupons.size(); i++) {
            PricingCoupon c = coupons.get(i);
            if (c.kind() == PricingCouponKind.DISCOUNT) {
                evals[i] = evalDiscount(c, lines, now);
            }
        }
        int discountIdx;
        if (manual) {
            discountIdx = -1;
            String token = selection.discountUserCouponToken();
            if (token != null) {
                int idx = indexOf(coupons, token, PricingCouponKind.DISCOUNT);
                if (idx < 0 || !evals[idx].usable) {
                    manualInvalid = true;
                } else {
                    discountIdx = idx;
                }
            }
        } else {
            discountIdx = best(coupons, evals, PricingCouponKind.DISCOUNT, 0L);
        }
        long discountAmount = discountIdx < 0 ? 0L : evals[discountIdx].benefit;

        // ③ 商品实付
        long goodsNet = Math.subtractExact(goods, discountAmount);

        // ④⑤⑥ 运费与减免
        ShippingChoice shipping = in.shipping();
        boolean shippingKnown = shipping != null;
        FreeShippingRule rule = in.rule();
        long threshold = rule == null ? 0L : rule.threshold();

        long ruleDiscount = 0L;
        if (shippingKnown && threshold > 0 && goodsNet >= threshold) {
            Long cap = rule.cap();
            long ruleCap = (cap == null || cap == 0L)
                    ? shipping.lowestFee()
                    : Math.min(shipping.lowestFee(), cap);
            ruleDiscount = Math.max(0L, Math.min(shipping.selectedFee(), ruleCap));
        }

        for (int i = 0; i < coupons.size(); i++) {
            PricingCoupon c = coupons.get(i);
            if (c.kind() == PricingCouponKind.FREE_SHIPPING) {
                evals[i] = evalFreeShipping(c, lines, goodsNet, shipping, ruleDiscount, now);
            }
        }

        int freeShippingIdx = -1;
        if (shippingKnown) {
            if (manual) {
                String token = selection.freeShippingUserCouponToken();
                if (token != null) {
                    int idx = indexOf(coupons, token, PricingCouponKind.FREE_SHIPPING);
                    if (idx < 0 || isHardUnavailable(evals[idx])) {
                        manualInvalid = true;
                    } else if (evals[idx].usable && evals[idx].benefit > ruleDiscount) {
                        freeShippingIdx = idx;
                    }
                    // 只是输给满额免运（RULE_BETTER）不算无效：不扣券、用满额免运
                }
            } else {
                freeShippingIdx = best(coupons, evals, PricingCouponKind.FREE_SHIPPING, ruleDiscount);
            }
        } else if (manual) {
            // 运费未知：免运券只判有效期 / 范围 / 门槛，通过者不应用、不算无效
            String token = selection.freeShippingUserCouponToken();
            if (token != null) {
                int idx = indexOf(coupons, token, PricingCouponKind.FREE_SHIPPING);
                if (idx < 0 || isHardUnavailable(evals[idx])) {
                    manualInvalid = true;
                }
            }
        }

        long shippingFee = shippingKnown ? shipping.selectedFee() : 0L;
        long shippingDiscount;
        ShippingDiscountSource source;
        if (freeShippingIdx >= 0) {
            shippingDiscount = evals[freeShippingIdx].benefit;
            source = ShippingDiscountSource.COUPON;
        } else if (ruleDiscount > 0) {
            shippingDiscount = ruleDiscount;
            source = ShippingDiscountSource.FREE_SHIPPING_RULE;
        } else {
            shippingDiscount = 0L;
            source = ShippingDiscountSource.NONE;
        }

        // ⑦ 保价费：ceil(商品实付 × 费率 / 10000)，不受运费减免影响
        long insurance = in.insuranceApplicable()
                ? Math.addExact(Math.multiplyExact(goodsNet, (long) in.insuranceRateBp()),
                        BP_DENOMINATOR - 1) / BP_DENOMINATOR
                : 0L;

        // ⑧ 应付
        long shippingNet = Math.subtractExact(shippingFee, shippingDiscount);
        long shippingPortion = Math.addExact(shippingNet, insurance);
        long payable = Math.addExact(goodsNet, shippingPortion);

        // ⑨ PawCoin 拆分
        CoinOptions coin = in.coin();
        PaymentSplit split = PaymentSplit.compute(payable, shippingPortion, coin.balance(),
                coin.maxPerOrder(), coin.coinEnabled(), coin.allowShippingDeduction(), coin.useCoin());
        long deductible = coin.allowShippingDeduction()
                ? Math.max(payable, 0L)
                : Math.max(payable - shippingPortion, 0L);
        boolean coinCapped = coin.useCoin() && coin.coinEnabled()
                && Math.min(deductible, coin.balance()) > coin.maxPerOrder();

        // ⑩ 已省（不含 PawCoin）
        long saved = Math.addExact(discountAmount, shippingDiscount);

        // ⑪ 免运进度（基数 = 商品实付，与运费是否已知无关）
        FreeShippingProgress progress = threshold > 0
                ? new FreeShippingProgress(threshold, Math.max(0L, threshold - goodsNet))
                : null;

        List<CouponEvaluation> out = new ArrayList<>(coupons.size());
        for (int i = 0; i < coupons.size(); i++) {
            PricingCoupon c = coupons.get(i);
            Eval e = evals[i] == null ? Eval.no(CouponUnavailableReason.OUT_OF_SCOPE) : evals[i];
            boolean applied = i == discountIdx || i == freeShippingIdx;
            out.add(new CouponEvaluation(c.userCouponToken(), c.couponToken(), c.kind(),
                    e.usable, e.reason, e.usable ? e.benefit : 0L, applied));
        }

        return new PricingResult(goods, discountAmount, goodsNet,
                shippingFee, shippingDiscount, source,
                insurance, payable, split.coinAmount(), split.cashAmount(), coinCapped,
                saved, progress,
                discountIdx < 0 ? null : coupons.get(discountIdx).userCouponToken(),
                freeShippingIdx < 0 ? null : coupons.get(freeShippingIdx).userCouponToken(),
                manualInvalid, shippingKnown, Collections.unmodifiableList(out));
    }

    // ------------------------------------------------------------------ 评估

    /** 满减券：有效期 → 范围内有商品 → 范围内券前金额 ≥ 门槛；benefit = min(amount, eligible)。 */
    private static Eval evalDiscount(PricingCoupon c, List<PricingLine> lines, Instant now) {
        if (!withinValidity(c, now)) {
            return Eval.no(CouponUnavailableReason.EXPIRED);
        }
        long eligible = 0;
        for (PricingLine l : lines) {
            if (inScope(c, l)) {
                eligible = Math.addExact(eligible, lineAmount(l));
            }
        }
        if (eligible <= 0) {
            return Eval.no(CouponUnavailableReason.OUT_OF_SCOPE);
        }
        if (eligible < c.minSpend()) {
            return Eval.no(CouponUnavailableReason.BELOW_MIN_SPEND);
        }
        return Eval.ok(Math.max(0L, Math.min(c.amount(), eligible)));
    }

    /**
     * 免运券：有效期 → 有范围时每一行都在范围内 → 商品实付 ≥ 门槛；
     * benefit = min(所选运费, 本单最低报价, amount)；benefit ≤ 满额免运减免（且后者 &gt; 0）→ RULE_BETTER。
     * 运费未知时通过者 benefit = 0。
     */
    private static Eval evalFreeShipping(PricingCoupon c, List<PricingLine> lines, long goodsNet,
            ShippingChoice shipping, long ruleDiscount, Instant now) {
        if (!withinValidity(c, now)) {
            return Eval.no(CouponUnavailableReason.EXPIRED);
        }
        if (c.scopeType() != null && c.scopeType() != PricingScopeType.ALL) {
            for (PricingLine l : lines) {
                if (!inScope(c, l)) {
                    return Eval.no(CouponUnavailableReason.OUT_OF_SCOPE);
                }
            }
        }
        if (goodsNet < c.minSpend()) {
            return Eval.no(CouponUnavailableReason.BELOW_MIN_SPEND);
        }
        if (shipping == null) {
            return Eval.ok(0L);
        }
        long benefit = Math.max(0L,
                Math.min(shipping.selectedFee(), Math.min(shipping.lowestFee(), c.amount())));
        if (ruleDiscount > 0 && benefit <= ruleDiscount) {
            return Eval.no(CouponUnavailableReason.RULE_BETTER);
        }
        return Eval.ok(benefit);
    }

    /** {@code validFrom ≤ now < validUntil}；尚未生效也记 EXPIRED（防御）。 */
    private static boolean withinValidity(PricingCoupon c, Instant now) {
        if (c.validFrom() != null && now.isBefore(c.validFrom())) {
            return false;
        }
        return c.validUntil() == null || now.isBefore(c.validUntil());
    }

    private static boolean inScope(PricingCoupon c, PricingLine l) {
        PricingScopeType type = c.scopeType() == null ? PricingScopeType.ALL : c.scopeType();
        Set<String> values = c.scopeValues() == null ? Set.of() : c.scopeValues();
        return switch (type) {
            case ALL -> true;
            case CATEGORY -> l.category() != null && values.contains(l.category().name());
            case PRODUCT -> l.productToken() != null && values.contains(l.productToken());
        };
    }

    private static boolean isHardUnavailable(Eval e) {
        return !e.usable && e.reason != CouponUnavailableReason.RULE_BETTER;
    }

    private static long lineAmount(PricingLine l) {
        return Math.multiplyExact(l.unitPrice(), (long) l.qty());
    }

    // ------------------------------------------------------------------ 选择

    private static int indexOf(List<PricingCoupon> coupons, String userCouponToken,
            PricingCouponKind kind) {
        for (int i = 0; i < coupons.size(); i++) {
            PricingCoupon c = coupons.get(i);
            if (c.kind() == kind && userCouponToken.equals(c.userCouponToken())) {
                return i;
            }
        }
        return -1;
    }

    /** benefit 最大且 &gt; floor 的可用券；并列取先到期，再并列取 userCouponToken 字典序小。 */
    private static int best(List<PricingCoupon> coupons, Eval[] evals, PricingCouponKind kind,
            long floor) {
        int bestIdx = -1;
        for (int i = 0; i < coupons.size(); i++) {
            if (coupons.get(i).kind() != kind || !evals[i].usable || evals[i].benefit <= floor) {
                continue;
            }
            if (bestIdx < 0 || BETTER.compare(cand(coupons, evals, i), cand(coupons, evals, bestIdx)) < 0) {
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private record Cand(long benefit, Instant validUntil, String token) {
    }

    private static Cand cand(List<PricingCoupon> coupons, Eval[] evals, int i) {
        PricingCoupon c = coupons.get(i);
        return new Cand(evals[i].benefit, c.validUntil(), c.userCouponToken());
    }

    private static final Comparator<Cand> BETTER = Comparator
            .comparingLong(Cand::benefit).reversed()
            .thenComparing(Cand::validUntil, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Cand::token, Comparator.nullsLast(Comparator.naturalOrder()));

    private record Eval(boolean usable, CouponUnavailableReason reason, long benefit) {
        static Eval ok(long benefit) {
            return new Eval(true, null, benefit);
        }

        static Eval no(CouponUnavailableReason reason) {
            return new Eval(false, reason, 0L);
        }
    }
}
