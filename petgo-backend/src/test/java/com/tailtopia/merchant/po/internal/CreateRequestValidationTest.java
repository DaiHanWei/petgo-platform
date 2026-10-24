package com.tailtopia.merchant.po.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.merchant.po.domain.PoShippingMethod;
import com.tailtopia.merchant.po.internal.InternalPoDtos.AddressDto;
import com.tailtopia.merchant.po.internal.InternalPoDtos.CreateRequest;
import com.tailtopia.merchant.po.internal.InternalPoDtos.LineRequest;
import com.tailtopia.merchant.po.internal.InternalPoDtos.StoreSnapshotRequest;
import com.tailtopia.merchant.po.service.MerchantApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：建单请求体校验（契约 5.4 表格；违规 → 400 invalid-request，附 errors）。 */
class CreateRequestValidationTest {

    private static final AddressDto ADDR = new AddressDto("Budi", "+6281234567890", "DKI Jakarta",
            "Jakarta Selatan", "Kebayoran Baru", "Jl. Melati 5", "12110");
    private static final StoreSnapshotRequest STORE = new StoreSnapshotRequest("Toko Budi", "+6281234567890");

    @Test
    @DisplayName("合法请求体 → 命令对象，行顺序保留")
    void valid() {
        var cmd = InternalMerchantPurchaseOrderController.toCommand(new CreateRequest(
                List.of(new LineRequest("b", 1, 10L), new LineRequest("a", 3, 0L)), ADDR, "COD_SHIPPING", STORE));
        assertThat(cmd.lines()).extracting(l -> l.skuToken()).containsExactly("b", "a");
        assertThat(cmd.shippingMethod()).isEqualTo(PoShippingMethod.COD_SHIPPING);
    }

    @Test
    @DisplayName("重复 sku_token / qty 越界 / 负价 / 空白字段 / 邮编不是 5 位 / 未知运费方式 → 400")
    void invalid() {
        assertInvalid(new CreateRequest(List.of(new LineRequest("a", 1, 1L), new LineRequest("a", 1, 1L)),
                ADDR, "FREE", STORE));
        assertInvalid(new CreateRequest(List.of(new LineRequest("a", 10_000, 1L)), ADDR, "FREE", STORE));
        assertInvalid(new CreateRequest(List.of(new LineRequest("a", 1, -1L)), ADDR, "FREE", STORE));
        assertInvalid(new CreateRequest(List.of(), ADDR, "FREE", STORE));
        assertInvalid(new CreateRequest(List.of(new LineRequest("a", 1, 1L)),
                new AddressDto(" ", "+62", "p", "k", "c", "a", "12110"), "FREE", STORE));
        assertInvalid(new CreateRequest(List.of(new LineRequest("a", 1, 1L)),
                new AddressDto("n", "+62", "p", "k", "c", "a", "1211"), "FREE", STORE));
        assertInvalid(new CreateRequest(List.of(new LineRequest("a", 1, 1L)), ADDR, "PICKUP", STORE));
        assertInvalid(new CreateRequest(List.of(new LineRequest("a", 1, 1L)), ADDR, "FREE", null));
    }

    private static void assertInvalid(CreateRequest r) {
        assertThatThrownBy(() -> InternalMerchantPurchaseOrderController.toCommand(r))
                .isInstanceOfSatisfying(MerchantApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getType()).isEqualTo("invalid-request");
                    assertThat(e.getExtensions()).containsKey("errors");
                });
    }
}
