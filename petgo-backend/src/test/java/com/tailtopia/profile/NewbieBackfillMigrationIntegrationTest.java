package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：V1.5.0 onboarding-recall Story 2.4 —— 存量补算五类账号：全做过的老用户 / 只做过 3 项 / 已按宠物解锁的双宠 /
 * 无宠物只做过 ③④ / 已注销；断言标记、flag、完成行、流水（只第一类 +20）、无通知；再执行一次不变。
 */
class NewbieBackfillMigrationIntegrationTest extends ApiIntegrationTest {

    private static final String SCRIPT = "db/migration/V20261115_0320__newbie_tasks_launch_backfill.sql";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PetProfileRepository petProfiles;
    @Autowired private MilestoneService milestoneService;
    @Autowired private PlatformTransactionManager txManager;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private long rosteredPet(User u, PetType type) {
        long id = new TransactionTemplate(txManager).execute(st -> {
            long pid = petProfiles.save(PetProfile.create(u.getId(), type, "Momo", null, null, null, null,
                    "TOK-BF-" + SEQ.incrementAndGet())).getId();
            milestoneService.assignRoster(pid, type);
            return pid;
        });
        extraPets.add(id);
        return id;
    }

    private void registeredDaysAgo(User u, int days) {
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), u.getId());
    }

    private long post(User author, String type) {
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, content_version) VALUES (?, ?, 'PUBLISHED', 'PUBLIC', now(), now(), 1) RETURNING id",
                Long.class, author.getId(), type);
    }

    private long comment(long post, User author, Long parent) {
        return jdbc.queryForObject("INSERT INTO comments (post_id, parent_id, author_id, body, created_at, updated_at,"
                + " content_version) VALUES (?, ?, ?, 'x', now(), now(), 1) RETURNING id", Long.class,
                post, parent, author.getId());
    }

    private void like(long post, User u) {
        jdbc.update("INSERT INTO content_likes (post_id, user_id, created_at) VALUES (?, ?, now())", post, u.getId());
    }

    private void completeCode(long petId, String code) {
        jdbc.update("""
                INSERT INTO milestone_completions (pet_milestone_id, source, completed_at)
                SELECT pm.id, 'SYSTEM_AUTO', now() FROM pet_milestones pm WHERE pm.pet_profile_id = ? AND pm.code = ?
                ON CONFLICT DO NOTHING""", petId, code);
    }

    private void runScript() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource(SCRIPT));
        }
    }

    private long marks(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM user_newbie_marks WHERE user_id = ? AND source = 'BACKFILL'",
                Long.class, u.getId());
    }

    private long lulusFlag(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM user_milestone_flags WHERE user_id = ? AND flag_key = 'LULUS_PEMULA'"
                + " AND celebrated_at IS NOT NULL", Long.class, u.getId());
    }

    private long backfillLedger(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE idem_key = ?", Long.class,
                "BACKFILL150:" + u.getId() + ":LULUS_PEMULA");
    }

    @Test
    void fiveAccountKindsAndReplayIsStable() throws Exception {
        User other = newUser();
        long otherPost = post(other, "DAILY");

        // ① 全做过的老用户（单宠）
        User full = newUser();
        registeredDaysAgo(full, 30);
        long fullPet = rosteredPet(full, PetType.CAT);
        long diary = post(full, "GROWTH_MOMENT");
        like(diary, other);
        like(otherPost, full);
        jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id, created_at) VALUES (?, ?, now())",
                full.getId(), rosteredPet(other, PetType.DOG));
        long c1 = comment(otherPost, full, null);
        long theirs = comment(diary, other, null);
        comment(diary, full, theirs);

        // ② 只做过 3 项（④ + ⑦ + ③）
        User partial = newUser();
        registeredDaysAgo(partial, 30);
        like(otherPost, partial);
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, source, created_at) VALUES (?, ?, 'USER', now())",
                partial.getId(), other.getId());

        // ③ 已按宠物解锁过的双宠
        User legacy = newUser();
        long legacyCat = rosteredPet(legacy, PetType.CAT);
        long legacyDog = rosteredPet(legacy, PetType.DOG);
        completeCode(legacyCat, "C-S16");

        // ④ 无宠物只做过 ③④
        User noPet = newUser();
        like(otherPost, noPet);
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, source, created_at) VALUES (?, ?, 'USER', now())",
                noPet.getId(), other.getId());

        // ⑤ 已注销
        User gone = newUser();
        like(otherPost, gone);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());

        long notificationsBefore = jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class);

        runScript();

        assertThat(marks(full)).isEqualTo(7);
        assertThat(lulusFlag(full)).isEqualTo(1);
        assertThat(backfillLedger(full)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM milestone_completions c JOIN pet_milestones m ON m.id = c.pet_milestone_id
                 WHERE m.pet_profile_id = ? AND m.code = 'C-S16' AND c.celebrated_at IS NOT NULL""", Long.class, fullPet))
                .isEqualTo(1);

        assertThat(marks(partial)).isEqualTo(3);
        assertThat(lulusFlag(partial)).isZero();

        assertThat(lulusFlag(legacy)).isEqualTo(1);
        assertThat(backfillLedger(legacy)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM milestone_completions c JOIN pet_milestones m ON m.id = c.pet_milestone_id
                 WHERE m.pet_profile_id = ? AND m.code = 'D-S16'""", Long.class, legacyDog)).isEqualTo(1);

        assertThat(marks(noPet)).isEqualTo(2);
        assertThat(marks(gone)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(notificationsBefore);

        long lifetime = jdbc.queryForObject("SELECT lifetime_total FROM growth_accounts WHERE user_id = ?", Long.class,
                full.getId());
        runScript();
        assertThat(marks(full)).isEqualTo(7);
        assertThat(backfillLedger(full)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lifetime_total FROM growth_accounts WHERE user_id = ?", Long.class,
                full.getId())).isEqualTo(lifetime);
        assertThat(c1).isPositive();
    }
}
