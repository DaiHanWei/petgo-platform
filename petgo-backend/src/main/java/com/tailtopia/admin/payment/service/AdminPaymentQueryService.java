package com.tailtopia.admin.payment.service;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.admin.payment.dto.AdminPaymentSummary;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.ArrayList;
import org.springframework.data.domain.PageImpl;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.dto.PaymentDisplayNo;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台支付记录通用查询（Story 9.6，AB-8E）。按用户跨类型（VET_CONSULT/PAWCOIN_TOPUP/AI_UNLOCK/ID_HD）
 * 只读查 {@code payment_intents}。无敏感 PII（gateway_meta 已脱敏，本查询不返 meta）。
 */
@Service
public class AdminPaymentQueryService {

    private final PaymentIntentRepository intents;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public AdminPaymentQueryService(PaymentIntentRepository intents) {
        this.intents = intents;
    }

    @Transactional(readOnly = true)
    public List<AdminPaymentRow> byUser(long userId) {
        return intents.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(AdminPaymentQueryService::toRow).toList();
    }

    /** 默认视图：全部支付意图按 created_at 倒序分页（跨用户跨类型）。 */
    @Transactional(readOnly = true)
    public Page<AdminPaymentRow> recent(int page, int size) {
        return intents.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(AdminPaymentQueryService::toRow);
    }


    // ——————————————————— 筛选 + 汇总（2026-08-28）———————————————————

    /**
     * 筛选条件。任一为 null / 空 = 不限。
     *
     * @param from 起（含），按 **WIB 当天 00:00** 换算；null 不限
     * @param to   止（**含当天**），按 WIB 次日 00:00 换算；null 不限
     */
    public record Filter(Long userId, PaymentPurpose purpose, PaymentStatus status,
            LocalDate from, LocalDate to) {

        public boolean isEmpty() {
            return userId == null && purpose == null && status == null && from == null && to == null;
        }
    }

    /** 后台一律按 WIB（雅加达）解释日期 —— 与其它后台页同一口径。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private List<Predicate> where(CriteriaBuilder cb, Root<PaymentIntent> r, Filter f) {
        List<Predicate> ps = new ArrayList<>();
        if (f.userId() != null) {
            ps.add(cb.equal(r.get("userId"), f.userId()));
        }
        if (f.purpose() != null) {
            ps.add(cb.equal(r.get("purpose"), f.purpose()));
        }
        if (f.status() != null) {
            ps.add(cb.equal(r.get("status"), f.status()));
        }
        if (f.from() != null) {
            ps.add(cb.greaterThanOrEqualTo(r.get("createdAt"),
                    f.from().atStartOfDay(WIB).toInstant()));
        }
        if (f.to() != null) {
            // ⚠️ **含当天**：上界取次日 00:00 且用 < ——
            //    写成 <= 当天 00:00 会让"起止选同一天"永远查不到东西。
            ps.add(cb.lessThan(r.get("createdAt"),
                    f.to().plusDays(1).atStartOfDay(WIB).toInstant()));
        }
        return ps;
    }

    /** 按筛选条件分页查（创建时间倒序）。 */
    @Transactional(readOnly = true)
    public Page<AdminPaymentRow> search(Filter f, int page, int size) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<PaymentIntent> cq = cb.createQuery(PaymentIntent.class);
        Root<PaymentIntent> r = cq.from(PaymentIntent.class);
        cq.where(where(cb, r, f).toArray(Predicate[]::new));
        cq.orderBy(cb.desc(r.get("createdAt")));
        List<AdminPaymentRow> rows = em.createQuery(cq)
                .setFirstResult(Math.max(page, 0) * size).setMaxResults(size)
                .getResultList().stream().map(AdminPaymentQueryService::toRow).toList();

        CriteriaQuery<Long> countQ = cb.createQuery(Long.class);
        Root<PaymentIntent> cr = countQ.from(PaymentIntent.class);
        countQ.select(cb.count(cr)).where(where(cb, cr, f).toArray(Predicate[]::new));
        long total = em.createQuery(countQ).getSingleResult();

        return new PageImpl<>(rows, PageRequest.of(Math.max(page, 0), size), total);
    }

    /**
     * 筛选结果的汇总（摆在表格上方）。
     *
     * <p>🔴 **不是 SUM(amount)**。那个数偏大且没人会察觉，三处失真见
     * {@link AdminPaymentSummary} 的类注释（未支付也计 / PawCoin 不是现金 / 混合支付只有现金段）。
     *
     * <p>🔴 汇总覆盖**整个筛选结果**，不是当前这一页 —— 一个只统计本页的"总收入"
     * 会随翻页变化，而运营会把它当成总数抄进周报。因此在 DB 里聚合，不在内存里加。
     */
    @Transactional(readOnly = true)
    public AdminPaymentSummary summarize(Filter f) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<PaymentIntent> r = cq.from(PaymentIntent.class);

        Predicate isPaid = cb.equal(r.get("status"), PaymentStatus.PAID);
        Expression<Long> paidCount = cb.sum(cb.<Long>selectCase()
                .when(isPaid, 1L).otherwise(0L));
        // 现金 = PAID 的 QRIS 全额 + PAID 的 MIXED 现金段。
        Expression<Long> cash = cb.sum(cb.<Long>selectCase()
                .when(cb.and(isPaid, cb.equal(r.get("channel"), PayChannel.QRIS)), r.<Long>get("amount"))
                .when(cb.and(isPaid, cb.equal(r.get("channel"), PayChannel.MIXED)),
                        cb.coalesce(r.<Long>get("cashAmount"), 0L))
                .otherwise(0L));
        // 金币抵扣 = PAID 的 PAWCOIN 全额 + PAID 的 MIXED 金币段。
        Expression<Long> coin = cb.sum(cb.<Long>selectCase()
                .when(cb.and(isPaid, cb.equal(r.get("channel"), PayChannel.PAWCOIN)), r.<Long>get("amount"))
                .when(cb.and(isPaid, cb.equal(r.get("channel"), PayChannel.MIXED)),
                        cb.coalesce(r.<Long>get("coinAmount"), 0L))
                .otherwise(0L));

        cq.multiselect(cb.count(r), paidCount, cash, coin,
                cb.countDistinct(r.get("currency")), cb.least(r.<String>get("currency")));
        cq.where(where(cb, r, f).toArray(Predicate[]::new));

        Object[] row = em.createQuery(cq).getSingleResult();
        long orders = num(row[0]);
        long paid = num(row[1]);
        long cashSum = num(row[2]);
        long coinSum = num(row[3]);
        long currencies = num(row[4]);
        String currency = currencies > 1 ? AdminPaymentSummary.MIXED_CURRENCY
                : (row[5] == null ? "IDR" : (String) row[5]);
        return new AdminPaymentSummary(orders, paid, cashSum, coinSum, currency);
    }

    /** 空结果时聚合列会是 null（SUM 无行 → NULL），一律当 0。 */
    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    /** 后台时间统一显示印尼时间（WIB，Asia/Jakarta，UTC+7）。 */
    private static final DateTimeFormatter WIB_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"));

    private static AdminPaymentRow toRow(PaymentIntent p) {
        String label = p.getCreatedAt() == null ? null : WIB_FMT.format(p.getCreatedAt()) + " WIB";
        return new AdminPaymentRow(p.getUserId(), p.getPublicToken(),
                PaymentDisplayNo.of(p), p.getPurpose().name(),
                p.getChannel().name(), p.getAmount(), p.getCurrency(), p.getStatus().name(),
                p.getCreatedAt(), label);
    }
}
