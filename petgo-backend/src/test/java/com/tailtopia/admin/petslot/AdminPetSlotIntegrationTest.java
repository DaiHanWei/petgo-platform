package com.tailtopia.admin.petslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1 集成：V1.4.0 multi-pet Story 4.3 —— 用户抽屉「宠物档案」全部宠物 + 「宠物与名额」页签 + 运营补发名额
 * （AC1 / AC3–AC8，UI 稿 L0–L6）。需 Docker postgres + redis（云端不跑，L1 待本地验收）。
 */
class AdminPetSlotIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private PetProfileRepository pets;
    @Autowired
    private PetSlotRentalRepository rentals;
    @Autowired
    private PetSlotPurchaseRepository purchases;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.tailtopia.account.repository.AccountDeletionRepository deletions;

    private record Admin(Authentication auth, long id, String name) {
    }

    private Admin admin(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        String name = "ops_petslot_" + n;
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "petslot-" + n + "@tailtopia.test", name, "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new Admin(new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities())), acc.getId(),
                    name);
        }
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN")); // /admin/** URL 层要求；少了 403 会假绿
        for (String s : permissions) {
            auths.add(new SimpleGrantedAuthority(s));
        }
        return new Admin(new TestingAuthenticationToken(p, null, auths), acc.getId(), name);
    }

    /** OPS_MANAGER 枚举角色登录（权限码直接取枚举，同 RolePermissionResolver 对 OPS_MANAGER 的口径）。 */
    private Admin opsManager() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.create("petslot-mgr-" + n + "@tailtopia.test",
                "主管" + n, AdminRole.OPS_MANAGER, null));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), null, AdminAccountType.STAFF,
                new HashSet<>(AdminRole.OPS_MANAGER.permissionCodes()));
        return new Admin(new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities())), acc.getId(),
                "主管" + n);
    }

    private PetProfile pet(User u, String name) {
        return pets.save(PetProfile.create(u.getId(), PetType.CAT, name, null, null, null, null,
                "it-card-" + SEQ.incrementAndGet()));
    }

    /** 给用户造 n 个生效中的月租名额（RENT，PawCoin 已付）。 */
    private void rentLive(User u, int n) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < n; i++) {
            PetSlotPurchase p = PetSlotPurchase.pending("itp" + UUID.randomUUID().toString().replace("-", "")
                            .substring(0, 24), u.getId(), PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                    com.tailtopia.pay.domain.PayChannel.PAWCOIN, null, "it-rent-" + SEQ.incrementAndGet(), null, now);
            p.settle(PetSlotPurchaseStatus.PAID, now);
            p = purchases.saveAndFlush(p);
            rentals.saveAndFlush(PetSlotRental.start("itr" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 24), u.getId(), PetSlotRentalSource.RENT, now, 30, p.getId()));
        }
    }

    private String drawer(User u, Admin a) throws Exception {
        return mvc.perform(get("/admin/users/" + u.getId() + "/drawer").param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions grant(User u, Admin a, String days, String reason,
            String token) throws Exception {
        return mvc.perform(post("/admin/users/" + u.getId() + "/pet-slots/grant").param("lang", "zh_CN")
                .param("days", days).param("reason", reason).param("grantToken", token)
                .header("HX-Request", "true").header("HX-Target", "user-drawer-err")
                .with(authentication(a.auth())).with(csrf()));
    }

    private long auditRows(User u) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM admin_audit_logs WHERE action_type = 'PET_SLOT_GRANTED'"
                + " AND target_id = ?", Long.class, String.valueOf(u.getId()));
        return n == null ? 0 : n;
    }

    private long adminRentals(User u) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM pet_slot_rentals WHERE user_id = ? AND source = 'ADMIN_GRANT'",
                Long.class, u.getId());
        return n == null ? 0 : n;
    }

    @Test
    void petsTabListsAllThreePets() throws Exception {
        User u = newUser();
        pet(u, "Orenji");
        pet(u, "Kopi");
        pet(u, "Boba");

        String html = drawer(u, admin(AdminAccountType.SUPER_ADMIN));

        String petsPanel = html.substring(html.indexOf("data-utab-panel=\"pets\""),
                html.indexOf("data-utab-panel=\"posts\""));
        assertThat(petsPanel).contains("Orenji").contains("Kopi").contains("Boba");
        assertThat(petsPanel.indexOf("Orenji")).isLessThan(petsPanel.indexOf("Kopi"));
        assertThat(petsPanel.indexOf("Kopi")).isLessThan(petsPanel.indexOf("Boba"));
    }

    @Test
    void viewerSeesBlocksAndAGreyedGrantCard() throws Exception {
        User u = newUser();
        pet(u, "Orenji");

        String html = drawer(u, admin(AdminAccountType.STAFF, AdminPermissions.USER_VIEW));

        String panel = html.substring(html.indexOf("id=\"user-petslots-panel\""));
        assertThat(panel).contains("当前名额").contains("默认 / 等级").contains("月租（生效中）").contains("已建宠物")
                .contains("宠物状态").contains("Orenji").contains("该用户还没有任何月租名额")
                .doesNotContain("购买记录")
                .contains("需要「补发宠物名额」权限")
                .doesNotContain("pet-slots/grant");
    }

    @Test
    void grantSevenDaysAddsSlotAuditsAndSwapsOnlyThePanel() throws Exception {
        User u = newUser();
        pet(u, "Orenji");
        Admin a = admin(AdminAccountType.STAFF, AdminPermissions.USER_VIEW, AdminPermissions.USER_PET_SLOT_ADJUST);
        String before = drawer(u, a);
        assertThat(before).contains("pet-slots/grant").contains("name=\"grantToken\"");
        String token = UUID.randomUUID().toString();
        Instant t0 = Instant.now();

        String html = grant(u, a, "7", "Klaim: QRIS sukses tapi slot belum masuk", token)
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Retarget", "#user-petslots-panel"))
                .andExpect(header().string("HX-Reswap", "outerHTML"))
                .andReturn().getResponse().getContentAsString();

        assertThat(adminRentals(u)).isEqualTo(1);
        PetSlotRental r = rentals.findByUserIdOrderByExpiresAtAscIdAsc(u.getId()).get(0);
        assertThat(r.getSource()).isEqualTo(PetSlotRentalSource.ADMIN_GRANT);
        assertThat(Duration.between(r.getStartsAt(), r.getExpiresAt())).isEqualTo(Duration.ofDays(7));
        assertThat(r.getStartsAt()).isAfterOrEqualTo(t0.minusSeconds(5));
        assertThat(purchases.findByIdemKey("ADMIN_GRANT:" + token)).get()
                .satisfies(p -> {
                    assertThat(p.getKind()).isEqualTo(PetSlotPurchaseKind.ADMIN_GRANT);
                    assertThat(p.getStatus()).isEqualTo(PetSlotPurchaseStatus.PAID);
                    assertThat(p.getTotal()).isZero();
                    assertThat(p.getChannel()).isNull();
                    assertThat(p.getOperatorAdminId()).isEqualTo(a.id());
                    assertThat(p.getDays()).isEqualTo(7);
                });
        assertThat(auditRows(u)).isEqualTo(1);
        assertThat(html).contains("id=\"user-petslots-panel\"").doesNotContain("id=\"user-drawer-panel\"")
                .contains("运营补发 · " + a.name() + " · 7 天")
                .contains("已补发 1 个宠物名额（7 天）")
                .doesNotContain("value=\"" + token + "\"");
        // 当前名额 1 → 2（默认 1 + 补发 1）
        assertThat(html).containsPattern("当前名额</div>\\s*<div class=\"ud-ps-v\">2<");

        // 同 token 重放（双击 / 回退重提）：不新增名额、不重复写审计，toast 照常
        grant(u, a, "7", "Klaim: QRIS sukses tapi slot belum masuk", token).andExpect(status().isOk());
        assertThat(adminRentals(u)).isEqualTo(1);
        assertThat(auditRows(u)).isEqualTo(1);
    }

    @Test
    void fullQuotaIs422AndTheCardIsGreyed() throws Exception {
        User u = newUser();
        rentLive(u, 4); // 默认 1 + 月租 4 = 5
        Admin a = admin(AdminAccountType.SUPER_ADMIN);

        assertThat(drawer(u, a)).contains("已达 5 个名额上限，不能再补发");
        grant(u, a, "7", "r", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#user-drawer-err"));
        assertThat(adminRentals(u)).isZero();
        assertThat(auditRows(u)).isZero();
    }

    @Test
    void invalidDaysAndBlankReasonAre422() throws Exception {
        User u = newUser();
        Admin a = admin(AdminAccountType.SUPER_ADMIN);
        grant(u, a, "0", "r", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        grant(u, a, "abc", "r", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        grant(u, a, "7", " ", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertThat(adminRentals(u)).isZero();
    }

    /** code review #2：注销已受理、@Async 级联未跑完（deletedAt 仍空）→ 补发 422，页签不渲染补发卡。 */
    @Test
    void deletionInProgressRejectsGrantAndHidesTheCard() throws Exception {
        User u = newUser();
        deletions.save(com.tailtopia.account.domain.AccountDeletion.request(u.getId()));
        Admin a = admin(AdminAccountType.SUPER_ADMIN);

        assertThat(drawer(u, a)).doesNotContain("pet-slots/grant");
        grant(u, a, "7", "r", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertThat(adminRentals(u)).isZero();
    }

    /** code review #4：超长 token → 422（原来撑爆 idem_key VARCHAR(160) → 500）。 */
    @Test
    void overlongTokenIs422() throws Exception {
        User u = newUser();
        grant(u, admin(AdminAccountType.SUPER_ADMIN), "7", "r", "a".repeat(149))
                .andExpect(status().isUnprocessableEntity());
        assertThat(adminRentals(u)).isZero();
    }

    @Test
    void withoutTheCodePostIs403() throws Exception {
        User u = newUser();
        grant(u, admin(AdminAccountType.STAFF, AdminPermissions.USER_VIEW), "7", "r", UUID.randomUUID().toString())
                .andExpect(status().isForbidden());
        assertThat(adminRentals(u)).isZero();
    }

    @Test
    void opsManagerEnumRoleCanGrant() throws Exception {
        User u = newUser();
        grant(u, opsManager(), "3", "客诉补偿", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(adminRentals(u)).isEqualTo(1);
    }

    @Test
    void grantRestoresAReadOnlyPet() throws Exception {
        User u = newUser();
        pet(u, "Orenji");
        PetProfile second = pet(u, "Kopi");
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", second.getId());

        grant(u, admin(AdminAccountType.SUPER_ADMIN), "7", "到期误判补偿", UUID.randomUUID().toString())
                .andExpect(status().isOk());

        String status = jdbc.queryForObject("SELECT status FROM pet_profiles WHERE id = ?", String.class,
                second.getId());
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void nonHtmxPostRedirectsBackToTheDrawer() throws Exception {
        User u = newUser();
        mvc.perform(post("/admin/users/" + u.getId() + "/pet-slots/grant").param("days", "7").param("reason", "r")
                        .param("grantToken", UUID.randomUUID().toString())
                        .with(authentication(admin(AdminAccountType.SUPER_ADMIN).auth())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users?open=" + u.getId()));
        assertThat(adminRentals(u)).isEqualTo(1);
    }
}
