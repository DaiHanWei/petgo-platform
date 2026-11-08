package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/** L1（V1.4.2 shop-admin Story 3.2 · C2）：保存前预检回确认弹层（新旧值 + 估算），不写库。需真库，云端只编译。 */
class AdminShareRewardConfirmIntegrationTest extends ApiIntegrationTest {

    @Test
    void confirmReturnsTheModalWithEstimate() throws Exception {
        String html = mvc.perform(post("/admin/config/share-reward/confirm").with(csrf())
                        .with(user("ops").roles("SUPER_ADMIN")).header("HX-Request", "true")
                        .param("shareRewardEnabled", "true").param("shareRewardMonthlyCap", "999999")
                        .param("idCardShareReward", "7").param("idCardShareDailyCap", "1"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#confirm-host"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("confirm-modal").contains("cm-banner-warn").contains("name=\"confirmed\"")
                .contains("每个账号每月最多可获得");
    }
}
