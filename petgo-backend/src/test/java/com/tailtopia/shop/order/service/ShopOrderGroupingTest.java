package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.order.service.ShopOrderListFixtures.NOW;
import static com.tailtopia.shop.order.service.ShopOrderListFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0（v1.4.2 shop Story 6.2 AC1.2 / AC2.2）：「SQL 条件表」（{@link ShopOrderGroup#matchesSqlCondition}，仓库查询的逐字镜像）
 * 与 3.3 内存判据（{@link ShopOrder#displayStatusAt} → {@link ShopOrderGroup#ofDisplayStatus}）对同一组样例归组一致；
 * 角标计数与分组一致。
 */
class ShopOrderGroupingTest {

    /** 8 状态 × {无窗, 窗内, 恰在截止, 已过窗}。 */
    private static List<ShopOrder> samples() {
        List<ShopOrder> out = new ArrayList<>();
        long id = 1;
        for (ShopOrderStatus s : ShopOrderStatus.values()) {
            for (Instant exp : Arrays.asList(null, NOW.plusSeconds(600), NOW, NOW.minusSeconds(60))) {
                out.add(order(id++, s, exp));
            }
        }
        return out;
    }

    @Test
    @DisplayName("每个样例恰好落在一个非 all 分组；SQL 条件与 displayStatus 判据归组一致")
    void sqlConditionMatchesDisplayStatusGrouping() {
        for (ShopOrder o : samples()) {
            ShopOrderGroup byMemory = ShopOrderGroup.ofDisplayStatus(o.displayStatusAt(NOW));
            List<ShopOrderGroup> bySql = Arrays.stream(ShopOrderGroup.values())
                    .filter(g -> g != ShopOrderGroup.ALL)
                    .filter(g -> g.matchesSqlCondition(o.getStatus(), o.getExpiresAt(), NOW))
                    .toList();
            assertThat(bySql).as("status=%s expiresAt=%s", o.getStatus(), o.getExpiresAt())
                    .containsExactly(byMemory);
            assertThat(ShopOrderGroup.ALL.matchesSqlCondition(o.getStatus(), o.getExpiresAt(), NOW)).isTrue();
        }
    }

    @Test
    @DisplayName("宽限期待支付（已过窗）→ 已取消组，不在待付款组；无窗的历史待付款单与恰在截止的单 → 待付款组")
    void gracePeriodGoesToCancelled() {
        assertThat(ShopOrderGroup.CANCELLED.matchesSqlCondition(ShopOrderStatus.PENDING_PAYMENT,
                NOW.minusSeconds(1), NOW)).isTrue();
        assertThat(ShopOrderGroup.PENDING_PAYMENT.matchesSqlCondition(ShopOrderStatus.PENDING_PAYMENT,
                NOW.minusSeconds(1), NOW)).isFalse();
        assertThat(ShopOrderGroup.PENDING_PAYMENT.matchesSqlCondition(ShopOrderStatus.PENDING_PAYMENT, null, NOW))
                .isTrue();
        assertThat(ShopOrderGroup.PENDING_PAYMENT.matchesSqlCondition(ShopOrderStatus.PENDING_PAYMENT, NOW, NOW))
                .isTrue();
        assertThat(order(1, ShopOrderStatus.PENDING_PAYMENT, NOW).displayStatusAt(NOW))
                .isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("角标：pendingPayment = 待付款组条数，toReceive = 待收货组条数（同一组样例）")
    void countsMatchGroups() {
        List<ShopOrder> all = samples();
        long pendingByGroup = all.stream().filter(o -> ShopOrderGroup.PENDING_PAYMENT
                .matchesSqlCondition(o.getStatus(), o.getExpiresAt(), NOW)).count();
        long toReceiveByGroup = all.stream().filter(o -> ShopOrderGroup.TO_RECEIVE
                .matchesSqlCondition(o.getStatus(), o.getExpiresAt(), NOW)).count();
        // 计数 SQL 的两个 CASE 条件（ShopOrderRepository#countMyShopOrderBadges）逐字镜像
        long pendingByCount = all.stream().filter(o -> o.getStatus() == ShopOrderStatus.PENDING_PAYMENT
                && (o.getExpiresAt() == null || !o.getExpiresAt().isBefore(NOW))).count();
        long toReceiveByCount = all.stream().filter(o -> o.getStatus() == ShopOrderStatus.SHIPPED
                || o.getStatus() == ShopOrderStatus.DELIVERED).count();
        assertThat(pendingByCount).isEqualTo(pendingByGroup).isEqualTo(3);
        assertThat(toReceiveByCount).isEqualTo(toReceiveByGroup).isEqualTo(8);
    }

    @Test
    @DisplayName("group 参数：缺省 / 空白 = all，大小写不敏感")
    void parseGroup() {
        assertThat(ShopOrderGroup.parse(null)).isEqualTo(ShopOrderGroup.ALL);
        assertThat(ShopOrderGroup.parse(" ")).isEqualTo(ShopOrderGroup.ALL);
        assertThat(ShopOrderGroup.parse("TO_RECEIVE")).isEqualTo(ShopOrderGroup.TO_RECEIVE);
        assertThat(ShopOrderGroup.parse("cancelled")).isEqualTo(ShopOrderGroup.CANCELLED);
    }
}
