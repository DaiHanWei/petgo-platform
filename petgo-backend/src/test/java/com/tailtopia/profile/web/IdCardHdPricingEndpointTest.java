package com.tailtopia.profile.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code GET /api/v1/pet-profiles/me/id-cards/pricing} 集成测试（417 同类：HD 展示价改后端下发）。
 *
 * <p>覆盖：登录门控（guest→401）、价格实时反映 {@code pricing_config.id_hd_download_price}
 * 当前值（后台改价 → 下一次查询即新价，与扣费口径同源）。
 */
class IdCardHdPricingEndpointTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/pet-profiles/me/id-cards/pricing";

    @Autowired
    private PricingConfigRepository pricingRepo;

    private long originalPrice;
    private boolean priceTouched;

    @AfterEach
    void restorePrice() {
        if (priceTouched) {
            PricingConfig pc = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
            pc.setIdHdDownloadPrice(originalPrice);
            pricingRepo.save(pc);
        }
    }

    @Test
    void pricing_byUser_returnsCurrentConfiguredPrice() throws Exception {
        User u = newUser();
        PricingConfig pc = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        originalPrice = pc.getIdHdDownloadPrice();
        priceTouched = true;

        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(originalPrice));

        // 后台改价 → 下一次查询即新价（无缓存，实时读 pricing_config）。
        pc.setIdHdDownloadPrice(originalPrice + 4900);
        pricingRepo.save(pc);
        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(originalPrice + 4900));
    }

    @Test
    void pricing_missingToken_returns401() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }
}
