package com.tailtopia.admin.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/** L1：默认（非 stag）profile 不注册 kitchen-sink 路由 → 404（Story 2.3b AC7 生产不存在）。 */
class KitchenSinkAbsentByDefaultIntegrationTest extends ApiIntegrationTest {

    @Test
    void kitchenSinkIs404WithoutStagProfile() throws Exception {
        AdminUserDetails admin = new AdminUserDetails(1L, null, "ks@tailtopia.test", null, AdminAccountType.SUPER_ADMIN);
        mvc.perform(get("/admin/_kitchen-sink").with(user(admin))).andExpect(status().isNotFound());
    }
}
