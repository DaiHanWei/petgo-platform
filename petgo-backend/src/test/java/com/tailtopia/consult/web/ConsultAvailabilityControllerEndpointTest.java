package com.tailtopia.consult.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code GET /api/v1/consult/availability} 集成测试：兽医在线可用性（只回 bool，不泄露人数）。
 *
 * <p>覆盖：ROLE_USER 门控（vet→403 / guest→401）、响应仅含 {@code vetOnline}/{@code expectedWindow}。
 */
class ConsultAvailabilityControllerEndpointTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/consult/availability";

    @Autowired
    private VetAccountRepository vets;

    @Test
    void availability_byUser_returns200WithBoolOnly() throws Exception {
        User u = newUser();
        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetOnline").isBoolean())
                .andExpect(jsonPath("$.expectedWindow").exists())
                // 护栏：绝不透传精确在线人数
                .andExpect(jsonPath("$.onlineCount").doesNotExist())
                .andExpect(jsonPath("$.count").doesNotExist());
    }

    @Test
    void availability_missingToken_returns401() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void availability_byVet_returns403() throws Exception {
        VetAccount vet = vets.save(VetAccount.create("vet-it-" + SEQ.incrementAndGet(),
                "$2a$10$abcdefghijklmnopqrstuv", "兽医"));
        mvc.perform(get(URL).header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isForbidden());
    }
}
