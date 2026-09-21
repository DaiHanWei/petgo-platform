package com.tailtopia.admin.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.settlement.dto.AdminSettlementRow;
import com.tailtopia.admin.settlement.dto.SettlementSummary;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.3.0 Story 8.5 · AC3）：月结摘要条三格的**数值**。
 *
 * <p>🔴 这是钱的数，而它是一个纯函数（入参就是列表那一份 rows，不碰 DB）—— 所以必须在 L0
 * 就钉死，不能只靠页面上「`data-sum="paidThisMonth"` 这个标记存在」那种断言：
 * 把判据从 {@code paidAt} 换回 {@code period}、把 WIB 换成系统时区、把 {@code isBefore}
 * 写反，那类断言全都照样绿。
 */
class AdminSettlementSummaryTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /** summary 不碰任何依赖，构造器传 null 即可 —— 让这条测试真的留在 L0（无库、无上下文）。 */
    private final AdminSettlementService svc = new AdminSettlementService(null, null, null, null);

    private AdminSettlementRow row(String status, long payout, Instant paidAt) {
        return new AdminSettlementRow(1L, 9L, "2026-05", 2, 100000L, payout, status, null,
                paidAt, Instant.parse("2026-05-01T00:00:00Z"));
    }

    private Instant thisMonthWib(int dayOfMonth) {
        return YearMonth.now(WIB).atDay(dayOfMonth).atStartOfDay(WIB).plusHours(9).toInstant();
    }

    @Test
    void pendingCountAndPayoutOnlyCoverSettlementsAwaitingPayout() {
        SettlementSummary s = svc.summary(List.of(
                row("PENDING_FINANCE", 60000L, null),
                row("PENDING_FINANCE", 40000L, null),
                row("PAID", 30000L, thisMonthWib(2)),
                row("ARCHIVED", 20000L, thisMonthWib(3))));

        assertThat(s.pendingCount()).isEqualTo(2);
        assertThat(s.pendingPayout()).as("只算待打款的，已打款的不能混进来").isEqualTo(100000L);
    }

    /**
     * 🔴 「本月已打款」判的是 <b>{@code paidAt} 落在本月</b>，不是 period 等于本月。
     *
     * <p>8 月的月结在 9 月初打款，财务问「这个月付出去多少」时它算 9 月。这条方向一旦写反，
     * 月初两天的数会与财务对不上，而页面上看不出任何异常。
     */
    @Test
    void paidThisMonthIsJudgedByPayoutTimeNotBySettlementPeriod() {
        Instant lastMonth = YearMonth.now(WIB).minusMonths(1).atDay(15).atStartOfDay(WIB).toInstant();
        SettlementSummary s = svc.summary(List.of(
                row("PAID", 30000L, thisMonthWib(1)),      // 本月 1 日打款 → 计入
                row("ARCHIVED", 20000L, thisMonthWib(28)), // 本月打款后归档 → 也计入
                row("PAID", 99999L, lastMonth),            // 上月打款 → 不计入
                row("PENDING_FINANCE", 88888L, null)));    // 还没打款 → 不计入

        assertThat(s.paidThisMonth()).isEqualTo(50000L);
    }

    /** 月初边界：本月 1 日 00:00（WIB）算本月，上月最后一刻不算。 */
    @Test
    void theMonthBoundaryIsWibMidnightOnTheFirst() {
        Instant firstMidnight = YearMonth.now(WIB).atDay(1).atStartOfDay(WIB).toInstant();
        SettlementSummary in = svc.summary(List.of(row("PAID", 10000L, firstMidnight)));
        SettlementSummary out = svc.summary(List.of(
                row("PAID", 10000L, firstMidnight.minusSeconds(1))));

        assertThat(in.paidThisMonth()).isEqualTo(10000L);
        assertThat(out.paidThisMonth()).as("上月最后一秒不属于本月").isZero();
    }

    @Test
    void anEmptyLedgerIsAllZerosNotNull() {
        SettlementSummary s = svc.summary(List.of());
        assertThat(s.pendingCount()).isZero();
        assertThat(s.pendingPayout()).isZero();
        assertThat(s.paidThisMonth()).isZero();
    }
}
