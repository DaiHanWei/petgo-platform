package com.tailtopia.shop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 1.1 · SKU 包裹参数与可售判定（D-10）。 */
class ShopSkuSellableTest {

    private static ShopSku sku() {
        return ShopSku.create("t", 1L, "3 kg", 100L, null, ReturnPolicy.RETURNABLE);
    }

    @Test
    @DisplayName("新建 SKU 包裹参数为空 → 不可售；四项全有 → 可售")
    void sellableOnlyWhenComplete() {
        ShopSku s = sku();
        assertThat(s.isSellable()).isFalse();
        s.applyPackage(1000, 20, 15, 10);
        assertThat(s.isSellable()).isTrue();
        assertThat(s.getPackageWeightG()).isEqualTo(1000);
        assertThat(s.getPackageLengthCm()).isEqualTo(20);
        assertThat(s.getPackageWidthCm()).isEqualTo(15);
        assertThat(s.getPackageHeightCm()).isEqualTo(10);
    }

    @Test
    @DisplayName("任一为 null（如存量行部分补齐）→ 不可售")
    void anyNullNotSellable() {
        ShopSku s = sku();
        org.springframework.test.util.ReflectionTestUtils.setField(s, "packageWeightG", 1000);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "packageLengthCm", 20);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "packageWidthCm", 15);
        assertThat(s.isSellable()).isFalse();
    }

    @Test
    @DisplayName("全部 null = 清空，回到不可售")
    void clearAll() {
        ShopSku s = sku();
        s.applyPackage(1000, 20, 15, 10);
        s.applyPackage(null, null, null, null);
        assertThat(s.isSellable()).isFalse();
    }

    @Test
    @DisplayName("部分为空 / 非正 → IllegalArgumentException")
    void partialOrNonPositiveRejected() {
        assertThatThrownBy(() -> sku().applyPackage(1000, null, 15, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sku().applyPackage(null, 20, 15, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sku().applyPackage(0, 20, 15, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sku().applyPackage(1000, 20, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
