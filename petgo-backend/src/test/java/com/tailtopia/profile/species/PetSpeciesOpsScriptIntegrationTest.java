package com.tailtopia.profile.species;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.service.PetSpeciesChangeLog;
import com.tailtopia.support.ApiIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：V1.5.0 pet-species Story 1.4 · AC4 —— 运维脚本 {@code scripts/ops/pet-species/apply-mapping.sql} 与
 * {@code export-push-groups.sql}（{@code push_groups_view} 段）在真库上的行为。
 *
 * <p>临时表 / 临时视图是会话级的，所以全部在同一条 JDBC 连接上执行（Maven 工作目录是 {@code petgo-backend/}）。
 */
class PetSpeciesOpsScriptIntegrationTest extends ApiIntegrationTest {

    private static final Path APPLY = Path.of("../scripts/ops/pet-species/apply-mapping.sql");
    private static final Path EXPORT = Path.of("../scripts/ops/pet-species/export-push-groups.sql");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private MilestoneService milestones;

    @Autowired
    private PetSpeciesChangeLog changeLog;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    private long pet(User owner, String name, PetSpecies species, String breed) {
        PetProfile p = profiles.save(PetProfile.create(owner.getId(), species, name, null, breed,
                LocalDate.of(2022, 1, 1), null, "ops" + SEQ.incrementAndGet()));
        milestones.assignRoster(p.getId(), p.getMilestoneTrack());
        return p.getId();
    }

    /** 一行映射：{pet_id, pet_name, target_species, target_breed, confidence}。 */
    private record Row(long petId, String name, String target, String breed, String confidence) {
    }

    /** 同一连接：建 species_mapping → 插映射 → 执行 apply-mapping.sql；返回统计（kind/item → n）。 */
    private Map<String, Long> runMapping(List<Row> rows) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS species_mapping");
                st.execute("CREATE TEMP TABLE species_mapping (pet_id BIGINT, pet_name TEXT, current_type TEXT,"
                        + " target_species TEXT, target_breed TEXT, confidence TEXT, reason TEXT)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO species_mapping VALUES (?, ?, 'OTHER', ?, ?, ?, 'it')")) {
                for (Row r : rows) {
                    ps.setLong(1, r.petId());
                    ps.setString(2, r.name());
                    ps.setString(3, r.target());
                    ps.setString(4, r.breed());
                    ps.setString(5, r.confidence());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            Map<String, Long> stats = new HashMap<>();
            try (Statement st = c.createStatement()) {
                boolean rs = st.execute(Files.readString(APPLY, StandardCharsets.UTF_8));
                while (true) {
                    if (rs) {
                        try (var r = st.getResultSet()) {
                            while (r.next()) {
                                stats.put(r.getString("kind") + "/" + r.getString("item"), r.getLong("n"));
                            }
                        }
                    } else if (st.getUpdateCount() == -1) {
                        break;
                    }
                    rs = st.getMoreResults();
                }
            }
            return stats;
        }
    }

    private Map<String, Object> row(long petId) {
        return jdbc.queryForMap("SELECT pet_species, pet_type, milestone_track, breed, pet_type_changed_at"
                + " FROM pet_profiles WHERE id = ?", petId);
    }

    @Test
    void migratesHighRowsOnlyAndIsRerunnable() throws Exception {
        User owner = newUser();
        long rabbit = pet(owner, "Momo", PetSpecies.OTHER, null);
        long civet = pet(newUser(), "Pandan", PetSpecies.OTHER, "Musang lokal");
        long cat = pet(newUser(), "Oyen", PetSpecies.OTHER, null);
        long low = pet(newUser(), "Bubu", PetSpecies.OTHER, null);
        long wrongName = pet(newUser(), "Real", PetSpecies.OTHER, null);
        long userChanged = pet(newUser(), "Kiki", PetSpecies.OTHER, null);
        tx.executeWithoutResult(s -> changeLog.append(userChanged, PetSpecies.OTHER, PetSpecies.OTHER,
                PetSpeciesChangeSource.USER, null, null));

        List<Row> rows = List.of(
                new Row(rabbit, "Momo", "RABBIT", "Holland Lop", "HIGH"),
                new Row(civet, "Pandan", "CIVET", "Musang Pandan", "HIGH"),
                new Row(cat, " Oyen ", "CAT", "Kucing Kampung", "HIGH"),
                new Row(low, "Bubu", "HAMSTER", null, "LOW"),
                new Row(wrongName, "Other", "BIRD", null, "HIGH"),
                new Row(userChanged, "Kiki", "BIRD", null, "HIGH"),
                new Row(987_654_321L, "Ghost", "BIRD", null, "HIGH"),
                new Row(low + 1_000_000L, "NoTarget", "", null, "HIGH"));
        Map<String, Long> stats = runMapping(rows);

        assertThat(stats).containsEntry("migrated/RABBIT", 1L).containsEntry("migrated/CIVET", 1L)
                .containsEntry("migrated/CAT", 1L).containsEntry("skipped/LOW", 1L)
                .containsEntry("skipped/NAME_MISMATCH", 1L).containsEntry("skipped/ALREADY_CHANGED", 1L)
                .containsEntry("skipped/PET_NOT_FOUND", 1L).containsEntry("skipped/NO_TARGET", 1L);
        assertThat(row(rabbit)).containsEntry("pet_species", "RABBIT").containsEntry("pet_type", "OTHER")
                .containsEntry("breed", "Holland Lop");
        assertThat(row(civet)).containsEntry("pet_species", "CIVET").containsEntry("breed", "Musang lokal"); // 已有不覆盖
        assertThat(row(cat)).containsEntry("pet_species", "CAT").containsEntry("pet_type", "CAT")
                .containsEntry("milestone_track", "OTHER");
        assertThat(row(cat).get("pet_type_changed_at")).isNull();
        assertThat(row(low)).containsEntry("pet_species", "OTHER");
        assertThat(row(wrongName)).containsEntry("pet_species", "OTHER");
        assertThat(row(userChanged)).containsEntry("pet_species", "OTHER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_species_changes WHERE source = 'MIGRATION'"
                + " AND pet_profile_id IN (?, ?, ?)", Integer.class, rabbit, civet, cat)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT group_changed FROM pet_species_changes WHERE source = 'MIGRATION'"
                + " AND pet_profile_id = ?", Boolean.class, cat)).isTrue();
        assertThat(jdbc.queryForObject("SELECT type_changed FROM pet_species_changes WHERE source = 'MIGRATION'"
                + " AND pet_profile_id = ?", Boolean.class, civet)).isFalse();

        // 重跑幂等：迁移数 0、不报错、不多记。
        Map<String, Long> again = runMapping(rows);
        assertThat(again.keySet()).noneMatch(k -> k.startsWith("migrated/"));
        assertThat(again).containsEntry("skipped/NOT_OTHER", 3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_species_changes WHERE source = 'MIGRATION'"
                + " AND pet_profile_id IN (?, ?, ?)", Integer.class, rabbit, civet, cat)).isEqualTo(3);

        // 51 只猫场景（PRD 3.4 AC2）：迁移后里程碑仍是 G 清单。
        mvc.perform(get("/api/v1/pet-profiles/me/milestones").param("petId", String.valueOf(cat))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(ownerOf(cat))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].items[*].code").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.startsWith("G-"))));
        mvc.perform(get("/api/v1/me/newbie-tasks").param("petId", String.valueOf(cat))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(ownerOf(cat))))
                .andExpect(status().isOk());
    }

    private long ownerOf(long petId) {
        return jdbc.queryForObject("SELECT owner_id FROM pet_profiles WHERE id = ?", Long.class, petId);
    }

    @Test
    void duplicatePetIdAndUnknownSpeciesFailWholeBatch() {
        User owner = newUser();
        long a = pet(owner, "A", PetSpecies.OTHER, null);
        assertThatThrownBy(() -> runMapping(List.of(new Row(a, "A", "RABBIT", null, "HIGH"),
                new Row(a, "A", "RABBIT", null, "HIGH")))).isInstanceOf(SQLException.class)
                .hasMessageContaining("pet_id 重复");
        assertThatThrownBy(() -> runMapping(List.of(new Row(a, "A", "DRAGON", null, "HIGH"))))
                .isInstanceOf(SQLException.class).hasMessageContaining("非法 target_species");
        assertThat(row(a)).containsEntry("pet_species", "OTHER");
    }

    /** 读 export-push-groups.sql 的 push_groups_view 段（不含 psql 元命令）。 */
    private static String pushGroupsView() throws IOException {
        String sql = Files.readString(EXPORT, StandardCharsets.UTF_8);
        int from = sql.indexOf("-- BEGIN push_groups_view");
        int to = sql.indexOf("-- END push_groups_view");
        assertThat(from).isNotNegative();
        assertThat(to).isGreaterThan(from);
        return sql.substring(from, to);
    }

    @Test
    void exportGroupsAreDisjoint() throws Exception {
        User a = newUser();
        pet(a, "A", PetSpecies.OTHER, null);
        pet(a, "A2", PetSpecies.CAT, null); // 同时满足 C → 只进 A
        User b = newUser();
        long migrated = pet(b, "B", PetSpecies.RABBIT, null);
        jdbc.update("INSERT INTO pet_species_changes (pet_profile_id, from_species, to_species, type_changed,"
                + " group_changed, source) VALUES (?, 'OTHER', 'RABBIT', true, false, 'MIGRATION')", migrated);
        User c = newUser();
        pet(c, "C", PetSpecies.DOG, "Weimaraner"); // 手填 → C
        User catOk = newUser();
        pet(catOk, "Ok", PetSpecies.CAT, "Kucing Persia"); // 清单内 → 不发
        User d = newUser(); // 没宠物 → D，不导出
        User readOnly = newUser();
        long ro = pet(readOnly, "Ro", PetSpecies.OTHER, null);
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", ro);
        User deleted = newUser();
        pet(deleted, "Del", PetSpecies.OTHER, null);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.getId());

        Map<Long, List<String>> groups = new HashMap<>();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(pushGroupsView());
            try (var rs = st.executeQuery("SELECT user_id, grp FROM pet_species_push_groups")) {
                while (rs.next()) {
                    groups.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                }
            }
        }
        assertThat(groups.get(a.getId())).containsExactly("A");
        assertThat(groups.get(b.getId())).containsExactly("B");
        assertThat(groups.get(c.getId())).containsExactly("C");
        assertThat(groups).doesNotContainKeys(catOk.getId(), d.getId(), readOnly.getId(), deleted.getId());
        assertThat(groups.values()).allMatch(g -> g.size() == 1);
    }
}
