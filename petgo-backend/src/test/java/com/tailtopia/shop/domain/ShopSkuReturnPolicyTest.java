package com.tailtopia.shop.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：SKU 退货规则的继承语义（Story 1.1 AC2，FR-94A）。
 *
 * <p>SKU 级为空时必须继承商品级——否则未单独设置的 SKU 在详情页显示为空，
 * 用户看不到「开封不退」这类关键约束（FR-104 要求三处明示，商品详情页是第 1 处）。
 */
class ShopSkuReturnPolicyTest {

    private ShopSku skuWith(ReturnPolicy own) {
        ShopSku sku = new ShopSku() {
        };
        ReflectionTestUtils.setField(sku, "returnPolicy", own);
        return sku;
    }

    @Test
    @DisplayName("SKU 级为空 → 继承商品级")
    void inheritsWhenNull() {
        assertThat(skuWith(null).effectiveReturnPolicy(ReturnPolicy.NO_RETURN_AFTER_OPEN))
                .isEqualTo(ReturnPolicy.NO_RETURN_AFTER_OPEN);
        assertThat(skuWith(null).effectiveReturnPolicy(ReturnPolicy.NON_RETURNABLE))
                .isEqualTo(ReturnPolicy.NON_RETURNABLE);
    }

    @Test
    @DisplayName("SKU 级有值 → 覆盖商品级（同商品不同规格可有不同标识，FR-94A）")
    void overridesWhenPresent() {
        assertThat(skuWith(ReturnPolicy.RETURNABLE)
                .effectiveReturnPolicy(ReturnPolicy.NON_RETURNABLE))
                .isEqualTo(ReturnPolicy.RETURNABLE);
    }

    @Test
    @DisplayName("🔴 枚举三值不含「换」—— 换货已砍出本版本（C-13）")
    void noExchangeValue() {
        assertThat(ReturnPolicy.values()).containsExactly(
                ReturnPolicy.RETURNABLE,
                ReturnPolicy.NO_RETURN_AFTER_OPEN,
                ReturnPolicy.NON_RETURNABLE);
        assertThat(ReturnPolicy.values()).hasSize(3);
    }
}
