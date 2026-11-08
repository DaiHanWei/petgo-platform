package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/** L1（V1.4.2 shop-admin Story 2.3）：用户抽屉第 10 个页签「优惠券」，权限沿用 user.view，只读。需真库，云端只编译。 */
class AdminUserCouponsTabIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("ucp-" + n + "@tailtopia.test", "用户券", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    @Test
    void drawerHasTenTabsAndAnEmptyCouponsPanel() throws Exception {
        long uid = newUser().getId();
        String html = mvc.perform(get("/admin/users/" + uid + "/drawer").header("HX-Request", "true")
                        .with(authentication(staffWith(AdminPermissions.USER_VIEW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-utab=\"coupons\"").contains("user-coupons-panel").contains("该用户还没有优惠券")
                .doesNotContain("??");
        assertThat(html.split("data-utab=\"", -1)).hasSize(11);
    }
}
