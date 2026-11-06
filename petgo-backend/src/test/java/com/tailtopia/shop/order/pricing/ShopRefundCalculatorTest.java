package com.tailtopia.shop.order.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.order.pricing.RefundOrderSnapshot.RefundLine;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/**
 * L0：v1.4.2 shop Story 4.1 AC2 —— 取消退款计算器表驱动（AC2.8 全部 22 例，数值取自 story 已核算表）+ AC2.9 不变式。
 *
 * <p>基准订单 O1：A 行 100,000 × 2（适用满减）、B 行 50,000 × 1（不适用）、C 行 30,000 × 3（适用）→ G = 340,000、
 * E = 290,000；D = 25,000；F = 28,000、S = 15,000；I = 1,575；T = 329,575；C = 100,000。
 */
class ShopRefundCalculatorTest {

    static final long A = 1L;
    static final long B = 2L;
    static final long C_LINE = 3L;

    static RefundOrderSnapshot o1() {
        return new RefundOrderSnapshot(329_575L, 100_000L, 0L, 0L, 340_000L, 25_000L, 28_000L, 15_000L, 1_575L,
                true, List.of(
                        new RefundLine(A, 100_000L, 2, 0, true),
                        new RefundLine(B, 50_000L, 1, 0, false),
                        new RefundLine(C_LINE, 30_000L, 3, 0, true)));
    }

    /** O2 = O1 不保价：I = 0、T = 328,000。 */
    static RefundOrderSnapshot o2() {
        RefundOrderSnapshot o = o1();
        return new RefundOrderSnapshot(328_000L, 100_000L, 0L, 0L, o.goods(), o.discount(), o.shippingFee(),
                o.shippingDiscount(), 0L, false, o.lines());
    }

    /** 把一次退款结果落到快照上（RT / RC 累加；LINE 加该行件数，WHOLE 各行补齐；COMPENSATION 不动件数）。 */
    static RefundOrderSnapshot apply(RefundOrderSnapshot s, RefundQuote q) {
        List<RefundLine> lines = new ArrayList<>();
        for (RefundLine l : s.lines()) {
            int refunded = l.refundedQty();
            if (q.scope() == RefundScope.LINE && q.lineId() == l.lineId()) {
                refunded += q.qty();
            } else if (q.scope() == RefundScope.WHOLE) {
                refunded = l.qty();
            }
            lines.add(new RefundLine(l.lineId(), l.unitPrice(), l.qty(), refunded, l.discountEligible()));
        }
        return new RefundOrderSnapshot(s.total(), s.coin(), s.refundedTotal() + q.totalRefund(),
                s.refundedCoin() + q.coinRefund(), s.goods(), s.discount(), s.shippingFee(), s.shippingDiscount(),
                s.insuranceFee(), s.insuranceSelected(), lines);
    }

    static RefundOrderSnapshot afterFirstA() {
        RefundOrderSnapshot s = o1();
        return apply(s, ShopRefundCalculator.partial(s, A, 1, CancelReason.OUT_OF_STOCK));
    }

    /** 例 4 前 4 次：A×1、A×1、B×1、C×2。 */
    static RefundOrderSnapshot afterFourPartials() {
        RefundOrderSnapshot s = o1();
        s = apply(s, ShopRefundCalculator.partial(s, A, 1, CancelReason.OUT_OF_STOCK));
        s = apply(s, ShopRefundCalculator.partial(s, A, 1, CancelReason.OUT_OF_STOCK));
        s = apply(s, ShopRefundCalculator.partial(s, B, 1, CancelReason.OUT_OF_STOCK));
        s = apply(s, ShopRefundCalculator.partial(s, C_LINE, 2, CancelReason.OUT_OF_STOCK));
        return s;
    }

    record Case(String name, RefundOrderSnapshot snapshot, Function<RefundOrderSnapshot, RefundQuote> op,
            long total, Long coin, Long cash) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        RefundOrderSnapshot pure = new RefundOrderSnapshot(150_000L, 0L, 0L, 0L, 150_000L, 0L, 0L, 0L, 0L, false,
                List.of(new RefundLine(1L, 100_000L, 1, 0, false), new RefundLine(2L, 50_000L, 1, 0, false)));
        RefundOrderSnapshot coinOnly = new RefundOrderSnapshot(120_000L, 120_000L, 0L, 0L, 110_000L, 0L, 10_000L,
                0L, 0L, false, List.of(new RefundLine(1L, 110_000L, 1, 0, false)));
        RefundOrderSnapshot tiny = new RefundOrderSnapshot(3L, 1L, 0L, 0L, 3L, 0L, 0L, 0L, 0L, false,
                List.of(new RefundLine(1L, 1L, 3, 0, false)));
        RefundOrderSnapshot threeEligible = new RefundOrderSnapshot(20_000L, 0L, 0L, 0L, 30_000L, 10_000L, 0L, 0L,
                0L, false, List.of(new RefundLine(1L, 10_000L, 1, 0, true), new RefundLine(2L, 10_000L, 1, 0, true),
                        new RefundLine(3L, 10_000L, 1, 0, true)));
        RefundOrderSnapshot legacy = new RefundOrderSnapshot(220_000L, 50_000L, 0L, 0L, 200_000L, 0L, 20_000L, 0L,
                0L, false, List.of(new RefundLine(1L, 80_000L, 1, 0, false), new RefundLine(2L, 120_000L, 1, 0, false)));
        return Stream.of(
                new Case("#1 部分件数（适用行）A×1", o1(),
                        s -> ShopRefundCalculator.partial(s, A, 1, CancelReason.OUT_OF_STOCK), 91_380L, 27_726L, 63_654L),
                new Case("#2 不适用行无分摊 B×1", o1(),
                        s -> ShopRefundCalculator.partial(s, B, 1, CancelReason.OUT_OF_STOCK), 50_000L, 15_171L, 34_829L),
                new Case("#3 一行取消部分件数 C×2", o1(),
                        s -> ShopRefundCalculator.partial(s, C_LINE, 2, CancelReason.OUT_OF_STOCK),
                        54_828L, 16_635L, 38_193L),
                new Case("#4 最后一件 C×1 按整单", afterFourPartials(),
                        s -> ShopRefundCalculator.partial(s, C_LINE, 1, CancelReason.OUT_OF_STOCK),
                        41_987L, 12_742L, 29_245L),
                new Case("#5 平台原因整单", o1(),
                        s -> ShopRefundCalculator.whole(s, CancelReason.OUT_OF_STOCK, null), 329_575L, 100_000L, 229_575L),
                new Case("#6 平台原因整单减去已退", afterFirstA(),
                        s -> ShopRefundCalculator.whole(s, CancelReason.OUT_OF_STOCK, null), 238_195L, 72_274L, 165_921L),
                new Case("#7 用户原因退回", o1(),
                        s -> ShopRefundCalculator.whole(s, CancelReason.USER_FAULT_RETURNED, null),
                        315_000L, 95_577L, 219_423L),
                new Case("#8 用户原因退回减去已退", afterFirstA(),
                        s -> ShopRefundCalculator.whole(s, CancelReason.USER_FAULT_RETURNED, null),
                        223_620L, 67_851L, 155_769L),
                new Case("#9 丢件破损·已保价", o1(),
                        s -> ShopRefundCalculator.whole(s, CancelReason.LOST_DAMAGED, null), 315_000L, 95_577L, 219_423L),
                new Case("#10 丢件破损·未保价·协商额", o2(),
                        s -> ShopRefundCalculator.whole(s, CancelReason.LOST_DAMAGED, 120_000L),
                        120_000L, 36_585L, 83_415L),
                new Case("#12 S-9 签收后破损赔付（已保价）", o1(),
                        s -> ShopRefundCalculator.compensation(s, null), 315_000L, 95_577L, 219_423L),
                new Case("#13 币取整零头入现金", tiny,
                        s -> ShopRefundCalculator.partial(s, 1L, 1, CancelReason.OUT_OF_STOCK), 1L, 0L, 1L),
                new Case("#14 满减分摊向下取整", threeEligible,
                        s -> ShopRefundCalculator.partial(s, 1L, 1, CancelReason.OUT_OF_STOCK), 6_667L, 0L, 6_667L),
                new Case("#15 纯 QRIS 单·部分", pure,
                        s -> ShopRefundCalculator.partial(s, 1L, 1, CancelReason.OUT_OF_STOCK), 100_000L, 0L, 100_000L),
                new Case("#15 纯 QRIS 单·整单", pure,
                        s -> ShopRefundCalculator.whole(s, CancelReason.OUT_OF_STOCK, null), 150_000L, 0L, 150_000L),
                new Case("#16 纯币单整单", coinOnly,
                        s -> ShopRefundCalculator.whole(s, CancelReason.OUT_OF_STOCK, null), 120_000L, 120_000L, 0L),
                new Case("#21 迟到款", o1(), s -> ShopRefundCalculator.latePayment(229_575L), 229_575L, 0L, 229_575L),
                new Case("#22 存量订单（D = 0、I = 0、无适用行）部分取消", legacy,
                        s -> ShopRefundCalculator.partial(s, 1L, 1, CancelReason.OUT_OF_STOCK),
                        80_000L, 80_000L * 50_000L / 220_000L, 80_000L - 80_000L * 50_000L / 220_000L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("AC2.8 金额表 + AC2.9 不变式")
    void amounts(Case c) {
        RefundQuote q = c.op().apply(c.snapshot());
        assertThat(q.totalRefund()).as("total").isEqualTo(c.total());
        if (c.coin() != null) {
            assertThat(q.coinRefund()).as("coin").isEqualTo(c.coin());
        }
        if (c.cash() != null) {
            assertThat(q.cashRefund()).as("cash").isEqualTo(c.cash());
        }
        assertInvariants(c.snapshot(), q);
    }

    static void assertInvariants(RefundOrderSnapshot s, RefundQuote q) {
        assertThat(q.coinRefund() + q.cashRefund()).as("coin + cash = total").isEqualTo(q.totalRefund());
        assertThat(q.goodsRefund() + q.shippingRefund() + q.insuranceRefund()).as("三段 = total")
                .isEqualTo(q.totalRefund());
        assertThat(q.coinRefund()).as("coin ≥ 0").isGreaterThanOrEqualTo(0L);
        assertThat(q.cashRefund()).as("cash ≥ 0").isGreaterThanOrEqualTo(0L);
        if (q.reason() != CancelReason.LATE_PAYMENT) {
            assertThat(s.refundedTotal() + q.totalRefund()).as("RT + total ≤ T").isLessThanOrEqualTo(s.total());
            assertThat(s.refundedCoin() + q.coinRefund()).as("RC + coin ≤ C").isLessThanOrEqualTo(s.coin());
        }
    }

    @Test
    @DisplayName("#1 满减分摊 = ⌊25,000 × 100,000 / 290,000⌋ = 8,620；LINE 不退运费保价")
    void case1Share() {
        RefundQuote q = ShopRefundCalculator.partial(o1(), A, 1, CancelReason.OUT_OF_STOCK);
        assertThat(o1().eligibleAmount()).isEqualTo(290_000L);
        assertThat(100_000L - q.totalRefund()).isEqualTo(8_620L);
        assertThat(q.scope()).isEqualTo(RefundScope.LINE);
        assertThat(q.lineId()).isEqualTo(A);
        assertThat(q.qty()).isEqualTo(1);
        assertThat(q.shippingRefund()).isZero();
        assertThat(q.insuranceRefund()).isZero();
        assertThat(q.becameWhole()).isFalse();
    }

    @Test
    @DisplayName("#3 C×2 满减分摊 = 5,172")
    void case3Share() {
        RefundQuote q = ShopRefundCalculator.partial(o1(), C_LINE, 2, CancelReason.OUT_OF_STOCK);
        assertThat(60_000L - q.totalRefund()).isEqualTo(5_172L);
    }

    @Test
    @DisplayName("#4 前 4 次累计 RT = 287,588、RC = 87,258；最后一件 becameWhole，三段 27,412 / 13,000 / 1,575；五次合计 = T、币合计 = C")
    void case4LastPieceBecomesWhole() {
        RefundOrderSnapshot s = afterFourPartials();
        assertThat(s.refundedTotal()).isEqualTo(287_588L);
        assertThat(s.refundedCoin()).isEqualTo(87_258L);

        RefundQuote last = ShopRefundCalculator.partial(s, C_LINE, 1, CancelReason.OUT_OF_STOCK);
        assertThat(last.becameWhole()).isTrue();
        assertThat(last.scope()).isEqualTo(RefundScope.WHOLE);
        assertThat(last.lineId()).isNull();
        assertThat(last.goodsRefund()).isEqualTo(27_412L);
        assertThat(last.shippingRefund()).isEqualTo(13_000L);
        assertThat(last.insuranceRefund()).isEqualTo(1_575L);

        RefundOrderSnapshot end = apply(s, last);
        assertThat(end.refundedTotal()).isEqualTo(329_575L);
        assertThat(end.refundedCoin()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("#5 平台原因整单：goods 315,000 / 运费 13,000 / 保价 1,575")
    void case5Breakdown() {
        RefundQuote q = ShopRefundCalculator.whole(o1(), CancelReason.OUT_OF_STOCK, null);
        assertThat(q.goodsRefund()).isEqualTo(315_000L);
        assertThat(q.shippingRefund()).isEqualTo(13_000L);
        assertThat(q.insuranceRefund()).isEqualTo(1_575L);
        assertThat(q.scope()).isEqualTo(RefundScope.WHOLE);
    }

    @Test
    @DisplayName("#7 / #9 用户原因 / 丢件破损：不退运费与保价（goods = total）")
    void case7NoShipping() {
        for (CancelReason r : List.of(CancelReason.USER_FAULT_RETURNED, CancelReason.LOST_DAMAGED)) {
            RefundQuote q = ShopRefundCalculator.whole(o1(), r, null);
            assertThat(q.goodsRefund()).isEqualTo(q.totalRefund());
            assertThat(q.shippingRefund()).isZero();
            assertThat(q.insuranceRefund()).isZero();
        }
    }

    @Test
    @DisplayName("#8 用户原因退回减去已退：coin 未触上限 72,274")
    void case8CoinBelowCap() {
        RefundOrderSnapshot s = afterFirstA();
        assertThat(s.remainingCoin()).isEqualTo(72_274L);
        assertThat(ShopRefundCalculator.whole(s, CancelReason.USER_FAULT_RETURNED, null).coinRefund())
                .isLessThan(72_274L);
    }

    @Test
    @DisplayName("#11 协商额超商品实付 → 422；未保价缺协商额 / 负数 → 422")
    void case11NegotiatedTooHigh() {
        assertThatThrownBy(() -> ShopRefundCalculator.whole(o2(), CancelReason.LOST_DAMAGED, 315_001L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> ShopRefundCalculator.whole(o2(), CancelReason.LOST_DAMAGED, null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> ShopRefundCalculator.compensation(o2(), -1L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(ShopRefundCalculator.whole(o2(), CancelReason.LOST_DAMAGED, 315_000L).totalRefund())
                .isEqualTo(315_000L);
    }

    @Test
    @DisplayName("#12 S-9：scope = COMPENSATION、reason = LOST_DAMAGED，未保价同样走协商额")
    void case12Compensation() {
        RefundQuote q = ShopRefundCalculator.compensation(o1(), null);
        assertThat(q.scope()).isEqualTo(RefundScope.COMPENSATION);
        assertThat(q.reason()).isEqualTo(CancelReason.LOST_DAMAGED);
        assertThat(q.lineId()).isNull();
        assertThat(ShopRefundCalculator.compensation(o2(), 120_000L).totalRefund()).isEqualTo(120_000L);
    }

    @Test
    @DisplayName("#14 三行各 10,000 均适用、D = 10,000：取消一行分摊 3,333")
    void case14FloorShare() {
        RefundOrderSnapshot s = new RefundOrderSnapshot(20_000L, 0L, 0L, 0L, 30_000L, 10_000L, 0L, 0L, 0L, false,
                List.of(new RefundLine(1L, 10_000L, 1, 0, true), new RefundLine(2L, 10_000L, 1, 0, true),
                        new RefundLine(3L, 10_000L, 1, 0, true)));
        assertThat(10_000L - ShopRefundCalculator.partial(s, 1L, 1, CancelReason.OUT_OF_STOCK).totalRefund())
                .isEqualTo(3_333L);
    }

    @Test
    @DisplayName("#17 超量取消：A 已退 2 件再取消 1 件 → 409；0 件 → 409")
    void case17OverCancel() {
        RefundOrderSnapshot s = o1();
        s = apply(s, ShopRefundCalculator.partial(s, A, 2, CancelReason.OUT_OF_STOCK));
        RefundOrderSnapshot after = s;
        assertThatThrownBy(() -> ShopRefundCalculator.partial(after, A, 1, CancelReason.OUT_OF_STOCK))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> ShopRefundCalculator.partial(o1(), B, 0, CancelReason.OUT_OF_STOCK))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("#18 已无可退（RT = T）整单 → 409")
    void case18NothingLeft() {
        RefundOrderSnapshot s = o1();
        RefundOrderSnapshot done = apply(s, ShopRefundCalculator.whole(s, CancelReason.OUT_OF_STOCK, null));
        assertThat(done.refundedTotal()).isEqualTo(done.total());
        assertThatThrownBy(() -> ShopRefundCalculator.whole(done, CancelReason.OUT_OF_STOCK, null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> ShopRefundCalculator.compensation(done, null))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("#19 补偿：现金段 165,921 × 10% = 16,592；cap 10,000 → 10,000；rate ≤ 0 / 基数 ≤ 0 → 0")
    void case19Premium() {
        assertThat(ShopRefundCalculator.compensationPremium(165_921L, 10, 0L)).isEqualTo(16_592L);
        assertThat(ShopRefundCalculator.compensationPremium(165_921L, 10, 10_000L)).isEqualTo(10_000L);
        assertThat(ShopRefundCalculator.compensationPremium(165_921L, 0, 0L)).isZero();
        assertThat(ShopRefundCalculator.compensationPremium(0L, 10, 0L)).isZero();
    }

    @Test
    @DisplayName("#20 渠道费：165,921 → BCA 165,921 / OVO 163,421 / GOPAY 163,421；现金 2,000 选 OVO → 422 refund-below-payout-fee")
    void case20PayoutFee() {
        assertThat(ShopRefundCalculator.payoutNet(165_921L, PayoutChannel.BCA)).isEqualTo(165_921L);
        assertThat(ShopRefundCalculator.payoutNet(165_921L, PayoutChannel.OVO)).isEqualTo(163_421L);
        assertThat(ShopRefundCalculator.payoutNet(165_921L, PayoutChannel.GOPAY)).isEqualTo(163_421L);
        assertThatThrownBy(() -> ShopRefundCalculator.payoutNet(2_000L, PayoutChannel.OVO))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.REFUND_BELOW_PAYOUT_FEE);
                });
    }

    @Test
    @DisplayName("#21 迟到款：WHOLE / LATE_PAYMENT，全现金；金额 ≤ 0 → 422")
    void case21LatePayment() {
        RefundQuote q = ShopRefundCalculator.latePayment(229_575L);
        assertThat(q.scope()).isEqualTo(RefundScope.WHOLE);
        assertThat(q.reason()).isEqualTo(CancelReason.LATE_PAYMENT);
        assertThat(q.goodsRefund()).isEqualTo(229_575L);
        assertThatThrownBy(() -> ShopRefundCalculator.latePayment(0L)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("部分取消只接受平台全额类原因；整单不接受未付款类原因")
    void reasonGuards() {
        assertThatThrownBy(() -> ShopRefundCalculator.partial(o1(), A, 1, CancelReason.USER_FAULT_RETURNED))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> ShopRefundCalculator.partial(o1(), A, 1, CancelReason.LOST_DAMAGED))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> ShopRefundCalculator.whole(o1(), CancelReason.PAYMENT_TIMEOUT, null))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> ShopRefundCalculator.whole(o1(), CancelReason.LATE_PAYMENT, null))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("乘法溢出即抛（Math.multiplyExact），不静默截断")
    void overflowThrows() {
        RefundOrderSnapshot huge = new RefundOrderSnapshot(Long.MAX_VALUE, Long.MAX_VALUE / 2, 0L, 0L,
                Long.MAX_VALUE, 0L, 0L, 0L, 0L, false,
                List.of(new RefundLine(1L, Long.MAX_VALUE / 4, 2, 0, false), new RefundLine(2L, 1L, 1, 0, false)));
        assertThatThrownBy(() -> ShopRefundCalculator.partial(huge, 1L, 1, CancelReason.OUT_OF_STOCK))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("复审修复：近纯币单（T = 100,000、C = 99,999）4 件逐件部分取消 → 币合计 = C、现金合计 = T − C，每步 coin + cash = total")
    void nearPureCoinPartialsKeepCoinSegment() {
        RefundOrderSnapshot s = new RefundOrderSnapshot(100_000L, 99_999L, 0L, 0L, 100_000L, 0L, 0L, 0L, 0L, false,
                List.of(new RefundLine(1L, 25_000L, 1, 0, false), new RefundLine(2L, 25_000L, 1, 0, false),
                        new RefundLine(3L, 25_000L, 1, 0, false), new RefundLine(4L, 25_000L, 1, 0, false)));
        long coinSum = 0L;
        long cashSum = 0L;
        for (long id = 1L; id <= 4L; id++) {
            RefundQuote q = ShopRefundCalculator.partial(s, id, 1, CancelReason.OUT_OF_STOCK);
            assertInvariants(s, q);
            assertThat(q.becameWhole()).isEqualTo(id == 4L);
            assertThat(cashSum + q.cashRefund()).as("累计现金不超过实付现金").isLessThanOrEqualTo(1L);
            coinSum += q.coinRefund();
            cashSum += q.cashRefund();
            s = apply(s, q);
        }
        assertThat(coinSum).isEqualTo(99_999L);
        assertThat(cashSum).isEqualTo(1L);
        assertThat(s.refundedTotal()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("复审修复：按原因算出 0 元 → 409 nothing-to-refund（不建 0 元退款单）")
    void zeroTotalIsNothingToRefund() {
        assertThatThrownBy(() -> ShopRefundCalculator.whole(o2(), CancelReason.LOST_DAMAGED, 0L))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.NOTHING_TO_REFUND);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.orderRefund.nothingToRefund");
                });
    }

    // ---------- V1.4.2 shop-admin Story 5.1：UI 稿 D3 ~ D7 示例（T = 146.500、C = 50.000）----------
    // 注：UI 稿 D4 / D5 的币段写作 46.690 / 34.130（四舍五入的示意值）；本计算器按拍板口径「币段 ⌊本次 × C / T⌋」
    //     向下取整，为 46.689 / 34.129，零头入现金。D6 / D7 的数字与稿一致。

    /** D3 / D4 / D5：商品 140.000 − 满减 3.200 = 实付 136.800；运费 9.000（无减免）；保价 700。 */
    private static RefundOrderSnapshot uiOrder(boolean insured) {
        return new RefundOrderSnapshot(146_500L, 50_000L, 0L, 0L, 140_000L, 3_200L, 9_000L, 0L, 700L, insured,
                List.of(new RefundLine(A, 100_000L, 1, 0, true), new RefundLine(B, 40_000L, 1, 0, true)));
    }

    @Test
    void uiD3PlatformFullRefund() {
        RefundQuote q = ShopRefundCalculator.whole(uiOrder(true), CancelReason.OUT_OF_STOCK, null);
        assertThat(q.totalRefund()).isEqualTo(146_500L);
        assertThat(q.coinRefund()).isEqualTo(50_000L);
        assertThat(q.cashRefund()).isEqualTo(96_500L);
        assertThat(q.shippingRefund()).isEqualTo(9_000L);
        assertThat(q.insuranceRefund()).isEqualTo(700L);
    }

    @Test
    void uiD4UserFaultLessShippingAndInsurance() {
        RefundQuote q = ShopRefundCalculator.whole(uiOrder(true), CancelReason.USER_FAULT_RETURNED, null);
        assertThat(q.totalRefund()).isEqualTo(136_800L);
        assertThat(q.coinRefund()).isEqualTo(46_689L);
        assertThat(q.cashRefund()).isEqualTo(90_111L);
        assertThat(q.shippingRefund()).isZero();
        assertThat(q.insuranceRefund()).isZero();
    }

    @Test
    void uiD5LostDamagedNegotiatedWithinGoodsPaid() {
        RefundQuote q = ShopRefundCalculator.whole(uiOrder(false), CancelReason.LOST_DAMAGED, 100_000L);
        assertThat(q.totalRefund()).isEqualTo(100_000L);
        assertThat(q.coinRefund()).isEqualTo(34_129L);
        assertThat(q.cashRefund()).isEqualTo(65_871L);
        assertThatThrownBy(() -> ShopRefundCalculator.whole(uiOrder(false), CancelReason.LOST_DAMAGED, 136_801L))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        // 已保价：固定为商品实付
        assertThat(ShopRefundCalculator.whole(uiOrder(true), CancelReason.LOST_DAMAGED, null).totalRefund())
                .isEqualTo(136_800L);
    }

    /** D6 / D7：商品 140.000 − 满减 7.000（两行都适用），运费 12.800，保价 700；Whiskas 40.000 分摊满减 2.000 → 实付 38.000。 */
    @Test
    void uiD6PartialAndD7LastLineBecomesWhole() {
        RefundOrderSnapshot s = new RefundOrderSnapshot(146_500L, 50_000L, 0L, 0L, 140_000L, 7_000L, 12_800L, 0L,
                700L, true, List.of(new RefundLine(A, 100_000L, 1, 0, true), new RefundLine(B, 40_000L, 1, 0, true)));
        RefundQuote d6 = ShopRefundCalculator.partial(s, B, 1, CancelReason.OUT_OF_STOCK);
        assertThat(d6.becameWhole()).isFalse();
        assertThat(d6.totalRefund()).isEqualTo(38_000L);
        assertThat(d6.coinRefund()).isEqualTo(12_969L);
        assertThat(d6.cashRefund()).isEqualTo(25_031L);

        RefundOrderSnapshot after = new RefundOrderSnapshot(146_500L, 50_000L, 38_000L, 12_969L, 140_000L, 7_000L,
                12_800L, 0L, 700L, true, List.of(new RefundLine(A, 100_000L, 1, 0, true),
                        new RefundLine(B, 40_000L, 1, 1, true)));
        RefundQuote d7 = ShopRefundCalculator.partial(after, A, 1, CancelReason.OUT_OF_STOCK);
        assertThat(d7.becameWhole()).isTrue();
        assertThat(d7.totalRefund()).isEqualTo(108_500L);
        assertThat(d7.coinRefund()).isEqualTo(37_031L);
        assertThat(d7.cashRefund()).isEqualTo(71_469L);
    }
}
