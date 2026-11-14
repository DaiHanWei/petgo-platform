package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.service.NewbieMarkService;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：V1.5.0 onboarding-recall Story 2.2 —— 7 项做满 → 账号级 Lulus Pemula（多宠一起亮）、1 条通知、1 笔 +20；
 * 无 ACTIVE 宠物也通知；毕业后新建宠物直接「已完成且已庆祝」；迁移 ② ③ 重放。
 */
class NewbieGraduationIntegrationTest extends ApiIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PetProfileRepository petProfiles;
    @Autowired private MilestoneService milestoneService;
    @Autowired private NewbieMarkService marks;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("applicationTaskExecutor") private ThreadPoolTaskExecutor asyncExecutor;

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
                    "TOK-G-" + SEQ.incrementAndGet())).getId();
            milestoneService.assignRoster(pid, type);
            return pid;
        });
        extraPets.add(id);
        return id;
    }

    /** 注册 8 天前（⑦ 满足）并写满 ①–⑥。 */
    private void completeAll(User u) throws InterruptedException {
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(8, ChronoUnit.DAYS)), u.getId());
        for (NewbieTask t : List.of(NewbieTask.FIRST_DIARY, NewbieTask.FIRST_LIKE_RECEIVED, NewbieTask.FIRST_FOLLOW,
                NewbieTask.FIRST_LIKE_GIVEN, NewbieTask.FIRST_COMMENT, NewbieTask.FIRST_REPLY)) {
            marks.markIfAbsent(u.getId(), t);
        }
        awaitAsyncIdle();
    }

    private long completedCount(long petId, String code) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM milestone_completions c JOIN pet_milestones m ON m.id = c.pet_milestone_id
                 WHERE m.pet_profile_id = ? AND m.code = ?""", Long.class, petId, code);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void twoPetsGraduateOnceWithOneNotificationAndOneGrowthEntry() throws Exception {
        User u = newUser();
        long cat = rosteredPet(u, PetType.CAT);
        long dog = rosteredPet(u, PetType.DOG);

        completeAll(u);

        assertThat(count("SELECT count(*) FROM user_milestone_flags WHERE user_id = ? AND flag_key = 'LULUS_PEMULA'",
                u.getId())).isEqualTo(1);
        assertThat(completedCount(cat, "C-S16")).isEqualTo(1);
        assertThat(completedCount(dog, "D-S16")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'MILESTONE_SM_NODE'",
                u.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code = 'NEWBIE_GRADUATED'",
                u.getId())).isEqualTo(1);

        // 毕业后新建第三只 → 直接已完成且已庆祝，不加流水、不发通知。
        long other = rosteredPet(u, PetType.OTHER);
        awaitAsyncIdle();
        assertThat(count("""
                SELECT count(*) FROM milestone_completions c JOIN pet_milestones m ON m.id = c.pet_milestone_id
                 WHERE m.pet_profile_id = ? AND m.code = 'G-S9' AND c.celebrated_at IS NOT NULL""", other)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code = 'NEWBIE_GRADUATED'",
                u.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'MILESTONE_SM_NODE'",
                u.getId())).isEqualTo(1);
    }

    @Test
    void withoutActivePetsStillNotifiesAndAccrues() throws Exception {
        User u = newUser();
        completeAll(u);
        assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'MILESTONE_SM_NODE'",
                u.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code = 'NEWBIE_GRADUATED'",
                u.getId())).isEqualTo(1);
    }

    @Test
    void migrationsReplay() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V20261115_0310__lulus_pemula_account_flag.sql"));
            ScriptUtils.executeSqlScript(c,
                    new ClassPathResource("db/migration/V20261115_0315__insert_growth_rule_newbie_graduated.sql"));
        }
        assertThat(count("SELECT count(*) FROM growth_rules WHERE code = 'NEWBIE_GRADUATED' AND points = 20"))
                .isEqualTo(1);
    }

    private void awaitAsyncIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        Thread.sleep(50);
        while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("业务异步池 10s 内未排空");
            }
            Thread.sleep(20);
        }
    }
}
