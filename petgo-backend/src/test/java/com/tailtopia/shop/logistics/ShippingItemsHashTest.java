package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.support.ShopSkuFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 1.1 · 包裹哈希（D-9）只含包裹参数与数量。 */
class ShippingItemsHashTest {

    private static PackageItem item(String name, long value, int qty, int w, int l, int wi, int h) {
        return new PackageItem(name, value, qty, w, l, wi, h);
    }

    private static final PackageItem A = item("A", 100_000, 1, 1000, 20, 15, 10);
    private static final PackageItem B = item("B", 50_000, 2, 500, 10, 10, 5);

    @Test
    @DisplayName("同包裹不同顺序 hash 相同；64 位小写十六进制")
    void orderIndependent() {
        String h1 = PackageItems.itemsHash(List.of(A, B));
        String h2 = PackageItems.itemsHash(List.of(B, A));
        assertThat(h1).isEqualTo(h2).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("数量或任一尺寸变化 → hash 不同")
    void dimensionsAndQtyMatter() {
        String base = PackageItems.itemsHash(List.of(A));
        assertThat(PackageItems.itemsHash(List.of(item("A", 100_000, 2, 1000, 20, 15, 10)))).isNotEqualTo(base);
        assertThat(PackageItems.itemsHash(List.of(item("A", 100_000, 1, 1001, 20, 15, 10)))).isNotEqualTo(base);
        assertThat(PackageItems.itemsHash(List.of(item("A", 100_000, 1, 1000, 21, 15, 10)))).isNotEqualTo(base);
        assertThat(PackageItems.itemsHash(List.of(item("A", 100_000, 1, 1000, 20, 16, 10)))).isNotEqualTo(base);
        assertThat(PackageItems.itemsHash(List.of(item("A", 100_000, 1, 1000, 20, 15, 11)))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("名称与单价变化 → hash 不变")
    void nameAndValueIgnored() {
        assertThat(PackageItems.itemsHash(List.of(item("Other name", 1, 1, 1000, 20, 15, 10))))
                .isEqualTo(PackageItems.itemsHash(List.of(A)));
    }

    @Test
    @DisplayName("规范化串为 v1|w,l,wi,h,qty 排序后 ; 连接（已知值）")
    void knownCanonicalForm() throws Exception {
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest("v1|1000,20,15,10,1;500,10,10,5,2"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(PackageItems.itemsHash(List.of(B, A))).isEqualTo(expected);
    }

    @Test
    @DisplayName("PackageItems.of：可售 SKU 取包裹参数；不可售 → 422 sku-not-sellable")
    void ofSku() {
        ShopSku sku = ShopSku.create("t", 1L, "3 kg", 285_000L, null, ReturnPolicy.RETURNABLE);
        assertThatThrownBy(() -> PackageItems.of(sku, "Royal Canin", 1))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.SKU_NOT_SELLABLE));
        ShopSkuFixtures.sellable(sku);
        PackageItem i = PackageItems.of(sku, "Royal Canin", 3);
        assertThat(i).isEqualTo(new PackageItem("Royal Canin 3 kg", 285_000L, 3, 1000, 20, 15, 10));
        assertThat(i.toString()).doesNotContain("Royal");
    }
}
