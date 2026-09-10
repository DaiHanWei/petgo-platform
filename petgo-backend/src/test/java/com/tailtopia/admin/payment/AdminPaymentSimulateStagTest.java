package com.tailtopia.admin.payment;

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
import com.tailtopia.admin.payment.web.AdminPaymentSimulateController;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

/**
 * L1（真库 + stag profile）：B12 模拟回调三钮只在 stag 存在（V1.3.0 Story 8.5 · AC2，决策 D-41）。
 *
 * <p>与 {@code AdminMoneyPagesDrawerIntegrationTest} 成对：那边验「非 stag 时这三条路由 404、
 * 抽屉里整块不渲染」，这边验「stag 时真的能用、终态被拦、非超管进不来」。
 *
 * <p>⚠️ 真实 staging 容器的 profile 见 runbook（当前为 {@code prod}）；
 * {@code @StagOnly} 要生效须 {@code SPRING_PROFILES_ACTIVE=prod,stag}。
 */
@ActiveProfiles({"dev", "stag"})
class AdminPaymentSimulateStagTest extends ApiIntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private PaymentIntentRepository intents;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "sim-" + n + "@tailtopia.test", "模拟回调测试员", "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
        }
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String s : permissions) {
            auths.add(new SimpleGrantedAuthority(s));
        }
        return new TestingAuthenticationToken(p, null, auths);
    }

    private PaymentIntent seedIntent() {
        long n = SEQ.incrementAndGet();
        return intents.save(PaymentIntent.create(760L + n, PaymentPurpose.VET_CONSULT,
                PayChannel.QRIS, 50000L, "IDR", "sim-p-" + n));
    }

    @Test
    void theControllerIsRegisteredOnlyUnderStag() {
        assertThat(context.getBeansOfType(AdminPaymentSimulateController.class)).hasSize(1);
    }

    @Test
    void theDrawerRendersTheThreeButtonsWithTheTestEnvironmentBadge() throws Exception {
        PaymentIntent p = seedIntent();
        String html = mvc.perform(get("/admin/payments/" + p.getPublicToken() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-stag-only").contains("仅测试环境")
                .contains("simulate-paid").contains("simulate-failed").contains("simulate-expired");
        // 🔴 不可逆且会真的触发下游入账 —— 三个钮都要过确认。
        assertThat(html).contains("data-confirm");
    }

    @Test
    void simulatingSuccessDrivesTheRealCallbackPath() throws Exception {
        PaymentIntent p = seedIntent();
        mvc.perform(post("/admin/payments/" + p.getPublicToken() + "/simulate-paid")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))).with(csrf()))
                .andExpect(status().isOk());

        PaymentIntent after = intents.findByPublicToken(p.getPublicToken()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PAID);
        // ⚠️ gatewayRef 带 sim- 前缀：事后在库里一眼能认出「这笔是测出来的」。
        assertThat(after.getGatewayRef()).startsWith("sim-");
    }

    /** 🔴 终态不能再模拟：收口对已终态是**静默返回**，放过去的话运营点完什么都没变。 */
    @Test
    void aTerminalIntentIsRejectedRatherThanSilentlyIgnored() throws Exception {
        PaymentIntent p = seedIntent();
        Authentication su = auth(AdminAccountType.SUPER_ADMIN);
        mvc.perform(post("/admin/payments/" + p.getPublicToken() + "/simulate-failed")
                        .header("HX-Request", "true").with(authentication(su)).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/payments/" + p.getPublicToken() + "/simulate-paid")
                        .header("HX-Request", "true").with(authentication(su)).with(csrf()))
                .andExpect(status().isUnprocessableEntity());

        String html = mvc.perform(get("/admin/payments/" + p.getPublicToken() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(su)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).as("终态：按钮留在原地但置灰 + 说明原因").contains("disabled")
                .doesNotContain("hx-post");
    }

    /** 🛡 即使在 stag，普通运营（有 payment.view）也不能把一笔支付刷成已付款。 */
    @Test
    void aStaffWithPaymentViewCannotSimulate() throws Exception {
        PaymentIntent p = seedIntent();
        Authentication staff = auth(AdminAccountType.STAFF, AdminPermissions.PAYMENT_VIEW);
        mvc.perform(post("/admin/payments/" + p.getPublicToken() + "/simulate-paid")
                        .header("HX-Request", "true").with(authentication(staff)).with(csrf()))
                .andExpect(status().isForbidden());

        String html = mvc.perform(get("/admin/payments/" + p.getPublicToken() + "/drawer")
                        .param("lang", "zh_CN").header("HX-Request", "true")
                        .with(authentication(staff)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).as("看得到的人也不该看到那三个钮").doesNotContain("simulate-paid");
    }
}
