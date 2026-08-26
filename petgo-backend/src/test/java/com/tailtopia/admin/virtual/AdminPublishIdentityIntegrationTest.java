package com.tailtopia.admin.virtual;

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
import com.tailtopia.admin.virtual.domain.SeedRealAccountGrant;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.admin.virtual.repository.SeedRealAccountGrantRepository;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1 集成：运营发布身份池（V1.1.6 Story 12.1 · AB-3I）。
 *
 * <p><b>本 story 解决的是</b>：虚拟账号没有宠物档案（发不了成长日历）、没有粉丝、主页是空的 ——
 * 替代不了公司那几个人格化 IP 号；而此前后台的发布账号<b>只接受虚拟账号</b>。
 *
 * <h2>🛡 四条安全攸关断言，不得弱化</h2>
 * <ul>
 *   <li><b>不改 {@code account_type}</b>：纳入池只加一行授权关系。改类型会让那个真人号
 *       在 App 内的一切行为走进未被验证的分支。</li>
 *   <li><b>移出 ≠ 封号</b>：移出后该账号在 App 内仍能正常登录发帖，历史内容不动。</li>
 *   <li><b>独立权限码</b>：只有 {@code virtual_account.manage} 的人<b>不能</b>以真实身份发布 ——
 *       能管虚拟账号 ≠ 能以真人身份发言。</li>
 *   <li><b>移出前的排期提示不做任何处置</b>：不阻止移出、不自动取消排期、不自动转草稿。</li>
 * </ul>
 */
class AdminPublishIdentityIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SeedRealAccountGrantRepository grants;

    @Autowired
    private SeedContentHashRepository hashes;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private UserRepository users;

    @Autowired
    private com.tailtopia.content.repository.ContentPostRepository posts;

    @Autowired
    private AdminVirtualAccountService virtualAccounts;

    /**
     * 后台身份。
     *
     * <p>⚠️ POST 还须 {@code .with(csrf())} —— {@code /admin/**} 那条过滤链**保留了 CSRF**
     * （只有 API 链 disable）。少了它拿到 403，会被误读成"权限门没放行"。
     */
    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "ident-" + n + "@tailtopia.test", "身份池测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(principal, null,
                    new java.util.ArrayList<>(principal.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它。
        List<GrantedAuthority> auths = new java.util.ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private Authentication superAdmin() {
        return auth(AdminAccountType.SUPER_ADMIN);
    }

    /** 池内一个真实账号，返回它的 user。 */
    private User grantedRealAccount() throws Exception {
        User u = newUser();
        mvc.perform(post("/admin/publish-identities").with(authentication(superAdmin())).with(csrf())
                        .param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "市场部 IP 号，2026-08 授权"))
                .andExpect(status().is3xxRedirection());
        return u;
    }

    private long virtualAccount() {
        return virtualAccounts.create("虚拟号-" + SEQ.incrementAndGet(), null, 1L);
    }

    // ——————————————————— 🛡 AC2 不新增账号类型 ———————————————————

    /**
     * 🛡 <b>本条是本 story 最重要的一条</b>。纳入身份池只加一行授权关系，
     * 那个真人账号的 {@code account_type} 必须仍是 {@link AccountType#REAL}。
     *
     * <p>把它改成 {@code VIRTUAL} 是"看起来更省事"的做法 —— 后果是它在 App 内的
     * 登录、发帖、被查看全部走进为虚拟账号写的分支，而那些分支从来没有被真人流量验证过。
     */
    @Test
    void grantingKeepsTheAccountTypeUntouched() throws Exception {
        User u = grantedRealAccount();

        User reloaded = users.findById(u.getId()).orElseThrow();
        assertThat(reloaded.getAccountType()).isEqualTo(AccountType.REAL);
        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(grants.existsByUserIdAndStatus(u.getId(), SeedRealAccountGrant.Status.ACTIVE))
                .isTrue();
    }

    /** 授权说明必填 —— 它是"内部人冒充内部人"这条风险上唯一的追责依据。 */
    @Test
    void authorizationNoteIsRequired() throws Exception {
        User u = newUser();
        mvc.perform(post("/admin/publish-identities").with(authentication(superAdmin())).with(csrf())
                        .param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "   "))
                .andExpect(status().is3xxRedirection());

        assertThat(grants.existsByUserIdAndStatus(u.getId(), SeedRealAccountGrant.Status.ACTIVE))
                .as("说明为空不该纳入").isFalse();
    }

    // ——————————————————— AC2 放开发布断言 ———————————————————

    /** 池内真实账号可以作为批量发布的作者（此前被那句 accountType 硬断言挡着）。 */
    @Test
    void realAccountInThePoolCanPublish() throws Exception {
        User u = grantedRealAccount();

        mvc.perform(post("/admin/seed-batch").with(authentication(superAdmin())).with(csrf())
                        .param("virtualUserId", String.valueOf(u.getId()))
                        .param("lines", "以 IP 号发的一条-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        assertThat(hashes.countByAuthorId(u.getId())).isEqualTo(1);
    }

    /** 🛡 不在池内的真实账号仍然不能发 —— 放开的是"池内"，不是"所有真实账号"。 */
    @Test
    void realAccountOutsideThePoolStillCannotPublish() throws Exception {
        User outsider = newUser();

        mvc.perform(post("/admin/seed-batch").with(authentication(superAdmin())).with(csrf())
                        .param("virtualUserId", String.valueOf(outsider.getId()))
                        .param("lines", "不该发出去的一条-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        assertThat(hashes.countByAuthorId(outsider.getId())).isZero();
    }

    /** 移出之后不能再作为新内容的发布者。 */
    @Test
    void removedAccountCannotPublishAnyMore() throws Exception {
        User u = grantedRealAccount();
        mvc.perform(post("/admin/publish-identities/" + u.getId() + "/remove")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/admin/seed-batch").with(authentication(superAdmin())).with(csrf())
                        .param("virtualUserId", String.valueOf(u.getId()))
                        .param("lines", "移出后不该发出去-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        assertThat(hashes.countByAuthorId(u.getId())).isZero();
    }

    // ——————————————————— 🛡 AC3 移出 ≠ 封号 ———————————————————

    /**
     * 🛡 移出只收回"后台可代其发布"这一项。
     *
     * <p>这是运营最容易误解的一点，所以钉三样：账号仍启用、类型未变、
     * <b>并且他之前经后台发的内容还在</b>（历史内容与作者归属完全不受影响）。
     */
    @Test
    void removalIsNotABanAndLeavesHistoryIntact() throws Exception {
        User u = grantedRealAccount();
        String marker = "移出前发的一条-" + SEQ.incrementAndGet();
        mvc.perform(post("/admin/seed-batch").with(authentication(superAdmin())).with(csrf())
                        .param("virtualUserId", String.valueOf(u.getId()))
                        .param("lines", marker))
                .andExpect(status().is3xxRedirection());
        long publishedBefore = hashes.countByAuthorId(u.getId());

        mvc.perform(post("/admin/publish-identities/" + u.getId() + "/remove")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        User reloaded = users.findById(u.getId()).orElseThrow();
        assertThat(reloaded.isEnabled()).as("移出不是封号").isTrue();
        assertThat(reloaded.getAccountType()).isEqualTo(AccountType.REAL);
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(hashes.countByAuthorId(u.getId())).isEqualTo(publishedBefore);
    }

    /** 移出留痕、不删行 —— 否则"谁在什么时候把谁移出去的"就查不到了。 */
    @Test
    void removalKeepsTheGrantRowForAudit() throws Exception {
        User u = grantedRealAccount();
        mvc.perform(post("/admin/publish-identities/" + u.getId() + "/remove")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        SeedRealAccountGrant row = grants
                .findByUserIdAndStatus(u.getId(), SeedRealAccountGrant.Status.REMOVED).orElseThrow();
        assertThat(row.getRemovedBy()).isNotNull();
        assertThat(row.getRemovedAt()).isNotNull();
        assertThat(row.getAuthorizationNote()).isNotBlank();
    }

    /** 移出后可以再纳入（授权是有历史的，不是一次性的开关）。 */
    @Test
    void anAccountCanBeGrantedAgainAfterRemoval() throws Exception {
        User u = grantedRealAccount();
        mvc.perform(post("/admin/publish-identities/" + u.getId() + "/remove")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/admin/publish-identities").with(authentication(superAdmin())).with(csrf())
                        .param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "二次授权"))
                .andExpect(status().is3xxRedirection());

        assertThat(grants.existsByUserIdAndStatus(u.getId(), SeedRealAccountGrant.Status.ACTIVE))
                .isTrue();
    }

    // ——————————————————— 🛡 AC4 移出前的排期提示 ———————————————————

    /**
     * 确认页显示排期条数，而且<b>什么都不处置</b>。
     *
     * <p>🔴 这里的 0 是**真实统计的结果**：Story 13.1 起计数已接到 {@code seed_batch_rows}
     * （{@code ScheduledSeedRowCounter}），而本用例没有造任何排期行。
     * 本条钉的是"这一页存在、能显示这个数、且是只读的"；
     * 「有排期时数字对不对」由 {@code SeedBatchStateMachineIntegrationTest} 钉。
     */
    @Test
    void removeConfirmPageShowsTheScheduleCountAndChangesNothing() throws Exception {
        User u = grantedRealAccount();

        String html = mvc.perform(get("/admin/publish-identities/" + u.getId() + "/remove")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 🔴 断言的是**路径形状**而不是裸 id：id 是个小整数，"3" 之类会在 CSS 颜色里撞上，
        //    那种断言看着绿其实什么都没验（本轮早先踩过一次）。
        assertThat(html).contains("/admin/publish-identities/" + u.getId() + "/remove");

        // 🛡 看一眼确认页不该改任何东西 —— 授权仍生效。
        assertThat(grants.existsByUserIdAndStatus(u.getId(), SeedRealAccountGrant.Status.ACTIVE))
                .as("GET 确认页必须是只读的").isTrue();
    }

    /** ⚠️ 虚拟账号**禁用**走同一个确认页、同一个计数口径（两处各判一次，口径迟早分叉）。 */
    @Test
    void disablingAVirtualAccountGoesThroughTheSameConfirmPage() throws Exception {
        long virtualId = virtualAccount();

        mvc.perform(get("/admin/virtual-accounts/" + virtualId + "/disable")
                        .with(authentication(superAdmin())))
                .andExpect(status().isOk());

        assertThat(users.findById(virtualId).orElseThrow().isEnabled())
                .as("确认页是只读的，不该顺手禁用").isTrue();
    }

    // ——————————————————— 🛡 AC5 独立权限码 ———————————————————

    /**
     * 🛡 <b>能管虚拟账号 ≠ 能以真人身份发言。</b>
     *
     * <p>只持 {@code virtual_account.manage} 的人走批量发布这条既有路径时，
     * 选虚拟账号照旧可用，选池内真实账号必须被挡下 —— 后者的后果不可撤回
     * （内容会出现在那个真人的个人主页并推送给他的粉丝）。
     */
    @Test
    void virtualAccountManagerCannotPublishAsARealIdentity() throws Exception {
        User real = grantedRealAccount();
        Authentication onlyVirtual =
                auth(AdminAccountType.STAFF, AdminPermissions.VIRTUAL_ACCOUNT_MANAGE);

        mvc.perform(post("/admin/seed-batch").with(authentication(onlyVirtual)).with(csrf())
                        .param("virtualUserId", String.valueOf(real.getId()))
                        .param("lines", "越权发的一条-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        assertThat(hashes.countByAuthorId(real.getId()))
                .as("只有 virtual_account.manage 不该能以真人身份发布").isZero();
    }

    /** 同一个人选虚拟账号仍然发得出去 —— 别把常用路径一起锁死。 */
    @Test
    void virtualAccountManagerCanStillPublishAsAVirtualAccount() throws Exception {
        long virtualId = virtualAccount();
        Authentication onlyVirtual =
                auth(AdminAccountType.STAFF, AdminPermissions.VIRTUAL_ACCOUNT_MANAGE);

        mvc.perform(post("/admin/seed-batch").with(authentication(onlyVirtual)).with(csrf())
                        .param("virtualUserId", String.valueOf(virtualId))
                        .param("lines", "虚拟号照常发-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        assertThat(hashes.countByAuthorId(virtualId)).isEqualTo(1);
    }

    /** 🛡 没有 {@code seed.publish_as_real} 的人连纳入都不行。 */
    @Test
    void grantingRequiresTheDedicatedPermission() throws Exception {
        User u = newUser();
        mvc.perform(post("/admin/publish-identities")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.VIRTUAL_ACCOUNT_MANAGE)))
                        .with(csrf())
                        .param("userId", String.valueOf(u.getId()))
                        .param("authorizationNote", "越权纳入"))
                .andExpect(status().isForbidden());

        assertThat(grants.existsByUserIdAndStatus(u.getId(), SeedRealAccountGrant.Status.ACTIVE))
                .isFalse();
    }

    /**
     * 🛡 只持 {@code seed.publish_as_real} 的人**看得见那一页**。
     *
     * <p>侧栏条件与控制器 {@code @PreAuthorize} 必须逐字一致 ——
     * 不一致的表现是"看得见入口、点进去 403"，而这类问题在开发机上很难自己撞到。
     */
    @Test
    void seedPublishAsRealAloneCanOpenThePage() throws Exception {
        mvc.perform(get("/admin/virtual-accounts")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.SEED_PUBLISH_AS_REAL))))
                .andExpect(status().isOk());
    }

    // ——————————————————— AC7 列表与计数 ———————————————————

    /**
     * 「经后台发布内容数」🛡 <b>只统计经后台代发的</b>。
     *
     * <p>该账号在 App 内自主发布的内容压根不进这张表 —— 运营看这个数是为了核对
     * "我们发了多少"，把持有人自己发的帖算进来这个数就没用了（AC7/AC8）。
     */
    @Test
    void publishedCountOnlyCountsAdminSidePublishing() throws Exception {
        User u = grantedRealAccount();

        // 持有人在 App 内自己发一条（走用户接口，不经后台）。
        mvc.perform(post("/api/v1/content-posts")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION,
                                userBearer(u.getId()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .content("{\"type\":\"DAILY\",\"text\":\"我自己发的-"
                                + SEQ.incrementAndGet() + "\"}"))
                .andExpect(status().isCreated());

        // 🔴 先证明"他确实发出去了" —— 否则下面那句 isZero() 只是在验一个没发生的事。
        // ⚠️ 必须用 Objects.equals：`ContentPost#getAuthorId()` 返回的是**装箱 Long**，
        //    用 `==` 比的是对象引用 —— id 超过 127 就跳出 Long 缓存，恒为 false。
        //    那样写这条断言会永远看到 0，而"0"恰好是它期望的另一半，于是**安静地通过**。
        assertThat(posts.findAll().stream()
                .filter(p -> java.util.Objects.equals(p.getAuthorId(), u.getId())).count())
                .as("持有人自主发布的内容确实进了 content_posts").isEqualTo(1);
        assertThat(hashes.countByAuthorId(u.getId()))
                .as("持有人自主发布的不该计入「经后台发布内容数」").isZero();
    }

    /** AC7 最后一条：以真实账号发布须记下**实际操作的后台账号**（出事要追到人）。 */
    @Test
    void everyAdminSidePostRecordsWhichAdminPressedPublish() throws Exception {
        User u = grantedRealAccount();
        Authentication admin = superAdmin();
        long adminId = ((AdminUserDetails) admin.getPrincipal()).getAdminAccountId();

        mvc.perform(post("/admin/seed-batch").with(authentication(admin)).with(csrf())
                        .param("virtualUserId", String.valueOf(u.getId()))
                        .param("lines", "要留操作人痕迹的一条-" + SEQ.incrementAndGet()))
                .andExpect(status().is3xxRedirection());

        assertThat(hashes.findAll().stream()
                .filter(h -> h.getAuthorId() == u.getId())
                .map(h -> h.getPublishedByAdminId()))
                .as("每条都要记下按发布键的后台账号")
                .containsOnly(adminId);
    }
}
