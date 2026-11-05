package com.tailtopia.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：商品详情运费估算 {@code GET /api/v1/shop/products/{token}/shipping-estimate}（v1.4.2 shop Story 3.1 AC2）。
 * 需 Docker postgres + redis，{@code BITESHIP_MODE=stub}。云端不跑，待本地验收。
 */
class ShippingEstimateIntegrationTest extends ApiIntegrationTest {

    @Autowired private AdminShippingZoneService zones;
    @Autowired private ShippingAddressService addresses;
    @Autowired private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private String[] seedProduct(boolean sellable) {
        String pToken = "se" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?",
                Long.class, pToken);
        String sToken = "ses" + SEQ.incrementAndGet();
        if (sellable) {
            jdbc.update("""
                    INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g,
                            package_length_cm, package_width_cm, package_height_cm)
                    VALUES (?, ?, '3 kg', 100000, 1000, 20, 15, 10)""", sToken, pid);
        } else {
            jdbc.update("""
                    INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                    VALUES (?, ?, '3 kg', 100000)""", sToken, pid);
        }
        return new String[] {pToken, sToken};
    }

    private long seedZone(String postalCode) {
        String kec = "Ke" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        jdbc.update("UPDATE shipping_zones SET postal_code = ? WHERE kecamatan = ?", postalCode, kec);
        return jdbc.queryForObject("SELECT id FROM shipping_zones WHERE kecamatan = ?", Long.class, kec);
    }

    @Test
    @DisplayName("游客按地区 → serviceable=true、lowestFee=10000；无收货地 → NO_DESTINATION")
    void guestByRegion() throws Exception {
        String[] p = seedProduct(true);
        long zone = seedZone("12160");
        mvc.perform(get("/api/v1/shop/products/{t}/shipping-estimate", p[0])
                        .param("skuToken", p[1]).param("regionKecamatanId", String.valueOf(zone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceable").value(true))
                .andExpect(jsonPath("$.lowestFee").value(10_000))
                .andExpect(jsonPath("$.quoteFailed").value(false));
        mvc.perform(get("/api/v1/shop/products/{t}/shipping-estimate", p[0]).param("skuToken", p[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("NO_DESTINATION"));
    }

    @Test
    @DisplayName("区域无邮编 → serviceable=false；stub 失败邮编 → quoteFailed=true（200）")
    void noPostalAndFailure() throws Exception {
        String[] p = seedProduct(true);
        long noPostal = seedZone(null);
        long failing = seedZone("99998");
        mvc.perform(get("/api/v1/shop/products/{t}/shipping-estimate", p[0])
                        .param("skuToken", p[1]).param("regionKecamatanId", String.valueOf(noPostal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceable").value(false));
        mvc.perform(get("/api/v1/shop/products/{t}/shipping-estimate", p[0])
                        .param("skuToken", p[1]).param("regionKecamatanId", String.valueOf(failing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteFailed").value(true))
                .andExpect(jsonPath("$.reason").value("QUOTE_FAILED"));
    }

    @Test
    @DisplayName("登录有地址：按本人地址估算")
    void loggedInWithAddress() throws Exception {
        String[] p = seedProduct(true);
        long uid = newUser().getId();
        String kec = "Ka" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. 1", "12160", null)).getPublicToken();
        mvc.perform(get("/api/v1/shop/products/{t}/shipping-estimate", p[0])
                        .param("skuToken", p[1]).param("addressToken", addr)
                        .header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowestFee").value(10_000));
    }

    @Test
    @DisplayName("不可售 SKU → 422 sku-not-sellable")
    void unsellableSku() throws Exception {
        String[] p = seedProduct(false);
        long zone = seedZone("12160");
        mvc.perform(get("/api/v1/shop/products/{t}/shipping-estimate", p[0])
                        .param("skuToken", p[1]).param("regionKecamatanId", String.valueOf(zone)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("sku-not-sellable")));
    }
}
