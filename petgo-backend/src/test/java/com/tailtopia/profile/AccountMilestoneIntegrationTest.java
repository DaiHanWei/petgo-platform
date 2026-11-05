package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.AccountMilestoneService;
import com.tailtopia.profile.service.MilestoneCelebrationService;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.social.service.FollowService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.4.2 social-topic Story 2.1 · L1（需 postgres + redis）：账号级里程碑「第一次关注一个人」「第一次收藏内容」全流程。
 *
 * <p>覆盖 AC1（迁移重放幂等）、AC2（多宠 + 只读、1 条通知、1 条流水）、AC3（官方号自动关注 / 自收藏）、AC4（只一次）、
 * AC5（无宠物后建宠 / 只读恢复 → 已完成且已庆祝）、AC6（庆祝回报按账号）、AC7（混养 targetRef）、AC8（并发）、AC10（注销删 flag）。
 * 成长值流水断言「≤ 1 条且键正确」：测试账号是否参与成长值取决于库里的参与配置，不在本 story 范围。
 */
class AccountMilestoneIntegrationTest extends ApiIntegrationTest {

    private static final String SCRIPT = "db/migration/V20261104_2030__milestone_account_flags_and_roster.sql";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private MilestoneService milestoneService;
    @Autowired
    private MilestoneCelebrationService celebrations;
    @Autowired
    private AccountMilestoneService accountMilestones;
    @Autowired
    private FollowService followService;
    @Autowired
    private com.tailtopia.account.repository.AccountDeletionRepository deletionRepo;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    // ===== AC2 / AC4 / AC7：多宠 + 只读 + 混养 + 只触发一次 =====

    @Test
    void firstFollowCompletesActivePetsOnceWithOneNotification() throws Exception {
        User u = newUser();
        long cat = rosteredPet(u, PetType.CAT);
        long dog = rosteredPet(u, PetType.DOG);
        long ro = rosteredPet(u, PetType.OTHER);
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", ro);
        User a = newUser();
        User b = newUser();

        followService.follow(u.getId(), a.getPublicToken());
        awaitAsyncIdle();

        assertThat(flagCount(u.getId(), "FIRST_FOLLOW_USER")).isEqualTo(1);
        assertCompleted(cat, "C-S19", true);
        assertCompleted(dog, "D-S19", true);
        assertThat(completion(ro, "G-S12")).as("READ_ONLY 不写").isNull();
        assertThat(milestoneNotifications(u.getId())).as("只 1 条，targetRef = 第一只 ACTIVE（猫）")
                .containsExactly("C-S19");
        assertThat(growthRows(u.getId(), "MILESTONE_ACCT:" + u.getId() + ":FIRST_FOLLOW_USER")).isLessThanOrEqualTo(1);
        assertThat(growthRows(u.getId(), "MILESTONE:" + u.getId() + ":C-S19:" + cat)).as("不走按宠物给分").isZero();

        // AC4：再关注别人 / 取关后再关注 → 不再写、不再通知
        followService.follow(u.getId(), b.getPublicToken());
        followService.unfollow(u.getId(), a.getPublicToken());
        followService.follow(u.getId(), a.getPublicToken());
        awaitAsyncIdle();
        assertThat(milestoneNotifications(u.getId())).hasSize(1);
        assertThat(flagCount(u.getId(), "FIRST_FOLLOW_USER")).isEqualTo(1);
        assertCompleted(cat, "C-S19", true); // 取关不删完成行
    }

    // ===== AC8：并发两次关注不同的人 =====

    @Test
    void concurrentFollowsTriggerOnce() throws Exception {
        User u = newUser();
        long cat = rosteredPet(u, PetType.CAT);
        User a = newUser();
        User b = newUser();

        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> followService.follow(u.getId(), a.getPublicToken()));
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> followService.follow(u.getId(), b.getPublicToken()));
        CompletableFuture.allOf(f1, f2).join();
        awaitAsyncIdle();

        assertThat(flagCount(u.getId(), "FIRST_FOLLOW_USER")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM milestone_completions mc JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
                WHERE pm.pet_profile_id = ? AND pm.code = 'C-S19'
                """, Long.class, cat)).isEqualTo(1);
        assertThat(milestoneNotifications(u.getId())).hasSize(1);
        assertThat(growthRows(u.getId(), "MILESTONE_ACCT:" + u.getId() + ":FIRST_FOLLOW_USER")).isLessThanOrEqualTo(1);
    }

    // ===== AC3：官方号自动关注不触发；自收藏触发 =====

    @Test
    void systemFollowDoesNotTriggerButSelfCollectDoes() throws Exception {
        User u = newUser();
        long other = rosteredPet(u, PetType.OTHER);
        User official = newUser();
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, source) VALUES (?, ?, 'SYSTEM')",
                u.getId(), official.getId());

        // 自收藏：collectorId == authorId，发事件的写法与 CollectService.collect 一致（事务内发布、AFTER_COMMIT 消费）。
        new TransactionTemplate(txManager).executeWithoutResult(
                st -> events.publishEvent(new ContentCollectedEvent(999_999L, u.getId(), u.getId())));
        awaitAsyncIdle();

        assertThat(flagCount(u.getId(), "FIRST_FOLLOW_USER")).isZero();
        assertThat(flagCount(u.getId(), "FIRST_COLLECT_CONTENT")).isEqualTo(1);
        assertCompleted(other, "G-S13", true);
        assertThat(completion(other, "G-S12")).isNull();
        assertThat(milestoneNotifications(u.getId())).containsExactly("G-S13");
    }

    // ===== AC5：无宠物时触发 → 之后建宠 / 只读恢复 =====

    @Test
    void noPetAtTriggerThenNewPetGetsCelebratedCompletionWithoutNotification() throws Exception {
        User u = newUser();
        User a = newUser();

        followService.follow(u.getId(), a.getPublicToken());
        awaitAsyncIdle();
        assertThat(flagCount(u.getId(), "FIRST_FOLLOW_USER")).isEqualTo(1);
        assertThat(milestoneNotifications(u.getId())).isEmpty();
        long growthBefore = growthRows(u.getId(), "MILESTONE_ACCT:" + u.getId() + ":FIRST_FOLLOW_USER");
        assertThat(growthBefore).isLessThanOrEqualTo(1);

        long dog = rosteredPet(u, PetType.DOG); // assignRoster → applyExistingFlagsToPet
        awaitAsyncIdle();

        assertCompleted(dog, "D-S19", false);
        assertThat(completion(dog, "D-S20")).isNull();
        assertThat(milestoneNotifications(u.getId())).isEmpty();
        assertThat(growthRows(u.getId(), "MILESTONE_ACCT:" + u.getId() + ":FIRST_FOLLOW_USER"))
                .as("建宠不再给分").isEqualTo(growthBefore);
    }

    @Test
    void readOnlyPetRestoredToActiveGetsCelebratedCompletion() throws Exception {
        User u = newUser();
        long cat = rosteredPet(u, PetType.CAT);
        long ro = rosteredPet(u, PetType.CAT);
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", ro);
        User a = newUser();
        followService.follow(u.getId(), a.getPublicToken());
        awaitAsyncIdle();
        assertThat(completion(ro, "C-S19")).isNull();

        // 名额足够时 reconcile 把只读恢复为正常（PET_DELETED 等「只恢复」触发）。
        jdbc.update("UPDATE pet_profiles SET status = 'ACTIVE' WHERE id = ?", ro); // 简化：直接走钩子语义
        new TransactionTemplate(txManager).executeWithoutResult(st -> accountMilestones.applyExistingFlagsToPet(ro));

        assertCompleted(ro, "C-S19", false);
        assertThat(milestoneNotifications(u.getId())).hasSize(1);
        assertCompleted(cat, "C-S19", true);
    }

    // ===== AC6：庆祝回报按账号 =====

    @Test
    void celebratingAccountCodeOnOnePetMarksAllPets() throws Exception {
        User u = newUser();
        long cat = rosteredPet(u, PetType.CAT);
        long dog = rosteredPet(u, PetType.DOG);
        User a = newUser();
        followService.follow(u.getId(), a.getPublicToken());
        awaitAsyncIdle();
        assertCompleted(cat, "C-S19", true);
        assertCompleted(dog, "D-S19", true);

        celebrations.reportCelebrated(u.getId(), cat, List.of("C-S19"));

        assertCompleted(cat, "C-S19", false);
        assertCompleted(dog, "D-S19", false);
    }

    // ===== AC10：注销删 flag =====

    @Test
    void deleteByUserIdRemovesFlags() throws Exception {
        User u = newUser();
        User a = newUser();
        followService.follow(u.getId(), a.getPublicToken());
        awaitAsyncIdle();
        assertThat(flagCount(u.getId(), "FIRST_FOLLOW_USER")).isEqualTo(1);

        accountMilestones.deleteByUserId(u.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_milestone_flags WHERE user_id = ?", Long.class,
                u.getId())).isZero();
    }

    /** 复审 2-1 #2：注销已受理（PENDING）后到达的触发不写回 flag。 */
    @Test
    void triggerAfterDeletionRequestedDoesNotRecreateFlag() throws Exception {
        User u = newUser();
        rosteredPet(u, PetType.CAT);
        deletionRepo.save(com.tailtopia.account.domain.AccountDeletion.request(u.getId()));

        new TransactionTemplate(txManager).executeWithoutResult(
                st -> events.publishEvent(new ContentCollectedEvent(999_998L, u.getId(), 1L)));
        awaitAsyncIdle();

        assertThat(flagCount(u.getId(), "FIRST_COLLECT_CONTENT")).isZero();
        assertThat(milestoneNotifications(u.getId())).isEmpty();
    }

    // ===== AC1：迁移重放 =====

    @Test
    void migrationReplayIsIdempotentAndOnlyTouchesRosteredPets() throws Exception {
        User u = newUser();
        long cat = rosteredPet(u, PetType.CAT);
        long other = rosteredPet(u, PetType.OTHER);
        jdbc.update("DELETE FROM pet_milestones WHERE pet_profile_id IN (?, ?) AND code IN "
                + "('C-S19','C-S20','G-S12','G-S13')", cat, other);
        long bare = petProfiles.save(PetProfile.create(u.getId(), PetType.DOG, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet())).getId();

        runScript();
        runScript();

        assertRosterRow(cat, "C-S19", 34);
        assertRosterRow(cat, "C-S20", 35);
        assertRosterRow(other, "G-S12", 19);
        assertRosterRow(other, "G-S13", 20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ?",
                Long.class, bare)).as("无名册宠物不补").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ?",
                Long.class, cat)).isEqualTo(35);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ?",
                Long.class, other)).isEqualTo(20);
    }

    // ===== 造数 / 断言 =====

    private long rosteredPet(User u, PetType type) {
        long id = new TransactionTemplate(txManager).execute(st -> {
            long pid = petProfiles.save(PetProfile.create(u.getId(), type, "Momo", null, null, null, null,
                    "TOK-" + SEQ.incrementAndGet())).getId();
            milestoneService.assignRoster(pid, type);
            return pid;
        });
        return id;
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

    private long flagCount(long userId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM user_milestone_flags WHERE user_id = ? AND flag_key = ?",
                Long.class, userId, key);
    }

    private List<String> milestoneNotifications(long userId) {
        return jdbc.queryForList("SELECT target_ref FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'MILESTONE_SM_NODE' ORDER BY id", String.class, userId);
    }

    private long growthRows(long userId, String idemKey) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND idem_key = ?",
                Long.class, userId, idemKey);
    }

    private Map<String, Object> completion(long petId, String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT mc.completed_at, mc.celebrated_at, mc.source FROM milestone_completions mc
                JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
                WHERE pm.pet_profile_id = ? AND pm.code = ?
                """, petId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertCompleted(long petId, String code, boolean uncelebrated) {
        Map<String, Object> c = completion(petId, code);
        assertThat(c).as("%s/%s 应已完成", petId, code).isNotNull();
        assertThat(c.get("source")).isEqualTo("SYSTEM_AUTO");
        if (uncelebrated) {
            assertThat(c.get("celebrated_at")).as("%s 应进补庆祝", code).isNull();
        } else {
            assertThat(c.get("celebrated_at")).as("%s 应已庆祝", code).isNotNull();
        }
    }

    private void runScript() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(SCRIPT));
        }
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
}
