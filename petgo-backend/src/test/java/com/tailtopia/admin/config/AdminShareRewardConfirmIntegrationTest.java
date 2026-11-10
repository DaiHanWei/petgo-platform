package com.tailtopia.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/** L1（V1.4.2 shop-admin Story 3.2 · C2）：保存前预检回确认弹层（新旧值 + 估算），不写库。需真库，云端只编译。 */
class AdminShareRewardConfirmIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    /**
     * 真实后台超管 principal：{@code /admin/**} 门控要 {@code ROLE_ADMIN}，且控制器从 {@link AdminUserDetails} 取账号 id ——
     * {@code user("ops").roles("SUPER_ADMIN")} 两样都不满足（无 ROLE_ADMIN、principal 类型不对）。
     */
    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("srcf-" + n + "@tailtopia.test", "分享奖励预检超管", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    @Test
    void confirmReturnsTheModalWithEstimate() throws Exception {
        String html = mvc.perform(post("/admin/config/share-reward/confirm").with(csrf())
                        .with(authentication(superAdmin())).header("HX-Request", "true")
                        .param("shareRewardEnabled", "true").param("shareRewardMonthlyCap", "999999")
                        .param("idCardShareReward", "7").param("idCardShareDailyCap", "1"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Retarget", "#confirm-host"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("confirm-modal").contains("cm-banner-warn").contains("name=\"confirmed\"")
                .contains("每个账号每月最多可获得");
    }
}
