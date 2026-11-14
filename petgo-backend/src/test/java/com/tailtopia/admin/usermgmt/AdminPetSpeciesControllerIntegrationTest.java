package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：V1.5.0 pet-species Story 2.1 · AC3.5 —— 运营代改：403 / 原因空 422 / 相同 422 / 兔 → 仓鼠成功（清品种、冷却不变、
 * 修改记录 ADMIN、审计 1 条、track 不变）/ 猫 → 兔 track 切 OTHER / 用户冷却中仍能改；成功片段含 toast 与记录表。
 */
class AdminPetSpeciesControllerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private PetProfileRepository pets;
    @Autowired
    private MilestoneService milestones;
    @Autowired
    private JdbcTemplate jdbc;

    private record Admin(Authentication auth, long id) {
    }

    private Admin admin(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "petspecies-" + n + "@tailtopia.test", "ops_ps_" + n, "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(), acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new Admin(new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities())), acc.getId());
        }
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String s : permissions) {
            auths.add(new SimpleGrantedAuthority(s));
        }
        return new Admin(new TestingAuthenticationToken(p, null, auths), acc.getId());
    }

    private PetProfile pet(User u, PetSpecies s) {
        PetProfile p = pets.save(PetProfile.create(u.getId(), s, "Mochi", null, "Holland Lop", LocalDate.of(2025, 9, 12),
                null, "ps-card-" + SEQ.incrementAndGet()));
        milestones.assignRoster(p.getId(), p.getMilestoneTrack());
        return p;
    }

    private ResultActions change(Admin a, User u, PetProfile p, String species, String reason) throws Exception {
        return mvc.perform(post("/admin/users/" + u.getId() + "/pets/" + p.getId() + "/species").param("lang", "zh_CN")
                .param("species", species).param("reason", reason)
                .header("HX-Request", "true").header("HX-Target", "confirm-modal-err")
                .with(authentication(a.auth())).with(csrf()));
    }

    private Map<String, Object> row(long petId) {
        return jdbc.queryForMap("SELECT pet_species, breed, pet_type_changed_at, milestone_track FROM pet_profiles"
                + " WHERE id = ?", petId);
    }

    @Test
    void withoutPermissionIs403() throws Exception {
        User u = newUser();
        PetProfile p = pet(u, PetSpecies.RABBIT);
        change(admin(AdminAccountType.STAFF, AdminPermissions.USER_VIEW), u, p, "HAMSTER", "x")
                .andExpect(status().isForbidden());
        Admin staff = admin(AdminAccountType.STAFF, AdminPermissions.USER_VIEW, AdminPermissions.USER_PET_TYPE_CHANGE);
        change(staff, u, p, "HAMSTER", "工单 1").andExpect(status().isOk());
    }

    @Test
    void emptyReasonAndSameTypeAre422InModal() throws Exception {
        User u = newUser();
        PetProfile p = pet(u, PetSpecies.RABBIT);
        Admin a = admin(AdminAccountType.SUPER_ADMIN);
        change(a, u, p, "HAMSTER", "  ").andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(Matchers.containsString("请填写修改原因")));
        change(a, u, p, "RABBIT", "x").andExpect(status().isUnprocessableEntity());
        assertThat(row(p.getId())).containsEntry("pet_species", "RABBIT");
    }

    @Test
    void rabbitToHamsterSucceedsWithoutTouchingCooldown() throws Exception {
        User u = newUser();
        PetProfile p = pet(u, PetSpecies.RABBIT);
        Instant userChanged = Instant.parse("2026-11-24T12:05:00Z");
        jdbc.update("UPDATE pet_profiles SET pet_type_changed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(userChanged), p.getId()); // 用户冷却中
        Admin a = admin(AdminAccountType.SUPER_ADMIN);

        change(a, u, p, "HAMSTER", "用户客诉（工单 #8812）").andExpect(status().isOk())
                .andExpect(header().string("HX-Retarget", "#user-pets-panel"))
                .andExpect(content().string(Matchers.containsString("已修改")))
                .andExpect(content().string(Matchers.containsString("类型修改记录")))
                .andExpect(content().string(Matchers.containsString("工单 #8812")));

        Map<String, Object> r = row(p.getId());
        assertThat(r).containsEntry("pet_species", "HAMSTER").containsEntry("milestone_track", "OTHER");
        assertThat(r.get("breed")).isNull();
        assertThat(((java.sql.Timestamp) r.get("pet_type_changed_at")).toInstant()).isEqualTo(userChanged);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_species_changes WHERE pet_profile_id = ?"
                + " AND source = 'ADMIN' AND operator_admin_id = ? AND reason = '用户客诉（工单 #8812）'",
                Integer.class, p.getId(), a.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_logs WHERE action_type = 'PET_SPECIES_CHANGED'"
                + " AND target_id = ? AND summary NOT LIKE '%8812%'", Integer.class, String.valueOf(p.getId())))
                .isEqualTo(1);
    }

    @Test
    void catToRabbitSwitchesTrack() throws Exception {
        User u = newUser();
        PetProfile p = pet(u, PetSpecies.CAT);
        change(admin(AdminAccountType.SUPER_ADMIN), u, p, "RABBIT", "选错").andExpect(status().isOk());
        assertThat(row(p.getId())).containsEntry("milestone_track", "OTHER");
        assertThat(row(p.getId()).get("pet_type_changed_at")).isNull();
    }

    @Test
    void confirmModalListsAllSpecies() throws Exception {
        User u = newUser();
        PetProfile p = pet(u, PetSpecies.RABBIT);
        mvc.perform(post("/admin/users/" + u.getId() + "/pets/" + p.getId() + "/species/confirm").param("lang", "zh_CN")
                        .header("HX-Request", "true").header("HX-Target", "user-drawer-err")
                        .with(authentication(admin(AdminAccountType.SUPER_ADMIN).auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Retarget", "#confirm-host"))
                .andExpect(content().string(Matchers.containsString("代改宠物类型")))
                .andExpect(content().string(Matchers.containsString("<optgroup")))
                .andExpect(content().string(Matchers.containsString("value=\"MOUSE\"")))
                .andExpect(content().string(Matchers.containsString("name=\"reason\"")));
    }
}
