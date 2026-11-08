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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1（V1.4.2 shop-admin Story 2.2）：优惠券页列表 / 新建 / 防误配 / 停用 / 权限。需真库，云端只编译。
 */
class AdminShopCouponPageIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicLong N = new AtomicLong(System.nanoTime() % 100000);

    private Authentication staffWith(String... codes) {
        long n = N.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin("cpn-" + n + "@tailtopia.test", "券测试", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(),
                AdminAccountType.STAFF, Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    @Test
    void viewerSeesTheListButCannotWrite() throws Exception {
        Authentication viewer = staffWith(AdminPermissions.SHOP_COUPON_VIEW);
        String html = mvc.perform(get("/admin/shop/coupons").with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("??").doesNotContain("data-drawer-open=\"/admin/shop/coupons?create=1\"");
        mvc.perform(post("/admin/shop/coupons").with(csrf()).with(authentication(viewer)).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/shop/coupons").with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_EDIT))))
                .andExpect(status().isForbidden());
    }

    @Test
    void riskyCouponAsksForConfirmationThenSaves() throws Exception {
        Authentication editor = staffWith(AdminPermissions.SHOP_COUPON_EDIT);
        String name = "IT-" + N.incrementAndGet();
        LocalDate today = LocalDate.now();
        var req = post("/admin/shop/coupons").with(csrf()).with(authentication(editor)).header("HX-Request", "true")
                .param("kind", "DISCOUNT").param("nameId", name).param("nameEn", name)
                .param("amount", "10000").param("minSpend", "0").param("acquireType", "CLAIM")
                .param("issueStart", today.plusDays(1).toString()).param("issueEnd", today.plusDays(10).toString())
                .param("validMode", "RELATIVE").param("validDays", "7").param("perUserLimit", "1").param("scopeType", "ALL");
        String modal = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(modal).contains("confirm-modal").contains("acknowledgeRisk");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_coupons WHERE name_id = ?", Long.class, name)).isZero();
        mvc.perform(req.param("acknowledgeRisk", "true")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_coupons WHERE name_id = ?", Long.class, name)).isEqualTo(1L);
    }

    @Test
    void discountNotBelowMinSpendIsRejectedInTheDrawer() throws Exception {
        Authentication editor = staffWith(AdminPermissions.SHOP_COUPON_EDIT);
        LocalDate today = LocalDate.now();
        String html = mvc.perform(post("/admin/shop/coupons").with(csrf()).with(authentication(editor)).header("HX-Request", "true")
                        .param("kind", "DISCOUNT").param("nameId", "X").param("nameEn", "X")
                        .param("amount", "50000").param("minSpend", "50000").param("acquireType", "CLAIM")
                        .param("issueStart", today.plusDays(1).toString()).param("issueEnd", today.plusDays(10).toString())
                        .param("validMode", "RELATIVE").param("validDays", "7").param("totalQuantity", "10"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("banner err").contains("name=\"nameId\"");
    }
}
