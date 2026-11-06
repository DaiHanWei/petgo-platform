package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/** L1（V1.4.2 shop-admin Story 5.2 · AC1 / AC9）：退款打款页渲染、页签、只读与权限。L1 待本地验收。 */
class AdminShopRefundPayoutPageIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication staffWith(String... codes) {
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "payout-" + SEQ.incrementAndGet() + "@tailtopia.test", "打款测试账号", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    @Test
    @DisplayName("refund.view 可看页面：五个页签 + 注销用户页签 + 摘要")
    void viewerSeesPage() throws Exception {
        for (String tab : new String[] {"AWAITING", "PENDING", "FAILED", "PAID", "PAWCOIN", "DELETED"}) {
            String html = mvc.perform(get("/admin/shop/refund-payouts").param("tab", tab)
                            .with(authentication(staffWith(AdminPermissions.REFUND_VIEW))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("payout-summary").contains("data-payout-tabs").doesNotContain("??");
        }
    }

    @Test
    @DisplayName("无 refund.payout → 写端点 403；无任何退款权限 → 页面 403")
    void permissions() throws Exception {
        mvc.perform(post("/admin/shop/refund-payouts/nope/paid").param("txnNo", "x")
                        .with(authentication(staffWith(AdminPermissions.REFUND_VIEW))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/shop/refund-payouts").with(authentication(staffWith(AdminPermissions.CONFIG_VIEW))))
                .andExpect(status().isForbidden());
    }
}
