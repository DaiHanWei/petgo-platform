package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：用户手机号查看与召回名单导出（Story 11.4 · AB-11A）。
 *
 * <p>🛡 三条安全攸关断言，不得弱化：
 * <ul>
 *   <li>🔴 **无查看权限时，号码根本不在响应里** —— 不是"页面上看不见"。
 *       只在模板里隐藏 = 数据已经到了浏览器，看源码就能拿到。</li>
 *   <li>🔴 **导出是第二个权限码** —— 只有查看权限不能导出。
 *       导出把 PII 批量带出系统，风险高一档。</li>
 *   <li>🔴 **手机号不得以别名字段转手** —— 日志脱敏按字段名匹配，
 *       叫 phoneNumber / mobile / contact 都会绕过脱敏、让真实号码落盘。</li>
 * </ul>
 */
class AdminUserPhoneIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "phone-" + n + "@tailtopia.test", "手机号测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(principal, null,
                    new java.util.ArrayList<>(principal.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它。
        List<org.springframework.security.core.GrantedAuthority> auths = new java.util.ArrayList<>();
        auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    /** 造一个带手机号的用户。号码每次唯一，避免与共享库里别的数据混淆。 */
    private User userWithPhone() {
        User u = newUser();
        String phone = "+62812" + (7000000 + (SEQ.incrementAndGet() % 1000000));
        u.setPhone(phone);
        return userRepo.save(u);
    }

    private User userWithoutPhone(String blankKind) {
        User u = newUser();
        // FR-70 允许留空保存以撤回号码（写 null）；历史数据也可能是空串。
        u.setPhone("empty".equals(blankKind) ? "" : null);
        return userRepo.save(u);
    }

    // ——————————————————— 🔴 AC1 服务端就不下发 ———————————————————

    @Test
    void withPhoneViewPermissionTheNumberIsRendered() throws Exception {
        User u = userWithPhone();
        String html = mvc.perform(get("/admin/users/" + u.getId())
                        .with(authentication(auth(AdminAccountType.STAFF, "user.view", "user.phone_view")))
                        .param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("有权限时应能看到号码").contains(u.getPhone());
    }

    /**
     * 🔴 **无查看权限 → 号码根本不在响应体里。**
     *
     * <p>这条断言的是"响应里没有这串字符"，而不是"页面上不显示" ——
     * 后者用 CSS 就能"实现"，而数据其实已经发到浏览器了。
     */
    @Test
    void withoutPhoneViewPermissionTheNumberNeverReachesTheResponse() throws Exception {
        User u = userWithPhone();
        String phone = u.getPhone();
        String html = mvc.perform(get("/admin/users/" + u.getId())
                        .with(authentication(auth(AdminAccountType.STAFF, "user.view")))
                        .param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html)
                .as("🛡 无 user.phone_view 时号码不得出现在响应里（服务端就不装，不是前端隐藏）")
                .doesNotContain(phone);
    }

    // ——————————————————— AC4 筛选：两种空都算未填写 ———————————————————

    @Test
    void filterTreatsBothNullAndEmptyStringAsNotProvided() throws Exception {
        User withPhone = userWithPhone();
        User nullPhone = userWithoutPhone("null");
        User emptyPhone = userWithoutPhone("empty");

        String empty = mvc.perform(get("/admin/users").param("phone", "empty")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(empty).as("null 手机号算未填写").contains(">" + nullPhone.getId() + "<");
        assertThat(empty).as("🔴 空串也算未填写 —— FR-70 允许留空保存以撤回号码")
                .contains(">" + emptyPhone.getId() + "<");
        assertThat(empty).as("已填写的不该出现在未填写名单里")
                .doesNotContain(">" + withPhone.getId() + "<");

        String filled = mvc.perform(get("/admin/users").param("phone", "filled")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(filled).contains(">" + withPhone.getId() + "<");
        assertThat(filled).doesNotContain(">" + emptyPhone.getId() + "<");
    }

    /** 🛡 列表页只显示"填了 / 没填"，不显示号码本身。 */
    @Test
    void listNeverShowsTheNumberItself() throws Exception {
        User u = userWithPhone();
        String html = mvc.perform(get("/admin/users").param("phone", "filled")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))).param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("列表里不该出现明文号码 —— 少一处 PII 就少一个泄漏面")
                .doesNotContain(u.getPhone());
    }

    // ——————————————————— 🔴 AC1/AC5 导出是第二个权限 ———————————————————

    @Test
    void exportRequiresItsOwnPermission() throws Exception {
        // 只有查看权限 → 导不出
        mvc.perform(get("/admin/users/phone-recall.csv")
                        .with(authentication(auth(AdminAccountType.STAFF, "user.view", "user.phone_view"))))
                .andExpect(status().isForbidden());
        // 有导出权限 → 可以
        mvc.perform(get("/admin/users/phone-recall.csv")
                        .with(authentication(auth(AdminAccountType.STAFF, "user.view", "user.phone_export"))))
                .andExpect(status().isOk());
    }

    /** 🛡 名单含已封号账号，但每行标注状态 —— 不自动剔除，由运营判断。 */
    @Test
    void exportKeepsBannedAccountsButFlagsTheirStatus() throws Exception {
        User active = userWithPhone();
        User banned = userWithPhone();
        banned.deactivate();
        userRepo.save(banned);

        String csv = mvc.perform(get("/admin/users/phone-recall.csv").param("phone", "filled")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).as("首行是表头").startsWith("user_id,display_name,phone,account_status");
        assertThat(csv).as("🛡 已封号账号不得被自动剔除").contains(String.valueOf(banned.getId()));
        assertThat(csv).as("必须标注状态，否则运营会在不知情下给封号用户发召回")
                .contains("DEACTIVATED");
        assertThat(csv).contains(String.valueOf(active.getId())).contains("ACTIVE");
    }

    /** 昵称里的逗号不能把整份名单的列错开。 */
    @Test
    void csvEscapesCommasInNames() throws Exception {
        User u = userWithPhone();
        u.setNickname("张三, 李四");
        userRepo.save(u);
        String csv = mvc.perform(get("/admin/users/phone-recall.csv").param("phone", "filled")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("\"张三, 李四\"");
    }

    // ——————————————————— 🔴 AC2 禁止别名字段（静态断言） ———————————————————

    /**
     * 🔴 **本 story 涉及的源码里不得出现手机号的别名字段。**
     *
     * <p>机制很具体：日志脱敏是**按字段名匹配**的（见 `users.phone` 那条迁移的注释）——
     * 叫 phoneNumber / mobile / contact 都不会命中脱敏名单，
     * 一旦以别名转手该值并被打进日志，真实号码就明晃晃落盘了。
     *
     * <p>这条是静态源码断言：它不验证运行时行为，验证的是**没人在这几个文件里起了别名**。
     */
    @Test
    void noAliasFieldNamesForThePhoneValue() throws Exception {
        List<String> files = List.of(
                "src/main/java/com/tailtopia/admin/usermgmt/dto/AdminUserDetailView.java",
                "src/main/java/com/tailtopia/admin/usermgmt/dto/AdminUserRow.java",
                "src/main/java/com/tailtopia/admin/usermgmt/service/AdminUserService.java",
                "src/main/java/com/tailtopia/admin/usermgmt/web/AdminUserController.java");
        for (String f : files) {
            String src = Files.readString(Path.of(f));
            // 只查"标识符形态"的别名（含大小写变体），注释里提到别名是允许的 ——
            // 本测试的注释本身就要提它们，所以按 `别名 =` / `别名(` / `别名;` 这类用法匹配。
            for (String alias : List.of("phoneNumber", "mobile", "contactNumber")) {
                boolean usedAsIdentifier = Stream.of(alias + " =", alias + ";", alias + ")",
                                alias + ",", "String " + alias, "getPhoneNumber")
                        .anyMatch(src::contains);
                assertThat(usedAsIdentifier)
                        .as("🔴 %s 里不得用 `%s` 这类别名转手手机号 —— "
                                + "日志脱敏按字段名匹配，别名会绕过脱敏让真实号码落盘", f, alias)
                        .isFalse();
            }
        }
    }
}
