package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 6.2）：退回入库表单参数解析、索赔结案的结果 / 金额校验。 */
class LogisticsDispositionParamsTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void inboundParamsSkipBlankAndZeroAndIgnoreOtherFields() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("qty_11", "2");
        p.put("qty_12", "0");
        p.put("qty_13", " ");
        p.put("_csrf", "x");
        assertThat(AdminShopOrderController.parseInboundQty(p)).containsExactly(Map.entry(11L, 2));
    }

    @Test
    void inboundParamsRejectNegativeGarbageAndAllEmpty() {
        assertThatThrownBy(() -> AdminShopOrderController.parseInboundQty(Map.of("qty_11", "-1")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.v142.inbound.qtyInvalid");
        assertThatThrownBy(() -> AdminShopOrderController.parseInboundQty(Map.of("qty_11", "1.5")))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> AdminShopOrderController.parseInboundQty(Map.of("qty_11", "0")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.v142.inbound.empty");
    }

    private static ShopOrder order() {
        return ShopOrder.place("tok", "TOKO-1", T0, 1L, 100_000L, 20_000L, 0L,
                new AddressSnapshot("B", "+62811", "DKI", "JS", "KB", "Jl", "12160"));
    }

    @Test
    void closeClaimRecordsResultAndAmountRejectedIsZero() {
        ShopOrder o = order();
        o.closeClaim("PARTIAL", 40_000L, T0, 7L);
        assertThat(o.getClaimResult()).isEqualTo("PARTIAL");
        assertThat(o.getClaimAmount()).isEqualTo(40_000L);
        assertThat(o.getClaimClosedAt()).isEqualTo(T0);
        ShopOrder r = order();
        r.closeClaim("REJECTED", 99L, T0, 7L);
        assertThat(r.getClaimAmount()).isZero();
    }

    @Test
    void closeClaimRejectsUnknownResultAndNegativeAmount() {
        assertThatThrownBy(() -> order().closeClaim("WON", 1L, T0, 7L)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.v142.claim.resultRequired");
        assertThatThrownBy(() -> order().closeClaim("PAID", -1L, T0, 7L)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.v142.claim.amountNegative");
        assertThatThrownBy(() -> order().closeClaim("PAID", 0L, T0, 7L)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.v142.claim.amountRequired");
    }
}
