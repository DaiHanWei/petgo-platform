package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * V1.3.4 Story 4.1 · AC6 · L1（需 postgres）：{@code V20261012_1105__milestones_checkin_tailsonality.sql}
 * 的名册补行 + 存量回填。迁移脚本在测试里<b>再执行两次</b>（Flyway 启动时已跑过一次，造数在其后），
 * 验证语义与幂等。
 *
 * <ul>
 *   <li>猫 A：两次打卡 + 两次完整解读 → 打卡 / 性格测试项均按「首次」时间点亮，celebrated_at = completed_at；</li>
 *   <li>狗 B：只单独买过配型（match_unlocked_at 非空、unlocked_at 为空）、无打卡 → 补名册行、不点亮；</li>
 *   <li>通用 C：无任何数据 → 补名册行（G-S10 / G-S11）、不点亮；</li>
 *   <li>宠物 D：无任何名册行 → 迁移不碰（惰性生成完整名册由 getMilestones 负责）。</li>
 * </ul>
 * 迁移直接写表、不经 MilestoneCompletionService → notifications 不得新增行。
 */
class MilestoneCheckinTailsonalityBackfillIntegrationTest extends ApiIntegrationTest {

    private static final String SCRIPT = "db/migration/V20261012_1105__milestones_checkin_tailsonality.sql";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private PlaceRepository places;
    @Autowired
    private MilestoneService milestoneService;

    @Test
    void backfillsRosterAndFirstTimesIdempotentlyWithoutNotifications() throws Exception {
        User ua = newUser();
        User ub = newUser();
        User uc = newUser();
        User ud = newUser();
        long a = rosteredPetWithoutNewItems(ua, PetType.CAT, "C-S17", "C-S18");
        long b = rosteredPetWithoutNewItems(ub, PetType.DOG, "D-S17", "D-S18");
        long c = rosteredPetWithoutNewItems(uc, PetType.OTHER, "G-S10", "G-S11");
        long d = newPet(ud, PetType.CAT); // 无名册

        Place place = newPlace(ua.getId());
        Place place2 = newPlace(ua.getId());
        checkin(ua.getId(), place, a, "2026-10-01T03:00:00Z");
        checkin(ua.getId(), place2, a, "2026-10-05T03:00:00Z");
        tailsonalityResult(ua.getId(), a, "CAT", "2026-10-02T03:00:00Z", null);
        tailsonalityResult(ua.getId(), a, "CAT", "2026-10-06T03:00:00Z", null);
        tailsonalityResult(ub.getId(), b, "DOG", null, "2026-10-03T03:00:00Z");

        List<Long> userIds = List.of(ua.getId(), ub.getId(), uc.getId(), ud.getId());
        long notificationsBefore = countNotifications(userIds);

        runScript();
        runScript();

        // A：名册行 + 首次时间
        assertRosterRow(a, "C-S17", 32);
        assertRosterRow(a, "C-S18", 33);
        Map<String, Object> aCheckin = completion(a, "C-S17");
        assertThat(aCheckin).isNotNull();
        assertThat(((Timestamp) aCheckin.get("completed_at")).toInstant())
                .isEqualTo(Instant.parse("2026-10-01T03:00:00Z"));
        assertThat(aCheckin.get("celebrated_at")).isEqualTo(aCheckin.get("completed_at"));
        assertThat(aCheckin.get("source")).isEqualTo("SYSTEM_AUTO");
        assertThat(aCheckin.get("linked_content_id")).isNull();
        Map<String, Object> aTs = completion(a, "C-S18");
        assertThat(aTs).isNotNull();
        assertThat(((Timestamp) aTs.get("completed_at")).toInstant())
                .isEqualTo(Instant.parse("2026-10-02T03:00:00Z"));
        assertThat(aTs.get("celebrated_at")).isEqualTo(aTs.get("completed_at"));
        assertThat(aTs.get("source")).isEqualTo("SYSTEM_AUTO");

        // B：只单买配型不算；无打卡
        assertRosterRow(b, "D-S17", 32);
        assertRosterRow(b, "D-S18", 33);
        assertThat(completion(b, "D-S17")).isNull();
        assertThat(completion(b, "D-S18")).isNull();

        // C：通用物种 code / sort_order
        assertRosterRow(c, "G-S10", 17);
        assertRosterRow(c, "G-S11", 18);
        assertThat(completion(c, "G-S10")).isNull();
        assertThat(completion(c, "G-S11")).isNull();

        // D：无名册宠物不补行
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ?",
                Long.class, d)).isZero();

        // 幂等：每个 pet_milestone_id 至多一条完成行
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT pet_milestone_id FROM milestone_completions
                    GROUP BY pet_milestone_id HAVING count(*) > 1) dup
                """, Long.class)).isZero();

        // 不发通知
        assertThat(countNotifications(userIds)).isEqualTo(notificationsBefore);
    }

    // ===== 造数 =====

    private long newPet(User u, PetType type) {
        return petProfiles.save(PetProfile.create(u.getId(), type, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet())).getId();
    }

    /** 建档 + 物化完整名册，再删掉两条新项 —— 模拟迁移前的存量宠物。 */
    private long rosteredPetWithoutNewItems(User u, PetType type, String checkinCode, String tsCode) {
        long id = newPet(u, type);
        milestoneService.assignRoster(id, type);
        jdbc.update("DELETE FROM pet_milestones WHERE pet_profile_id = ? AND code IN (?, ?)", id, checkinCode, tsCode);
        return id;
    }

    private Place newPlace(long marker) {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                -6.2351, 106.8101, "Jl. Test", null, marker, "Jakarta"));
    }

    private void checkin(long userId, Place place, long petId, String checkedAt) {
        Timestamp at = Timestamp.from(Instant.parse(checkedAt));
        Long checkinId = jdbc.queryForObject("""
                INSERT INTO place_checkins (place_id, user_id, checked_at, public_token, origin_place_id, visit_date)
                VALUES (?, ?, ?, ?, ?, (?::timestamptz AT TIME ZONE 'Asia/Jakarta')::date)
                RETURNING id
                """, Long.class, place.getId(), userId, at, token(), place.getId(), at);
        jdbc.update("""
                INSERT INTO place_checkin_pets (checkin_id, pet_profile_id, origin_place_id, visit_date)
                SELECT id, ?, origin_place_id, visit_date FROM place_checkins WHERE id = ?
                """, petId, checkinId);
    }

    private void tailsonalityResult(long userId, long petId, String questionSet, String unlockedAt,
            String matchUnlockedAt) {
        jdbc.update("""
                INSERT INTO tailsonality_results (public_token, pet_profile_id, user_id, question_set, answers,
                    type_code, energy, content_version, unlocked_at, match_unlocked_at)
                VALUES (?, ?, ?, ?, '{}'::jsonb, 'ENTJ', 'H', 1, ?, ?)
                """, token(), petId, userId, questionSet, ts(unlockedAt), ts(matchUnlockedAt));
    }

    private static Timestamp ts(String iso) {
        return iso == null ? null : Timestamp.from(Instant.parse(iso));
    }

    private static String token() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    // ===== 执行与断言 =====

    private void runScript() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(SCRIPT));
        }
    }

    private long countNotifications(List<Long> userIds) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id IN (?, ?, ?, ?)", Long.class,
                userIds.toArray());
    }

    private void assertRosterRow(long petId, String code, int sortOrder) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT level, trigger_type, sort_order FROM pet_milestones WHERE pet_profile_id = ? AND code = ?",
                petId, code);
        assertThat(rows).as("%s roster row", code).hasSize(1);
        assertThat(rows.get(0).get("level")).isEqualTo("S");
        assertThat(rows.get(0).get("trigger_type")).isEqualTo("SYSTEM_AUTO");
        assertThat(rows.get(0).get("sort_order")).isEqualTo(sortOrder);
    }

    private Map<String, Object> completion(long petId, String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT mc.source, mc.linked_content_id, mc.completed_at, mc.celebrated_at
                FROM milestone_completions mc
                JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
                WHERE pm.pet_profile_id = ? AND pm.code = ?
                """, petId, code);
        assertThat(rows).as("%s completion rows", code).hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
