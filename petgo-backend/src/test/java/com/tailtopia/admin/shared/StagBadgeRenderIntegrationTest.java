package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/** L1（真库 + stag profile）：STAG 角标只在 stag profile 渲染（Story 2.2 AC4）。独立上下文（profile 不同）。 */
@ActiveProfiles({"dev", "stag"})
class StagBadgeRenderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private StagFlag stagFlag;

    @Test
    void stagBadgeRenderedInStagProfile() throws Exception {
        assertThat(stagFlag.isStag()).isTrue();
        AdminUserDetails admin = new AdminUserDetails(1L, null, "stag@tailtopia.test", null,
                AdminAccountType.SUPER_ADMIN, Set.of(), 0, "Stag 超管", "SUPER_ADMIN");
        String html = mvc.perform(get("/admin/dashboard").param("lang", "zh_CN").with(user(admin)))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("stag-badge").contains("STAG");
        assertThat(html).contains("Stag 超管");
    }
}
