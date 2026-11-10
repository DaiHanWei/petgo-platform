package com.tailtopia.admin.consult;

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
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.consult.service.ConsultSuspensionService;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** L1（V1.4.2 exp-reverse Story 1.1 ~ 1.3）：真库 + 真模板，钉扣回链路、门控与筛选。 */
class AdminConsultOrderExpReverseIntegrationTest extends ApiIntegrationTest {

    private static final int ACCRUED = 50;

    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private ConsultInterruptService interrupts;
    @Autowired
    private ConsultSuspensionService suspensions;
    @Autowired
    private GrowthLedgerService growth;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "expreverse-" + n + "@tailtopia.test", "扣回测试员", "{bcrypt}x"));
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

    private Authentication reverser() {
        return auth(AdminAccountType.STAFF, AdminPermissions.ORDER_VIEW, AdminPermissions.ORDER_REVERSE_EXP);
    }

    /** 走真实挂起链路得到的 QRIS 退款中订单；入账可选。 */
    private ConsultOrder suspendedRefund(boolean accrue) {
        long userId = newUser().getId();
        long vetId = 8000L + SEQ.incrementAndGet();
        long intentId = 900000L + SEQ.incrementAndGet();
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.attachImConversation("conv-exp-" + SEQ.incrementAndGet());
        s = sessions.save(s);
        ConsultOrder o = ConsultOrder.inProgress("exp-" + SEQ.incrementAndGet(), userId, vetId, 1L, 50000L,
                PayChannel.QRIS, intentId, 30000L, 60, 50000L, Instant.now());
        o.markSessionStarted(Instant.now(), s.getId());
        o = orders.save(o);
        if (accrue) {
            growth.accrue(new AccrualCommand(userId, RuleCode.SPEND_DIGITAL, ACCRUED,
                    GrowthIdemKeys.spendIntent(intentId), "VET_CONSULT", String.valueOf(intentId), null, Instant.now()));
        }
        interrupts.interruptByVetBan(vetId);
        suspensions.forceEndSuspended(sessions.findById(s.getId()).orElseThrow());
        return orders.findById(o.getId()).orElseThrow();
    }

    private static MockHttpServletRequestBuilder hx(MockHttpServletRequestBuilder b, String currentUrl) {
        return b.header("HX-Request", "true").header("HX-Current-URL", currentUrl).header("HX-Target", "confirm-modal-err")
                .with(csrf());
    }

    private String doReverse(ConsultOrder o, Authentication a, String currentUrl, int expected) throws Exception {
        return mvc.perform(hx(post("/admin/consult-orders/" + o.getOrderToken() + "/exp-reverse"), currentUrl)
                        .param("lang", "zh_CN").with(authentication(a)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private String drawer(ConsultOrder o, Authentication a) throws Exception {
        return mvc.perform(get("/admin/consult-orders/" + o.getOrderToken() + "/drawer").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(a)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String filtered(Authentication a) throws Exception {
        return mvc.perform(get("/admin/consult-orders").param("filter", "exp-reverse").param("lang", "zh_CN")
                        .with(authentication(a)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long lifetime(long userId) {
        return jdbc.queryForObject("SELECT lifetime_total FROM growth_accounts WHERE user_id = ?", Long.class, userId);
    }

    private int ledgerRows(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND delta < 0",
                Integer.class, userId);
    }

    @Test
    void reverseTakesFullAccrualOnceAndAudits() throws Exception {
        ConsultOrder o = suspendedRefund(true);
        assertThat(o.getStatus()).isEqualTo(ConsultOrderStatus.REFUNDING);
        assertThat(o.getSuspensionRefundAt()).isNotNull();
        Authentication a = reverser();

        String html = doReverse(o, a, "http://x/admin/consult-orders", 200);

        assertThat(html).contains("已扣回 50 成长值").doesNotContain("data-exp-reverse");
        assertThat(lifetime(o.getUserId())).isZero();
        assertThat(orders.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(ConsultOrderStatus.REFUNDING);
        String summary = jdbc.queryForObject("SELECT summary FROM admin_audit_logs WHERE action_type = "
                + "'CONSULT_ORDER_EXP_REVERSED' AND target_id = ?", String.class, o.getOrderToken());
        assertThat(summary).contains("accrued=50").contains("reversedBefore=0").contains("reversed=50")
                .contains("levelBefore=").contains("levelAfter=");

        String again = doReverse(o, a, "http://x/admin/consult-orders", 422);

        assertThat(again).contains("这单的成长值已经扣回过了");
        assertThat(ledgerRows(o.getUserId())).isEqualTo(1);
    }

    @Test
    void drawerShowsButtonOnlyWhenReversibleAndPermitted() throws Exception {
        ConsultOrder o = suspendedRefund(true);

        assertThat(drawer(o, reverser())).contains("data-exp-reverse").contains("data-exp-pending");
        assertThat(drawer(o, auth(AdminAccountType.STAFF, AdminPermissions.ORDER_VIEW, AdminPermissions.ORDER_EDIT)))
                .doesNotContain("data-exp-reverse").contains("data-exp-pending");
        assertThat(drawer(suspendedRefund(false), auth(AdminAccountType.SUPER_ADMIN)))
                .contains("data-section=\"order-growth\"").doesNotContain("data-exp-reverse");
    }

    @Test
    void endpointsRequireThePermissionButSuperAdminPasses() throws Exception {
        ConsultOrder o = suspendedRefund(true);
        Authentication viewer = auth(AdminAccountType.STAFF, AdminPermissions.ORDER_VIEW, AdminPermissions.ORDER_EDIT);

        mvc.perform(hx(post("/admin/consult-orders/" + o.getOrderToken() + "/exp-reverse/preview"),
                        "http://x/admin/consult-orders").with(authentication(viewer)))
                .andExpect(status().isForbidden());
        doReverse(o, viewer, "http://x/admin/consult-orders", 403);
        assertThat(lifetime(o.getUserId())).isEqualTo(ACCRUED);

        String modal = mvc.perform(hx(post("/admin/consult-orders/" + o.getOrderToken() + "/exp-reverse/preview"),
                        "http://x/admin/consult-orders").param("lang", "zh_CN")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(modal).contains("扣回这单的成长值？").contains("此前已扣回").contains("本次扣回").contains("确认扣回");

        doReverse(o, auth(AdminAccountType.SUPER_ADMIN), "http://x/admin/consult-orders", 200);
        assertThat(lifetime(o.getUserId())).isZero();
    }

    @Test
    void filterListsOnlyReversibleOrdersAndDropsTheRowAfterReverse() throws Exception {
        ConsultOrder target = suspendedRefund(true);
        ConsultOrder noAccrual = suspendedRefund(false);
        long n = SEQ.incrementAndGet();
        ConsultOrder ordinary = orders.save(ConsultOrder.inProgress("exp-plain-" + n, 100L + n, 9L, 3L, 50000L,
                PayChannel.QRIS, null, 30000L, 60, 50000L, Instant.now()));
        Authentication a = reverser();

        String list = filtered(a);
        assertThat(list).contains("order-row-" + target.getOrderToken())
                .doesNotContain("order-row-" + noAccrual.getOrderToken())
                .doesNotContain("order-row-" + ordinary.getOrderToken());

        String after = doReverse(target, a, "http://x/admin/consult-orders?filter=exp-reverse", 200);

        assertThat(after).doesNotContain("order-row-" + target.getOrderToken());
        assertThat(filtered(a)).doesNotContain("order-row-" + target.getOrderToken());
    }

    @Test
    void unknownFilterMeansAll() throws Exception {
        ConsultOrder o = suspendedRefund(false);

        String html = mvc.perform(get("/admin/consult-orders").param("filter", "nope")
                        .with(authentication(reverser())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("order-row-" + o.getOrderToken());
    }

    @Test
    void verifyInsideFilteredViewKeepsTheFilteredSet() throws Exception {
        ConsultOrder o = suspendedRefund(true);
        Authentication a = auth(AdminAccountType.STAFF, AdminPermissions.ORDER_VIEW, AdminPermissions.ORDER_EDIT);

        String html = mvc.perform(hx(post("/admin/consult-orders/" + o.getOrderToken() + "/verify"),
                        "http://x/admin/consult-orders?filter=exp-reverse")
                        .param("status", "TO_VERIFY").with(authentication(a)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"orders-rows\"").contains("order-row-" + o.getOrderToken());
    }
}
