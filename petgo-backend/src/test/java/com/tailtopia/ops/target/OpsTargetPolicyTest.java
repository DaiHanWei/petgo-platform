package com.tailtopia.ops.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：统一跳转目标校验口径（V1.3.4 Story 5.1 · AC2）。 */
class OpsTargetPolicyTest {

    private final ShopProductRepository products = mock(ShopProductRepository.class);
    private final OpsTargetPolicy policy = new OpsTargetPolicy(products);

    @Test
    void none() {
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.NONE, null)).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.NONE, "HOME")).as("NONE 带值非法").isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.NONE, "")).isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(null, null)).isFalse();
    }

    @Test
    void product() {
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.PRODUCT, "aB3_x-9")).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.PRODUCT, "a".repeat(32))).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.PRODUCT, "a".repeat(33))).isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.PRODUCT, "ab/cd")).isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.PRODUCT, "")).isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.PRODUCT, null)).isFalse();
    }

    @Test
    void category() {
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.CATEGORY, "MAKANAN")).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.CATEGORY, "OBAT_VITAMIN")).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.CATEGORY, "CAMILAN")).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.CATEGORY, "PERAWATAN")).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.CATEGORY, "makanan")).as("区分大小写").isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.CATEGORY, "MAINAN")).isFalse();
    }

    @Test
    void appPage() {
        for (String page : new String[] {"HOME", "PUBLISH", "TOKO", "KNOW_YOUR_PET", "HEALTH"}) {
            assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.APP_PAGE, page)).as(page).isTrue();
        }
        assertThat(OpsAppPage.values()).hasSize(5);
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.APP_PAGE, "home")).as("小写非法").isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.APP_PAGE, "SETTINGS")).as("非 5 值").isFalse();
    }

    @Test
    void external() {
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "https://tailtopia.id/promo?a=1")).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "http://x.id")).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "HTTPS://x.id")).as("大写 scheme 合法").isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "javascript:alert(1)")).isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "ftp://x")).isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "https://")).as("无 host").isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "/relative/path")).isFalse();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, "https://x.id/a b")).as("不可解析").isFalse();
        String url512 = "https://x.id/" + "a".repeat(512 - "https://x.id/".length());
        assertThat(url512).hasSize(512);
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, url512)).isTrue();
        assertThat(OpsTargetPolicy.isWellFormed(OpsTargetType.EXTERNAL, url512 + "a")).as("513 字符").isFalse();
    }

    @Test
    void productAvailabilityRequiresActiveProduct() {
        when(products.findByPublicTokenAndActiveTrue("onSale")).thenReturn(Optional.of(mock(ShopProduct.class)));
        when(products.findByPublicTokenAndActiveTrue("delisted")).thenReturn(Optional.empty());

        assertThat(policy.isAvailable(OpsTargetType.PRODUCT, "onSale")).isTrue();
        assertThat(policy.isAvailable(OpsTargetType.PRODUCT, "delisted")).as("下架不可用").isFalse();
    }

    @Test
    void malformedNeverHitsRepositoryAndOtherTypesNeedOnlyWellFormed() {
        assertThat(policy.isAvailable(OpsTargetType.PRODUCT, "bad/token")).isFalse();
        verify(products, never()).findByPublicTokenAndActiveTrue("bad/token");

        assertThat(policy.isAvailable(OpsTargetType.NONE, null)).isTrue();
        assertThat(policy.isAvailable(OpsTargetType.CATEGORY, "MAKANAN")).isTrue();
        assertThat(policy.isAvailable(OpsTargetType.APP_PAGE, "TOKO")).isTrue();
        assertThat(policy.isAvailable(OpsTargetType.EXTERNAL, "https://x.id")).isTrue();
        assertThat(policy.isAvailable(OpsTargetType.EXTERNAL, "ftp://x")).isFalse();
    }

    @Test
    void batchProductTokensSkipMalformedAndEmpty() {
        assertThat(policy.availableProductTokens(java.util.List.of("bad/token"))).isEmpty();
        verifyNoInteractions(products);

        when(products.findActivePublicTokens(java.util.List.of("a1", "b2"))).thenReturn(Set.of("a1"));
        assertThat(policy.availableProductTokens(java.util.List.of("a1", "bad/x", "b2", "a1"))).containsExactly("a1");
    }

    @Test
    void batchIsAvailableMatchesSingleCheck() {
        Set<String> onSale = Set.of("a1");
        assertThat(OpsTargetPolicy.isAvailable(OpsTargetType.PRODUCT, "a1", onSale)).isTrue();
        assertThat(OpsTargetPolicy.isAvailable(OpsTargetType.PRODUCT, "b2", onSale)).isFalse();
        assertThat(OpsTargetPolicy.isAvailable(OpsTargetType.CATEGORY, "MAKANAN", onSale)).isTrue();
        assertThat(OpsTargetPolicy.isAvailable(OpsTargetType.EXTERNAL, "ftp://x", onSale)).isFalse();
        assertThat(OpsTargetPolicy.isAvailable(OpsTargetType.NONE, "x", onSale)).isFalse();
    }

    @Test
    void httpsUrlRequiresHttpsSchemeAndHost() {
        assertThat(OpsTargetPolicy.isHttpsUrl("https://cdn.x/a.jpg")).isTrue();
        assertThat(OpsTargetPolicy.isHttpsUrl("HTTPS://cdn.x/a.jpg")).isTrue();
        assertThat(OpsTargetPolicy.isHttpsUrl("http://cdn.x/a.jpg")).isFalse();
        assertThat(OpsTargetPolicy.isHttpsUrl("https://")).isFalse();
        assertThat(OpsTargetPolicy.isHttpsUrl("https:///a.jpg")).isFalse();
        assertThat(OpsTargetPolicy.isHttpsUrl(null)).isFalse();
    }
}
