package com.tailtopia.consult.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code GET /api/v1/consult/pricing} 集成测试（bug 20260729-417）。
 *
 * <p>覆盖：ROLE_USER 门控（vet→403 / guest→401）、价格实时反映 {@code pricing_config}
 * 当前值（后台改价 → 下一次查询即新价，与扣费口径同源）。
 */
class ConsultPricingControllerEndpointTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/consult/pricing";

    @Autowired
    private PricingConfigRepository pricingRepo;

    @Autowired
    private VetAccountRepository vets;

    private long originalPrice;
    private boolean priceTouched;

    @AfterEach
    void restorePrice() {
        if (priceTouched) {
            PricingConfig pc = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
            pc.setVetConsultPrice(originalPrice);
            pricingRepo.save(pc);
        }
    }

    @Test
    void pricing_byUser_returnsCurrentConfiguredPrice() throws Exception {
        User u = newUser();
        PricingConfig pc = pricingRepo.findById(PricingConfig.SINGLETON_ID).orElseThrow();
        originalPrice = pc.getVetConsultPrice();
        priceTouched = true;

        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(originalPrice));

        // 后台改价 → 下一次查询即新价（无缓存，实时读 pricing_config）。
        pc.setVetConsultPrice(originalPrice + 25000);
        pricingRepo.save(pc);
        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(originalPrice + 25000));
    }

    @Test
    void pricing_missingToken_returns401() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pricing_byVet_returns403() throws Exception {
        VetAccount vet = vets.save(VetAccount.create("vet-pricing-" + SEQ.incrementAndGet(),
                "$2a$10$abcdefghijklmnopqrstuv", "兽医"));
        mvc.perform(get(URL).header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isForbidden());
    }
}
