package com.tailtopia.shop.order.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shop.domain.ProductCategory;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：订单计价器表驱动用例（v1.4.2 Story 1.2 AC4，P01 ~ P52，🔒 资金攸关）。
 *
 * <p>每例除表中字段外，均断言两个不变式：
 * {@code payable = goodsNet + shippingFee − shippingDiscount + insuranceFee}、
 * {@code coinAmount + cashAmount = payable}。
 */
class ShopPricingCalculatorTest {

    // ------------------------------------------------------------------ 公共夹具

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final Instant FROM = NOW.minus(Duration.ofDays(1));
    private static final Instant UNTIL = NOW.plus(Duration.ofDays(5));

    private static PricingLine line(String product, ProductCategory cat, long price, int qty) {
        return new PricingLine("sku_" + product, product, cat, price, qty);
    }

    private static final PricingLine L_FOOD = line("P1", ProductCategory.MAKANAN, 150_000, 2);
    private static final PricingLine L_SNACK = line("P2", ProductCategory.CAMILAN, 49_000, 1);

    private static PricingCoupon discount(String token, long amount, long minSpend,
            PricingScopeType scope, Set<String> values) {
        return new PricingCoupon(token, "tpl_" + token, PricingCouponKind.DISCOUNT, amount, minSpend,
                scope, values, FROM, UNTIL);
    }

    private static PricingCoupon freeShip(String token, long cap, long minSpend,
            PricingScopeType scope, Set<String> values) {
        return new PricingCoupon(token, "tpl_" + token, PricingCouponKind.FREE_SHIPPING, cap, minSpend,
                scope, values, FROM, UNTIL);
    }

    private static PricingCoupon withValidity(PricingCoupon c, Instant from, Instant until) {
        return new PricingCoupon(c.userCouponToken(), c.couponToken(), c.kind(), c.amount(),
                c.minSpend(), c.scopeType(), c.scopeValues(), from, until);
    }

    private static PricingCoupon withToken(PricingCoupon c, String token) {
        return new PricingCoupon(token, c.couponToken(), c.kind(), c.amount(),
                c.minSpend(), c.scopeType(), c.scopeValues(), c.validFrom(), c.validUntil());
    }

    private static final PricingCoupon D20 = discount("d20", 20_000, 100_000, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon F10 = freeShip("f10", 10_000, 0, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon F12 = freeShip("f12", 12_000, 0, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon F15 = freeShip("f15", 15_000, 0, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon F25 = freeShip("f25", 25_000, 0, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon F10F = freeShip("f10f", 10_000, 0, PricingScopeType.CATEGORY, Set.of("MAKANAN"));
    private static final PricingCoupon F10M = freeShip("f10m", 10_000, 330_000, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon D30 = discount("d30", 30_000, 300_000, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon D30F = discount("d30f", 30_000, 250_000, PricingScopeType.CATEGORY, Set.of("MAKANAN"));
    private static final PricingCoupon DSN = discount("dsn", 10_000, 100_000, PricingScopeType.CATEGORY, Set.of("CAMILAN"));
    private static final PricingCoupon DOB = discount("dob", 10_000, 0, PricingScopeType.CATEGORY, Set.of("OBAT_VITAMIN"));
    private static final PricingCoupon D60P2 = discount("d60p2", 60_000, 0, PricingScopeType.PRODUCT, Set.of("P2"));
    private static final PricingCoupon D20P1 = discount("d20p1", 20_000, 200_000, PricingScopeType.PRODUCT, Set.of("P1"));
    private static final PricingCoupon D50 = discount("d50", 50_000, 0, PricingScopeType.ALL, Set.of());
    private static final PricingCoupon D5 = discount("d5", 5_000, 0, PricingScopeType.ALL, Set.of());

    /** 可变夹具：默认值即 AC4「公共夹具」。 */
    private static final class Fx {
        List<PricingLine> lines = new ArrayList<>(List.of(L_FOOD, L_SNACK));
        List<PricingCoupon> coupons = new ArrayList<>();
        CouponSelection selection = CouponSelection.auto();
        FreeShippingRule rule = new FreeShippingRule(0, null);
        ShippingChoice shipping = new ShippingChoice(10_000, 10_000);
        boolean insurance = false;
        int rateBp = 50;
        boolean useCoin = true;
        long balance = 12_000;
        long maxPerOrder = 1_000_000;
        boolean coinEnabled = true;
        boolean allowShippingDeduction = true;

        Fx lines(PricingLine... ls) {
            lines = new ArrayList<>(Arrays.asList(ls));
            return this;
        }

        Fx coupons(PricingCoupon... cs) {
            coupons = new ArrayList<>(Arrays.asList(cs));
            return this;
        }

        Fx ship(long selected, long lowest) {
            shipping = new ShippingChoice(selected, lowest);
            return this;
        }

        Fx rule(long threshold, Long cap) {
            rule = new FreeShippingRule(threshold, cap);
            return this;
        }

        PricingInput input() {
            return new PricingInput(lines, coupons, selection, rule, shipping, insurance, rateBp,
                    new CoinOptions(useCoin, balance, maxPerOrder, coinEnabled, allowShippingDeduction), NOW);
        }

        PricingResult run() {
            PricingResult r = ShopPricingCalculator.price(input());
            assertInvariants(r);
            return r;
        }
    }

    private static Fx fx() {
        return new Fx();
    }

    /** P01 的夹具：默认 + 券 [D20, F10]。 */
    private static Fx p01() {
        return fx().coupons(D20, F10);
    }

    /** P07 的夹具：PRD FR-172 原例。 */
    private static Fx p07() {
        Fx f = fx().lines(line("P1", ProductCategory.MAKANAN, 100_000, 2))
                .ship(28_000, 15_000).rule(100_000, 20_000L);
        f.balance = 0;
        return f;
    }

    private static void assertInvariants(PricingResult r) {
        assertThat(r.payable()).as("应付不变式")
                .isEqualTo(r.goodsNetAmount() + r.shippingFee() - r.shippingDiscount() + r.insuranceFee());
        assertThat(r.coinAmount() + r.cashAmount()).as("币 + 现金 = 应付").isEqualTo(r.payable());
        assertThat(r.goodsNetAmount()).isEqualTo(r.goodsAmount() - r.discountCouponAmount());
        assertThat(r.savedAmount()).isEqualTo(r.discountCouponAmount() + r.shippingDiscount());
        assertThat(r.shippingDiscount()).isLessThanOrEqualTo(r.shippingFee());
        assertThat(r.evaluations().stream().filter(e -> e.applied()
                && e.kind() == PricingCouponKind.DISCOUNT).count()).isLessThanOrEqualTo(1);
        assertThat(r.evaluations().stream().filter(e -> e.applied()
                && e.kind() == PricingCouponKind.FREE_SHIPPING).count()).isLessThanOrEqualTo(1);
    }

    private static CouponEvaluation eval(PricingResult r, String token) {
        return r.evaluations().stream().filter(e -> e.userCouponToken().equals(token)).findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------ UI 稿 D1 / D12 ~ D15

    @Test
    @DisplayName("P01 UI D1 默认")
    void p01_uiD1Default() {
        PricingResult r = p01().run();
        assertThat(r.goodsAmount()).isEqualTo(349_000);
        assertThat(r.discountCouponAmount()).isEqualTo(20_000);
        assertThat(r.goodsNetAmount()).isEqualTo(329_000);
        assertThat(r.shippingFee()).isEqualTo(10_000);
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.COUPON);
        assertThat(r.appliedDiscountUserCouponToken()).isEqualTo("d20");
        assertThat(r.appliedFreeShippingUserCouponToken()).isEqualTo("f10");
        assertThat(r.insuranceFee()).isZero();
        assertThat(r.payable()).isEqualTo(329_000);
        assertThat(r.coinAmount()).isEqualTo(12_000);
        assertThat(r.cashAmount()).isEqualTo(317_000);
        assertThat(r.savedAmount()).isEqualTo(30_000);
        assertThat(r.freeShippingProgress()).isNull();
        assertThat(r.shippingKnown()).isTrue();
        assertThat(r.manualSelectionInvalid()).isFalse();
        assertThat(r.coinCapped()).isFalse();
        assertThat(r.evaluations()).extracting(CouponEvaluation::userCouponToken).containsExactly("d20", "f10");
        assertThat(r.evaluations()).allMatch(e -> e.usable() && e.applied() && e.reason() == null);
    }

    @Test
    @DisplayName("P02 UI D1 开保价")
    void p02_uiD1Insurance() {
        Fx f = p01();
        f.insurance = true;
        PricingResult r = f.run();
        assertThat(r.insuranceFee()).isEqualTo(1_645);
        assertThat(r.payable()).isEqualTo(330_645);
        assertThat(r.coinAmount()).isEqualTo(12_000);
        assertThat(r.cashAmount()).isEqualTo(318_645);
    }

    @Test
    @DisplayName("P03 UI D12 满额免运 = 免运券 → 用满额免运")
    void p03_uiD12RuleEqualsCoupon() {
        PricingResult r = p01().rule(300_000, null).run();
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        CouponEvaluation f10 = eval(r, "f10");
        assertThat(f10.usable()).isFalse();
        assertThat(f10.reason()).isEqualTo(CouponUnavailableReason.RULE_BETTER);
        assertThat(f10.applied()).isFalse();
        assertThat(r.appliedFreeShippingUserCouponToken()).isNull();
        assertThat(r.payable()).isEqualTo(329_000);
        assertThat(r.freeShippingProgress()).isEqualTo(new FreeShippingProgress(300_000, 0));
    }

    @Test
    @DisplayName("P04 UI D13 超单笔上限")
    void p04_uiD13CoinCapped() {
        Fx f = fx().lines(line("P1", ProductCategory.MAKANAN, 130_000, 10)).coupons(D20, F10);
        f.balance = 1_500_000;
        PricingResult r = f.run();
        assertThat(r.payable()).isEqualTo(1_280_000);
        assertThat(r.coinAmount()).isEqualTo(1_000_000);
        assertThat(r.cashAmount()).isEqualTo(280_000);
        assertThat(r.coinCapped()).isTrue();
    }

    @Test
    @DisplayName("P05 UI D14 PawCoin 足额")
    void p05_uiD14CoinFull() {
        Fx f = p01();
        f.balance = 500_000;
        PricingResult r = f.run();
        assertThat(r.coinAmount()).isEqualTo(329_000);
        assertThat(r.cashAmount()).isZero();
        assertThat(r.coinCapped()).isFalse();
    }

    @Test
    @DisplayName("P06 UI D15 不用 PawCoin")
    void p06_uiD15NoCoin() {
        Fx f = p01();
        f.useCoin = false;
        PricingResult r = f.run();
        assertThat(r.coinAmount()).isZero();
        assertThat(r.cashAmount()).isEqualTo(329_000);
        assertThat(r.coinCapped()).isFalse();
    }

    // ------------------------------------------------------------------ 满额免运封顶（FR-172）

    @Test
    @DisplayName("P07 PRD FR-172 原例：最低 15,000 / 上限 20,000 / 选 28,000 → 减 15,000")
    void p07_prdFr172() {
        PricingResult r = p07().run();
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        assertThat(r.shippingFee() - r.shippingDiscount()).as("用户付运费").isEqualTo(13_000);
        assertThat(r.payable()).isEqualTo(213_000);
        assertThat(r.savedAmount()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("P08 封顶小于最低报价")
    void p08_capBelowLowest() {
        PricingResult r = p07().rule(100_000, 10_000L).run();
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.payable()).isEqualTo(218_000);
    }

    @Test
    @DisplayName("P09 封顶为空 = 不设额外封顶")
    void p09_capNull() {
        PricingResult r = p07().rule(100_000, null).run();
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(r.payable()).isEqualTo(213_000);
    }

    @Test
    @DisplayName("P10 封顶为 0 = 不设额外封顶（m7）")
    void p10_capZero() {
        PricingResult r = p07().rule(100_000, 0L).run();
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(r.payable()).isEqualTo(213_000);
    }

    @Test
    @DisplayName("P11 选的就是最低价")
    void p11_selectedIsLowest() {
        PricingResult r = p07().ship(8_000, 8_000).run();
        assertThat(r.shippingDiscount()).isEqualTo(8_000);
        assertThat(r.payable()).isEqualTo(200_000);
    }

    @Test
    @DisplayName("P12 免运券减得少于满额免运")
    void p12_couponLessThanRule() {
        PricingResult r = p07().coupons(F12).run();
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        assertThat(eval(r, "f12").reason()).isEqualTo(CouponUnavailableReason.RULE_BETTER);
        assertThat(eval(r, "f12").usable()).isFalse();
        assertThat(r.payable()).isEqualTo(213_000);
    }

    @Test
    @DisplayName("P13 免运券减得多")
    void p13_couponMoreThanRule() {
        PricingResult r = fx().lines(line("P1", ProductCategory.MAKANAN, 100_000, 2))
                .ship(15_000, 15_000).rule(100_000, 10_000L).coupons(F25).run();
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.COUPON);
        assertThat(r.appliedFreeShippingUserCouponToken()).isEqualTo("f25");
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(r.payable()).isEqualTo(200_000);
    }

    @Test
    @DisplayName("P14 相等用满额免运")
    void p14_equalUsesRule() {
        PricingResult r = p07().coupons(F15).run();
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        assertThat(eval(r, "f15").reason()).isEqualTo(CouponUnavailableReason.RULE_BETTER);
        assertThat(r.appliedFreeShippingUserCouponToken()).isNull();
    }

    @Test
    @DisplayName("P15 未达满额免运门槛时用免运券")
    void p15_belowRuleThresholdUsesCoupon() {
        PricingResult r = fx().lines(line("P1", ProductCategory.MAKANAN, 90_000, 1))
                .coupons(F10).rule(100_000, null).run();
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.COUPON);
        assertThat(r.payable()).isEqualTo(90_000);
        assertThat(r.freeShippingProgress()).isEqualTo(new FreeShippingProgress(100_000, 10_000));
    }

    @Test
    @DisplayName("P16 满额免运按券后实付判断")
    void p16_ruleUsesNetAmount() {
        PricingResult r = fx().lines(line("P1", ProductCategory.MAKANAN, 110_000, 1))
                .coupons(D20).rule(100_000, null).run();
        assertThat(r.discountCouponAmount()).isEqualTo(20_000);
        assertThat(r.goodsNetAmount()).isEqualTo(90_000);
        assertThat(r.shippingDiscount()).isZero();
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.NONE);
        assertThat(r.payable()).isEqualTo(100_000);
        assertThat(r.freeShippingProgress()).isEqualTo(new FreeShippingProgress(100_000, 10_000));
    }

    @Test
    @DisplayName("P17 实付恰好等于门槛")
    void p17_netEqualsThreshold() {
        PricingResult r = fx().lines(line("P1", ProductCategory.MAKANAN, 120_000, 1))
                .coupons(D20).rule(100_000, null).run();
        assertThat(r.goodsNetAmount()).isEqualTo(100_000);
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        assertThat(r.payable()).isEqualTo(100_000);
        assertThat(r.freeShippingProgress()).isEqualTo(new FreeShippingProgress(100_000, 0));
    }

    // ------------------------------------------------------------------ 满减券范围与门槛

    @Test
    @DisplayName("P18 满减门槛按范围内券前金额")
    void p18_minSpendByScopedAmount() {
        PricingResult r = fx().coupons(D30F).run();
        assertThat(r.discountCouponAmount()).isEqualTo(30_000);
        assertThat(r.payable()).isEqualTo(329_000);
    }

    @Test
    @DisplayName("P19 范围内未达门槛（总额达标不算）")
    void p19_scopedBelowMinSpend() {
        PricingResult r = fx().coupons(DSN).run();
        assertThat(eval(r, "dsn").reason()).isEqualTo(CouponUnavailableReason.BELOW_MIN_SPEND);
        assertThat(eval(r, "dsn").benefit()).isZero();
        assertThat(r.discountCouponAmount()).isZero();
        assertThat(r.payable()).isEqualTo(359_000);
    }

    @Test
    @DisplayName("P20 范围内无商品")
    void p20_noLineInScope() {
        PricingResult r = fx().coupons(DOB).run();
        assertThat(eval(r, "dob").reason()).isEqualTo(CouponUnavailableReason.OUT_OF_SCOPE);
        assertThat(r.payable()).isEqualTo(359_000);
    }

    @Test
    @DisplayName("P21 抵扣不超过适用商品金额")
    void p21_benefitCappedByEligible() {
        PricingResult r = fx().coupons(D60P2).run();
        assertThat(r.discountCouponAmount()).isEqualTo(49_000);
        assertThat(r.goodsNetAmount()).isEqualTo(300_000);
        assertThat(r.payable()).isEqualTo(310_000);
    }

    @Test
    @DisplayName("P22 指定商品命中")
    void p22_productScopeHit() {
        PricingResult r = fx().coupons(D20P1).run();
        assertThat(r.discountCouponAmount()).isEqualTo(20_000);
        assertThat(r.payable()).isEqualTo(339_000);
    }

    // ------------------------------------------------------------------ 免运券范围与门槛

    @Test
    @DisplayName("P23 免运券有范围、有商品不在范围")
    void p23_freeShippingScopeMiss() {
        PricingResult r = fx().coupons(F10F).run();
        assertThat(eval(r, "f10f").reason()).isEqualTo(CouponUnavailableReason.OUT_OF_SCOPE);
        assertThat(r.shippingDiscount()).isZero();
        assertThat(r.payable()).isEqualTo(359_000);
    }

    @Test
    @DisplayName("P24 免运券范围覆盖全部商品")
    void p24_freeShippingScopeCoversAll() {
        PricingResult r = fx().lines(L_FOOD).coupons(F10F).run();
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.COUPON);
        assertThat(r.payable()).isEqualTo(300_000);
    }

    @Test
    @DisplayName("P25 免运券门槛按券后实付（AUTO）")
    void p25_freeShippingMinSpendByNet() {
        PricingResult r = fx().coupons(D20, F10M).run();
        assertThat(r.goodsNetAmount()).isEqualTo(329_000);
        assertThat(eval(r, "f10m").reason()).isEqualTo(CouponUnavailableReason.BELOW_MIN_SPEND);
        assertThat(r.shippingDiscount()).isZero();
        assertThat(r.payable()).isEqualTo(339_000);
    }

    @Test
    @DisplayName("P26 同上手选不用满减")
    void p26_manualSkipDiscount() {
        Fx f = fx().coupons(D20, F10M);
        f.selection = CouponSelection.manual(null, "f10m");
        PricingResult r = f.run();
        assertThat(r.goodsNetAmount()).isEqualTo(349_000);
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.payable()).isEqualTo(349_000);
        assertThat(r.manualSelectionInvalid()).isFalse();
        assertThat(eval(r, "d20").usable()).isTrue();
        assertThat(eval(r, "d20").applied()).isFalse();
    }

    // ------------------------------------------------------------------ 有效期

    @Test
    @DisplayName("P27a 有效期边界：validUntil = NOW → EXPIRED")
    void p27a_validUntilEqualsNow() {
        PricingResult r = fx().coupons(withValidity(withToken(D20, "d20x"), FROM, NOW)).run();
        assertThat(eval(r, "d20x").usable()).isFalse();
        assertThat(eval(r, "d20x").reason()).isEqualTo(CouponUnavailableReason.EXPIRED);
        assertThat(r.discountCouponAmount()).isZero();
    }

    @Test
    @DisplayName("P27b 有效期边界：validUntil = NOW + 1s → 可用")
    void p27b_validUntilAfterNow() {
        PricingResult r = fx().coupons(withValidity(withToken(D20, "d20y"), FROM, NOW.plusSeconds(1))).run();
        assertThat(eval(r, "d20y").usable()).isTrue();
        assertThat(r.discountCouponAmount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("P28 尚未生效（防御）→ EXPIRED")
    void p28_notYetValid() {
        PricingResult r = fx().coupons(withValidity(withToken(D20, "d20z"),
                NOW.plus(Duration.ofHours(1)), UNTIL)).run();
        assertThat(eval(r, "d20z").usable()).isFalse();
        assertThat(eval(r, "d20z").reason()).isEqualTo(CouponUnavailableReason.EXPIRED);
    }

    // ------------------------------------------------------------------ AUTO / MANUAL

    @Test
    @DisplayName("P29 AUTO 取抵扣最大")
    void p29_autoPicksLargest() {
        PricingResult r = fx().coupons(D20, D30).run();
        assertThat(r.appliedDiscountUserCouponToken()).isEqualTo("d30");
        assertThat(eval(r, "d20").usable()).isTrue();
        assertThat(eval(r, "d20").applied()).isFalse();
        assertThat(eval(r, "d20").benefit()).isEqualTo(20_000);
        assertThat(r.payable()).isEqualTo(329_000);
    }

    @Test
    @DisplayName("P30 并列取先到期")
    void p30_tieEarlierExpiry() {
        PricingCoupon a = withValidity(withToken(D20, "d20a"), FROM, NOW.plus(Duration.ofDays(5)));
        PricingCoupon b = withValidity(withToken(D20, "d20b"), FROM, NOW.plus(Duration.ofDays(2)));
        PricingResult r = fx().coupons(a, b).run();
        assertThat(r.appliedDiscountUserCouponToken()).isEqualTo("d20b");
    }

    @Test
    @DisplayName("P31 并列同到期取 token 小")
    void p31_tieSmallerToken() {
        PricingResult r = fx().coupons(withToken(D20, "uc_b"), withToken(D20, "uc_a")).run();
        assertThat(r.appliedDiscountUserCouponToken()).isEqualTo("uc_a");
        assertThat(r.evaluations()).extracting(CouponEvaluation::userCouponToken)
                .as("顺序同入参").containsExactly("uc_b", "uc_a");
    }

    @Test
    @DisplayName("P32 AUTO 免运券取最大")
    void p32_autoPicksLargestFreeShipping() {
        PricingResult r = fx().ship(28_000, 15_000).rule(0, null).coupons(F10, F15).run();
        assertThat(r.appliedFreeShippingUserCouponToken()).isEqualTo("f15");
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(eval(r, "f10").usable()).isTrue();
        assertThat(eval(r, "f10").applied()).isFalse();
        assertThat(r.payable()).isEqualTo(362_000);
    }

    @Test
    @DisplayName("P33 AUTO 无可用券")
    void p33_autoNothingUsable() {
        PricingResult r = fx().coupons(DOB).run();
        assertThat(r.appliedDiscountUserCouponToken()).isNull();
        assertThat(r.appliedFreeShippingUserCouponToken()).isNull();
        assertThat(r.discountCouponAmount()).isZero();
        assertThat(r.payable()).isEqualTo(359_000);
    }

    @Test
    @DisplayName("P34 手选不用券")
    void p34_manualNone() {
        Fx f = fx().coupons(D20, F10);
        f.selection = CouponSelection.manual(null, null);
        PricingResult r = f.run();
        assertThat(r.discountCouponAmount()).isZero();
        assertThat(r.shippingDiscount()).isZero();
        assertThat(r.payable()).isEqualTo(359_000);
        assertThat(r.manualSelectionInvalid()).isFalse();
        assertThat(r.evaluations()).allMatch(e -> e.usable() && !e.applied());
    }

    @Test
    @DisplayName("P35 手选不可用券")
    void p35_manualUnusable() {
        Fx f = fx().coupons(DSN);
        f.selection = CouponSelection.manual("dsn", null);
        PricingResult r = f.run();
        assertThat(r.appliedDiscountUserCouponToken()).isNull();
        assertThat(r.manualSelectionInvalid()).isTrue();
        assertThat(r.payable()).isEqualTo(359_000);
    }

    @Test
    @DisplayName("P36 手选不存在的券")
    void p36_manualMissing() {
        Fx f = fx().coupons(D20);
        f.selection = CouponSelection.manual("nope", null);
        PricingResult r = f.run();
        assertThat(r.manualSelectionInvalid()).isTrue();
        assertThat(r.discountCouponAmount()).isZero();
    }

    @Test
    @DisplayName("P37 手选输给满额免运的免运券：不算无效")
    void p37_manualLosesToRule() {
        Fx f = p07().coupons(F12);
        f.selection = CouponSelection.manual(null, "f12");
        PricingResult r = f.run();
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        assertThat(r.shippingDiscount()).isEqualTo(15_000);
        assertThat(r.appliedFreeShippingUserCouponToken()).isNull();
        assertThat(r.manualSelectionInvalid()).isFalse();
    }

    // ------------------------------------------------------------------ 保价

    @Test
    @DisplayName("P38 保价取整（ceil）：300,000 / 100,001 / 329,000 / 1 / 0")
    void p38_insuranceCeil() {
        long[][] cases = {{300_000, 1_500}, {100_001, 501}, {329_000, 1_645}, {1, 1}};
        for (long[] c : cases) {
            Fx f = fx().lines(line("P1", ProductCategory.MAKANAN, c[0], 1));
            f.insurance = true;
            PricingResult r = f.run();
            assertThat(r.goodsNetAmount()).isEqualTo(c[0]);
            assertThat(r.insuranceFee()).as("实付 %d", c[0]).isEqualTo(c[1]);
        }
        Fx zero = fx().lines(line("P1", ProductCategory.MAKANAN, 50_000, 1)).coupons(D50);
        zero.insurance = true;
        PricingResult r = zero.run();
        assertThat(r.goodsNetAmount()).isZero();
        assertThat(r.insuranceFee()).isZero();
    }

    @Test
    @DisplayName("P39 保价不受运费减免影响")
    void p39_insuranceIgnoresShippingDiscount() {
        Fx f = p07();
        f.insurance = true;
        PricingResult r = f.run();
        assertThat(r.insuranceFee()).isEqualTo(1_000);
        assertThat(r.payable()).isEqualTo(214_000);
    }

    @Test
    @DisplayName("P40 快递不支持保价")
    void p40_insuranceNotApplicable() {
        Fx f = p01();
        f.insurance = false;
        assertThat(f.run().insuranceFee()).isZero();
    }

    // ------------------------------------------------------------------ PawCoin

    @Test
    @DisplayName("P41 余额为 0")
    void p41_zeroBalance() {
        Fx f = p01();
        f.balance = 0;
        PricingResult r = f.run();
        assertThat(r.coinAmount()).isZero();
        assertThat(r.cashAmount()).isEqualTo(329_000);
        assertThat(r.coinCapped()).isFalse();
    }

    @Test
    @DisplayName("P42 单笔上限为 0")
    void p42_zeroCap() {
        Fx f = p01();
        f.maxPerOrder = 0;
        PricingResult r = f.run();
        assertThat(r.coinAmount()).isZero();
        assertThat(r.coinCapped()).isTrue();
    }

    @Test
    @DisplayName("P43 电商 PawCoin 总开关关")
    void p43_coinDisabled() {
        Fx f = p01();
        f.coinEnabled = false;
        PricingResult r = f.run();
        assertThat(r.coinAmount()).isZero();
        assertThat(r.coinCapped()).isFalse();
    }

    @Test
    @DisplayName("P44 运费与保价不可用币抵扣")
    void p44_noShippingDeduction() {
        Fx f = p07();
        f.insurance = true;
        f.allowShippingDeduction = false;
        f.balance = 1_000_000;
        PricingResult r = f.run();
        assertThat(r.payable()).isEqualTo(214_000);
        assertThat(r.coinAmount()).isEqualTo(200_000);
        assertThat(r.cashAmount()).isEqualTo(14_000);
        assertThat(r.coinCapped()).isFalse();
    }

    @Test
    @DisplayName("P45 应付为 0")
    void p45_zeroPayable() {
        Fx f = fx().lines(line("P1", ProductCategory.MAKANAN, 50_000, 1)).coupons(D50, F10);
        f.insurance = true;
        PricingResult r = f.run();
        assertThat(r.discountCouponAmount()).isEqualTo(50_000);
        assertThat(r.goodsNetAmount()).isZero();
        assertThat(r.shippingDiscount()).isEqualTo(10_000);
        assertThat(r.insuranceFee()).isZero();
        assertThat(r.payable()).isZero();
        assertThat(r.coinAmount()).isZero();
        assertThat(r.cashAmount()).isZero();
    }

    // ------------------------------------------------------------------ 其它

    @Test
    @DisplayName("P46 运费未知（无地址 / 询价失败）")
    void p46_shippingUnknown() {
        Fx f = fx().coupons(D20, F10).rule(300_000, null);
        f.shipping = null;
        PricingResult r = f.run();
        assertThat(r.shippingKnown()).isFalse();
        assertThat(r.shippingFee()).isZero();
        assertThat(r.shippingDiscount()).isZero();
        assertThat(r.shippingDiscountSource()).isEqualTo(ShippingDiscountSource.NONE);
        CouponEvaluation f10 = eval(r, "f10");
        assertThat(f10.usable()).isTrue();
        assertThat(f10.benefit()).isZero();
        assertThat(f10.applied()).isFalse();
        assertThat(r.payable()).isEqualTo(329_000);
        assertThat(r.freeShippingProgress()).isEqualTo(new FreeShippingProgress(300_000, 0));
    }

    @Test
    @DisplayName("P47 门槛为 0 → 满额免运不生效")
    void p47_zeroThreshold() {
        PricingResult r = p07().rule(0, 20_000L).run();
        assertThat(r.shippingDiscount()).isZero();
        assertThat(r.payable()).isEqualTo(228_000);
        assertThat(r.freeShippingProgress()).isNull();
    }

    @Test
    @DisplayName("P48 已省不含 PawCoin")
    void p48_savedExcludesCoin() {
        PricingResult r = p01().run();
        assertThat(r.savedAmount()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("P49 贪心口径钉住（S-10）：302,000 用 5,000 券 → 应付 317,000")
    void p49_greedyPinned() {
        PricingResult r = fx().lines(line("P1", ProductCategory.MAKANAN, 302_000, 1)).coupons(D5)
                .ship(20_000, 20_000).rule(300_000, null).run();
        assertThat(r.appliedDiscountUserCouponToken()).isEqualTo("d5");
        assertThat(r.goodsNetAmount()).isEqualTo(297_000);
        assertThat(r.shippingDiscount()).isZero();
        assertThat(r.payable()).isEqualTo(317_000);
    }

    @Test
    @DisplayName("P50 免运减免不超过所选运费")
    void p50_discountCappedBySelectedFee() {
        PricingResult r = p01().ship(5_000, 5_000).run();
        assertThat(r.shippingDiscount()).isEqualTo(5_000);
        assertThat(r.payable()).isEqualTo(329_000);
    }

    @Test
    @DisplayName("P51 行单价不被改写（NFR7）")
    void p51_linesUntouched() {
        Fx f = p01();
        List<PricingLine> snapshot = List.copyOf(f.lines);
        PricingInput in = f.input();
        PricingResult r = ShopPricingCalculator.price(in);
        assertInvariants(r);
        assertThat(in.lines()).containsExactlyElementsOf(snapshot);
        assertThat(in.lines().get(0).unitPrice()).isEqualTo(150_000);
        assertThat(in.lines().get(1).unitPrice()).isEqualTo(49_000);
        assertThat(Arrays.stream(PricingResult.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(n -> n.toLowerCase().contains("unitprice"));
        assertThat(Arrays.stream(CouponEvaluation.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(n -> n.toLowerCase().contains("unitprice"));
    }

    @Test
    @DisplayName("P52 溢出抛 ArithmeticException")
    void p52_overflow() {
        Fx f = fx().lines(line("P1", ProductCategory.MAKANAN, Long.MAX_VALUE / 2, 3));
        assertThatThrownBy(() -> ShopPricingCalculator.price(f.input()))
                .isInstanceOf(ArithmeticException.class);
    }
}
