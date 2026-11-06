package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/** L0（V1.4.2 shop-admin Story 7.2 · AC11）：取消后到账只有「重新下单 / 退还到账款」，不提供恢复原订单。 */
class LatePaymentNoRestoreTest {

    @Test
    void onlyReorderAndRefundEndpoints() {
        var late = Arrays.stream(AdminShopOrderController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(PostMapping.class)).filter(java.util.Objects::nonNull)
                .flatMap(a -> Arrays.stream(a.value())).filter(p -> p.contains("/late/")).toList();
        assertThat(late).containsExactlyInAnyOrder("/admin/shop/order-list/{token}/late/reorder",
                "/admin/shop/order-list/{token}/late/refund/confirm", "/admin/shop/order-list/{token}/late/refund");
        assertThat(Arrays.stream(com.tailtopia.shop.order.service.LatePaymentService.class.getMethods())
                .map(Method::getName)).noneMatch(n -> n.toLowerCase().contains("restore"));
    }
}
