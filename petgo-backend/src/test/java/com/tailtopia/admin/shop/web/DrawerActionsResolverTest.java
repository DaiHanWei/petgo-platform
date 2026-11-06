package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.web.DrawerActionsResolver.Action;
import com.tailtopia.admin.shop.web.DrawerActionsResolver.Facts;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0（V1.4.2 shop-admin Story 6.1 · AC1）：吸底判定集中一处，表驱动。 */
class DrawerActionsResolverTest {

    @ParameterizedTest(name = "{0} fail={1} le={2} activeBs={3} activeAny={4} courier={5} → {6} unknown={7}")
    @CsvSource(nullValues = "-", delimiter = ';', value = {
            // H2：待发货、无运单 → Biteship 下单（+ 标记缺货）
            "PENDING_SHIPMENT; -; -; false; false; true; BITESHIP_CREATE FLAG_SHORTAGE; false",
            // 存量单（无快递编码）→ 手工运单
            "PENDING_SHIPMENT; -; -; false; false; false; MANUAL_FORM FLAG_SHORTAGE; false",
            // H3：已下 Biteship 运单 → 标记已发货
            "PENDING_SHIPMENT; -; -; true; true; true; MARK_SHIPPED FLAG_SHORTAGE; false",
            // H5：运单失败三选一；结果未知须先核实
            "PENDING_SHIPMENT; REJECTED:x; -; false; false; true; WAYBILL_RETRY MANUAL_FORM CONTACT_CANCEL FLAG_SHORTAGE; false",
            "PENDING_SHIPMENT; UNKNOWN_RESULT:timeout; -; false; false; true; WAYBILL_RETRY MANUAL_FORM CONTACT_CANCEL FLAG_SHORTAGE; true",
            // 运单失败但存量单无快递编码：不给重提
            "PENDING_SHIPMENT; REJECTED:x; -; false; false; false; MANUAL_FORM CONTACT_CANCEL FLAG_SHORTAGE; false",
            // H6：揽收失败（待发货 / 已发货）三选一，先取消旧运单
            "PENDING_SHIPMENT; -; PICKUP_FAILED; true; true; true; PICKUP_RETRY PICKUP_MANUAL CONTACT_CANCEL FLAG_SHORTAGE; false",
            "SHIPPED; -; PICKUP_FAILED; false; false; true; PICKUP_RETRY PICKUP_MANUAL CONTACT_CANCEL MARK_DELIVERED SHIPPED_INFO; false",
            // H4 / H13：已发货（Biteship 活动）→ 送达；手工单还可再登记包裹
            "SHIPPED; -; -; true; true; true; MARK_DELIVERED SHIPPED_INFO; false",
            "SHIPPED; -; -; false; true; false; MANUAL_FORM MARK_DELIVERED SHIPPED_INFO; false",
            "DELIVERED; -; -; false; false; true; SHIPPED_INFO; false",
            "COMPLETED; -; -; false; false; true; SHIPPED_INFO; false",
            "REFUNDING; REJECTED:x; -; false; false; true; ; false",
    })
    void table(ShopOrderStatus status, String failReason, LogisticsException le, boolean activeBs, boolean activeAny,
            boolean courier, String expected, boolean unknown) {
        var r = DrawerActionsResolver.resolve(new Facts(status, failReason, failReason != null, le, activeBs, activeAny,
                courier, false));
        Set<Action> want = expected == null || expected.isBlank() ? Set.of()
                : Arrays.stream(expected.trim().split("\\s+")).map(Action::valueOf).collect(Collectors.toSet());
        assertThat(r.actions()).isEqualTo(want);
        assertThat(r.unknownResult()).isEqualTo(unknown);
    }
}
