package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/** 2026-11-13 后台商品管理页按商品名搜索：真库跑 JPQL（LIKE 转义、大小写、空搜索、与其他筛选叠加）。 */
class AdminShopProductNameSearchIntegrationTest extends ApiIntegrationTest {

    @Autowired
    AdminShopProductService service;

    @Autowired
    ShopProductRepository products;

    private final List<Long> created = new ArrayList<>();
    private final String tag = "zq" + UUID.randomUUID().toString().substring(0, 6);

    @AfterEach
    void cleanup() {
        products.deleteAllById(created);
    }

    private void save(String name, boolean active) {
        ShopProduct p = ShopProduct.create("ns-" + UUID.randomUUID().toString().substring(0, 20), name, "Brand",
                ProductCategory.MAKANAN, "k", null, null, List.of(), Species.DOG, null, null, "<p/>", List.of(),
                "n", ReturnPolicy.values()[0], 0);
        ReflectionTestUtils.setField(p, "active", active); // 新建商品默认未上架，这里显式设
        created.add(products.save(p).getId());
    }

    private List<String> names(ProductCategory c, Boolean active, String q) {
        return service.page(c, active, false, q, 0, 100).getContent().stream()
                .map(ShopProduct::getName).filter(n -> n.contains(tag)).toList();
    }

    @Test
    @DisplayName("按名称模糊搜索：不区分大小写；% _ 按字面；空搜索不过滤；可与上架状态叠加")
    void searchByName() {
        save("Royal " + tag + " Kitten", true);
        save("Pro " + tag + " Adult", false);
        save("Diskon 50% " + tag, true);
        save("a_b " + tag, true);

        assertThat(names(null, null, tag.toUpperCase())).hasSize(4);
        assertThat(names(null, null, "royal " + tag)).containsExactly("Royal " + tag + " Kitten");
        assertThat(names(null, null, "50% " + tag)).containsExactly("Diskon 50% " + tag);
        assertThat(names(null, null, "%")).hasSize(1).first().asString().contains("50%");
        assertThat(names(null, null, "_")).containsExactly("a_b " + tag);
        assertThat(names(null, null, "   ")).hasSize(4);
        assertThat(names(null, null, null)).hasSize(4);
        assertThat(names(null, false, tag)).containsExactly("Pro " + tag + " Adult");
        assertThat(service.page(null, null, true, tag, 0, 100).getContent()).isNotNull(); // 缺包裹分支的 JPQL 能跑
    }
}
