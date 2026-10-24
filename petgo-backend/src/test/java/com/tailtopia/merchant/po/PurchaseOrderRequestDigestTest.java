package com.tailtopia.merchant.po;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.merchant.po.domain.PoShippingMethod;
import com.tailtopia.merchant.po.service.NewPurchaseOrder;
import com.tailtopia.merchant.po.service.PurchaseOrderRequestDigest;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：建单请求体摘要口径（契约 5.4）。期望值由契约规则独立算出（Python {@code hashlib.sha256} 对同一规范化串），
 * 与商家平台 {@code CheckoutFingerprint} 是同一口径 —— 两边任何一处改了转义或键序，这里先红。
 */
class PurchaseOrderRequestDigestTest {

    private static NewPurchaseOrder sample(List<NewPurchaseOrder.Line> lines) {
        return new NewPurchaseOrder(lines,
                new AddressSnapshot("Budi Ñ", "+6281234567890", "DKI Jakarta", "Jakarta Selatan",
                        "Kebayoran Baru", "Jl. Melati \"No\" 5\n", "12110"),
                PoShippingMethod.FREE, "Toko Budi", "+6281234567890");
    }

    @Test
    @DisplayName("规范化 JSON 键序、转义与契约一致，SHA-256 与独立计算值相等")
    void matchesContractCanonicalForm() {
        NewPurchaseOrder req = sample(List.of(new NewPurchaseOrder.Line("skuA", 2, 45000),
                new NewPurchaseOrder.Line("skuB", 1, 0)));
        assertThat(PurchaseOrderRequestDigest.of(req))
                .isEqualTo("6ed8f35031f6b922f50c4b538ca30ef6bb08e94d7ca9533f81515ab5b7b81cca");
    }

    @Test
    @DisplayName("行顺序不同 = 不同请求体")
    void lineOrderMatters() {
        String a = PurchaseOrderRequestDigest.of(sample(List.of(new NewPurchaseOrder.Line("skuA", 2, 45000),
                new NewPurchaseOrder.Line("skuB", 1, 0))));
        String b = PurchaseOrderRequestDigest.of(sample(List.of(new NewPurchaseOrder.Line("skuB", 1, 0),
                new NewPurchaseOrder.Line("skuA", 2, 45000))));
        assertThat(a).isNotEqualTo(b);
    }
}
