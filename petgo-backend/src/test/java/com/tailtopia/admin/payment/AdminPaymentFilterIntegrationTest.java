package com.tailtopia.admin.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.admin.payment.dto.AdminPaymentSummary;
import com.tailtopia.admin.payment.service.AdminPaymentQueryService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：支付记录的筛选与汇总（2026-08-28）。
 *
 * <p>🔴 这一组的核心不是「筛选能不能用」，而是**汇总那两个数不能算错**。
 * 一个偏大的「收入总数」摆在页面顶上，看着权威、没人会去核，而它会被抄进经营汇报。
 *
 * <p>⚠️ 本类**每次都带一个独立的 userId 做筛选条件**：这是一个共享库，
 * 不加这层隔离的话，别的用例留下的支付记录会混进汇总，断言时对时错。
 */
class AdminPaymentFilterIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminPaymentQueryService service;

    @Autowired
    private PaymentIntentRepository intents;

    @Autowired
    private JdbcTemplate jdbc;

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private long owner() {
        return newUser().getId();
    }

    private PaymentIntent paid(long userId, PaymentPurpose purpose, PayChannel channel, long amount) {
        PaymentIntent p = PaymentIntent.create(userId, purpose, channel, amount, "IDR",
                "tok-" + SEQ.incrementAndGet());
        p.markPaid(Map.of());
        return intents.save(p);
    }

    private PaymentIntent pending(long userId, PaymentPurpose purpose, long amount) {
        return intents.save(PaymentIntent.create(userId, purpose, PayChannel.QRIS, amount, "IDR",
                "tok-" + SEQ.incrementAndGet()));
    }

    /**
     * ⚠️ {@code created_at} 是 {@code updatable = false}（它是"写入时刻"，业务上不该被改），
     * 所以造历史时间必须走原生 UPDATE —— 改字段再 save 是**不会写回数据库**的。
     */
    private void backdate(long id, Instant at) {
        jdbc.update("update payment_intents set created_at = ? where id = ?",
                Timestamp.from(at), id);
    }

    // ——————————————————— 汇总口径 ———————————————————

    /**
     * 🔴 **「收入总数」不是 SUM(amount)**。这一条把三种会让它偏大的情况一次摆齐：
     * 未支付、PawCoin 抵扣、混合支付的金币段。
     *
     * <p>naive 的 SUM(amount) 会得到 10000+50000+30000+20000 = 110000，
     * 而真正进账的只有 QRIS 那 50000 加混合支付的现金段 12000 = 62000。
     * 差出来的 48000 全是"看着像收入其实不是"的钱。
     */
    @Test
    void cashIncomeExcludesUnpaidPawcoinAndTheCoinLegOfMixedPayments() {
        long u = owner();
        pending(u, PaymentPurpose.VET_CONSULT, 10000L);                       // 未支付
        paid(u, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 50000L);       // 真现金
        paid(u, PaymentPurpose.AI_UNLOCK, PayChannel.PAWCOIN, 30000L);        // 站内余额扣减
        PaymentIntent mixed = PaymentIntent.createMixed(u, PaymentPurpose.SHOP_ORDER, 20000L,
                8000L, 12000L, new java.math.BigDecimal("0.4"), "IDR",
                "tok-" + SEQ.incrementAndGet(), null);
        mixed.markPaid(Map.of());
        intents.save(mixed);

        AdminPaymentSummary s = service.summarize(
                new AdminPaymentQueryService.Filter(u, null, null, null, null));

        assertThat(s.orderCount()).as("订单数含未支付").isEqualTo(4);
        assertThat(s.paidCount()).isEqualTo(3);
        assertThat(s.cashIncome())
                .as("🔴 收入把未支付 / PawCoin / 混合支付的金币段算进去了 —— "
                        + "这个数会被抄进经营汇报")
                .isEqualTo(50000L + 12000L);
        assertThat(s.coinSpent())
                .as("金币抵扣要单列，否则运营拿现金收入去对订单金额总和会对不上、又找不到差额")
                .isEqualTo(30000L + 8000L);
        assertThat(s.cashIncome() + s.coinSpent())
                .as("现金 + 金币 = 已支付订单的金额合计（这条恒等式是运营对账的依据）")
                .isEqualTo(50000L + 30000L + 20000L);
    }

    /** 🛡 一条记录都没有时汇总是 0，不是 null（SUM 无行返回 NULL —— 直接用会 NPE）。 */
    @Test
    void summaryOfAnEmptyResultIsZeroNotNull() {
        AdminPaymentSummary s = service.summarize(
                new AdminPaymentQueryService.Filter(owner(), null, null, null, null));
        assertThat(s.orderCount()).isZero();
        assertThat(s.cashIncome()).isZero();
        assertThat(s.coinSpent()).isZero();
    }

    /**
     * 🔴 **汇总覆盖整个筛选结果，不是当前这一页。**
     *
     * <p>只统计本页的话，"总收入"会随翻页变化 —— 而运营会把它当成总数抄走。
     * 用超过一页的数据来验：分页每页 20，这里造 25 条。
     */
    @Test
    void summaryCoversTheWholeFilteredSetNotJustThePage() {
        long u = owner();
        for (int i = 0; i < 25; i++) {
            paid(u, PaymentPurpose.VET_CONSULT, PayChannel.QRIS, 1000L);
        }
        var filter = new AdminPaymentQueryService.Filter(u, null, null, null, null);

        assertThat(service.search(filter, 0, 20).getContent()).hasSize(20);
        assertThat(service.summarize(filter).cashIncome())
                .as("🔴 汇总只算了当前页 —— 翻一页数字就变，而它长得像总数")
                .isEqualTo(25_000L);
    }

    // ——————————————————— 筛选条件 ———————————————————

    @Test
    void purposeAndStatusFiltersNarrowBothTheListAndTheSummary() {
        long u = owner();
        paid(u, PaymentPurpose.VET_CONSULT, PayChannel.QRIS, 50000L);
        paid(u, PaymentPurpose.ID_HD, PayChannel.QRIS, 20000L);
        pending(u, PaymentPurpose.VET_CONSULT, 90000L);

        var byPurpose = new AdminPaymentQueryService.Filter(
                u, PaymentPurpose.VET_CONSULT, null, null, null);
        assertThat(service.search(byPurpose, 0, 20).getTotalElements()).isEqualTo(2);
        assertThat(service.summarize(byPurpose).cashIncome()).isEqualTo(50000L);

        var byStatus = new AdminPaymentQueryService.Filter(
                u, null, PaymentStatus.PENDING, null, null);
        assertThat(service.search(byStatus, 0, 20).getContent())
                .extracting(AdminPaymentRow::amount).containsExactly(90000L);
        assertThat(service.summarize(byStatus).cashIncome())
                .as("筛出来全是未支付 ⇒ 收入必须是 0，不能是 90000")
                .isZero();
    }

    /**
     * 🔴 **「止」含当天** —— 起止选同一天必须查得到当天的记录。
     *
     * <p>上界写成 {@code <= 当天 00:00} 的话，选"今天到今天"永远是空的，
     * 而运营会以为"今天没有订单"。
     */
    @Test
    void theEndDateIsInclusiveSoASingleDayRangeWorks() {
        long u = owner();
        LocalDate day = LocalDate.of(2026, 8, 20);
        PaymentIntent onDay = paid(u, PaymentPurpose.VET_CONSULT, PayChannel.QRIS, 50000L);
        backdate(onDay.getId(), day.atTime(23, 30).atZone(WIB).toInstant());
        PaymentIntent nextDay = paid(u, PaymentPurpose.VET_CONSULT, PayChannel.QRIS, 70000L);
        backdate(nextDay.getId(), day.plusDays(1).atTime(0, 30).atZone(WIB).toInstant());

        var sameDay = new AdminPaymentQueryService.Filter(u, null, null, day, day);
        List<AdminPaymentRow> rows = service.search(sameDay, 0, 20).getContent();

        assertThat(rows).extracting(AdminPaymentRow::amount)
                .as("🔴 起止同一天查不到当天的记录 ⇒ 运营会以为这天没有订单")
                .containsExactly(50000L);
        assertThat(service.summarize(sameDay).cashIncome()).isEqualTo(50000L);
    }

    /**
     * 🛡 时间段按 **WIB** 解释，不是 UTC。
     *
     * <p>一笔发生在 WIB 8/21 06:00 的支付，其 UTC 时刻是 8/20 23:00 ——
     * 若按 UTC 切天，它会被算进 8/20，整份日报错位一天。
     */
    @Test
    void theDateRangeIsReadAsJakartaTimeNotUtc() {
        long u = owner();
        PaymentIntent p = paid(u, PaymentPurpose.VET_CONSULT, PayChannel.QRIS, 50000L);
        backdate(p.getId(), LocalDate.of(2026, 8, 21).atTime(6, 0).atZone(WIB).toInstant());

        LocalDate d21 = LocalDate.of(2026, 8, 21);
        LocalDate d20 = LocalDate.of(2026, 8, 20);
        assertThat(service.search(new AdminPaymentQueryService.Filter(u, null, null, d21, d21), 0, 20)
                .getTotalElements()).as("WIB 的 8/21 06:00 就该算 8/21").isEqualTo(1);
        assertThat(service.search(new AdminPaymentQueryService.Filter(u, null, null, d20, d20), 0, 20)
                .getTotalElements()).as("🔴 按 UTC 切天会把它错算进 8/20，日报整体错位一天").isZero();
    }
}
