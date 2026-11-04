package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.social.service.FollowService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import javax.sql.DataSource;
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
 * V1.4.2 social-topic Story 2.2 · L1（需 Docker postgres + redis）：迁移 ⑥ {@code V20261104_2040} 账号级里程碑存量补算。
 *
 * <p>Flyway 启动时已执行一次（造数在其后），测试里用 {@link ScriptUtils} 在自动提交下<b>再执行两次</b>，
 * 断言语义（AC1 ~ AC3）+ 幂等（AC4.1）+ 9 类造数（AC4.2）+ 与 2.1 assignRoster 钩子衔接（T2.2）+ 账户列（T2.3）。
 * 照 {@code GrowthLaunchBackfillIntegrationTest} / {@code MilestoneCheckinTailsonalityBackfillIntegrationTest}。
 */
class AccountMilestoneBackfillMigrationIntegrationTest extends ApiIntegrationTest {

    private static final String SCRIPT = "db/migration/V20261104_2040__milestone_account_flags_backfill.sql";
    private static final String FOLLOW = "FIRST_FOLLOW_USER";
    private static final String COLLECT = "FIRST_COLLECT_CONTENT";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private MilestoneService milestoneService;
    @Autowired
    private PlaceRepository places;
    @Autowired
    private FollowService followService;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @Test
    void backfillsFlagsCompletionsAndGrowthIdempotentlyWithoutNotifications() throws Exception {
        User author = newUser();
        long post1 = publicPost(author.getId());
        long post2 = publicPost(author.getId());

        // ① 有宠物 + 关注过（两条关注，occurred_at 取最早；宠物建档早于关注 → completed_at = 关注时刻）
        User u1 = newUser();
        long u1Cat = rosteredPet(u1, PetType.CAT);
        setPetCreatedAt(u1Cat, "2025-06-01T00:00:00Z");
        follow(u1, newUser(), "2026-03-01T00:00:00Z");
        follow(u1, newUser(), "2026-01-01T00:00:00Z");

        // ② 有宠物 + 只收藏过（宠物建档晚于收藏 → completed_at = 建档时刻）
        User u2 = newUser();
        long u2Dog = rosteredPet(u2, PetType.DOG);
        setPetCreatedAt(u2Dog, "2026-05-01T00:00:00Z");
        collect(u2, post1, "2026-02-01T00:00:00Z");

        // ③ 两者都有 + 已有账户行（1.4.0 汇总弹窗快照与待弹标记不得被覆盖）
        User u3 = newUser();
        long u3Other = rosteredPet(u3, PetType.OTHER);
        follow(u3, newUser(), "2026-01-10T00:00:00Z");
        collect(u3, post1, "2026-01-11T00:00:00Z");
        collect(u3, post2, "2026-01-12T00:00:00Z");
        jdbc.update("""
                INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, backfill_popup_pending,
                        backfill_active_days, backfill_milestone_count, backfill_milestone_exp)
                VALUES (?, 10, 0, 1, true, 3, 0, 0)
                """, u3.getId());

        // ④ 无宠物 + 关注过
        User u4 = newUser();
        follow(u4, newUser(), "2026-01-01T00:00:00Z");
        // ④' 无宠物 + 关注且收藏过（T2.2 联动：补算后取关 → 新建宠物 → 两条已完成且已庆祝）
        User u4b = newUser();
        User u4bFollowee = newUser();
        follow(u4b, u4bFollowee, "2026-01-01T00:00:00Z");
        collect(u4b, post2, "2026-01-02T00:00:00Z");

        // ⑤ 只有 SYSTEM 关注（官方号自动关注）→ 无 flag
        User u5 = newUser();
        long u5Cat = rosteredPet(u5, PetType.CAT);
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, source) VALUES (?, ?, 'SYSTEM')",
                u5.getId(), newUser().getId());

        // ⑥ 只收藏过场所 → 无 flag
        User u6 = newUser();
        long u6Cat = rosteredPet(u6, PetType.CAT);
        jdbc.update("INSERT INTO place_collections (user_id, place_id) VALUES (?, ?)", u6.getId(),
                newPlace(u6.getId()).getId());

        // ⑦ 混养 + 1 只 READ_ONLY
        User u7 = newUser();
        long u7Cat = rosteredPet(u7, PetType.CAT);
        long u7Dog = rosteredPet(u7, PetType.DOG);
        long u7Ro = rosteredPet(u7, PetType.OTHER);
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", u7Ro);
        follow(u7, newUser(), "2026-01-01T00:00:00Z");

        // ⑧ VIRTUAL / 已注销 / DEACTIVATED
        User v = users.saveAndFlush(User.newVirtual("virtual:it-bf142-" + SEQ.incrementAndGet(), "v", null,
                adminActorId()));
        follow(v, newUser(), "2026-01-01T00:00:00Z");
        User gone = newUser();
        follow(gone, newUser(), "2026-01-01T00:00:00Z");
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());
        User deact = newUser();
        long deactCat = rosteredPet(deact, PetType.CAT);
        follow(deact, newUser(), "2026-01-01T00:00:00Z");
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", deact.getId());

        // ⑩ 注销已受理（account_deletions 一行 PENDING、一行 FAILED）→ 与运行时一致：无 flag / 完成行 / 流水
        User delPending = newUser();
        long delPendingCat = rosteredPet(delPending, PetType.CAT);
        follow(delPending, newUser(), "2026-01-01T00:00:00Z");
        collect(delPending, post1, "2026-01-02T00:00:00Z");
        jdbc.update("INSERT INTO account_deletions (user_id, status) VALUES (?, 'PENDING')", delPending.getId());
        User delFailed = newUser();
        long delFailedDog = rosteredPet(delFailed, PetType.DOG);
        follow(delFailed, newUser(), "2026-01-01T00:00:00Z");
        collect(delFailed, post2, "2026-01-02T00:00:00Z");
        jdbc.update("INSERT INTO account_deletions (user_id, status) VALUES (?, 'FAILED')", delFailed.getId());

        // ⑨ roster 缺失的老宠物（无任何名册行）
        User u9 = newUser();
        long u9Bare = petProfiles.save(PetProfile.create(u9.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet())).getId();
        follow(u9, newUser(), "2026-01-01T00:00:00Z");

        List<Long> all = List.of(u1.getId(), u2.getId(), u3.getId(), u4.getId(), u4b.getId(), u5.getId(),
                u6.getId(), u7.getId(), v.getId(), gone.getId(), deact.getId(), delPending.getId(),
                delFailed.getId(), u9.getId());
        long notificationsBefore = countNotifications(all);

        runScript();
        Map<String, Object> u3AccountAfterFirst = account(u3.getId());
        runScript(); // AC4.1 重放

        // ① flag + occurred_at = 最早关注；完成行 = 已庆祝、completed_at = GREATEST
        assertFlag(u1, FOLLOW, "2026-01-01T00:00:00Z");
        assertThat(flagCount(u1.getId(), COLLECT)).isZero();
        assertCompletedCelebrated(u1Cat, "C-S19", "2026-01-01T00:00:00Z");
        assertThat(completion(u1Cat, "C-S20")).isNull();
        assertLedger(u1, FOLLOW);
        assertThat(ledgerRows(u1.getId())).isEqualTo(1);
        assertAccount(u1.getId(), 5);

        // ②
        assertFlag(u2, COLLECT, "2026-02-01T00:00:00Z");
        assertThat(flagCount(u2.getId(), FOLLOW)).isZero();
        assertCompletedCelebrated(u2Dog, "D-S20", "2026-05-01T00:00:00Z");
        assertThat(completion(u2Dog, "D-S19")).isNull();
        assertThat(ledgerRows(u2.getId())).isEqualTo(1);
        assertAccount(u2.getId(), 5);

        // ③ 两条流水 +10；已有账户行累加，弹窗 / 快照 / celebrate_* 不变
        assertFlag(u3, FOLLOW, "2026-01-10T00:00:00Z");
        assertFlag(u3, COLLECT, "2026-01-11T00:00:00Z");
        assertThat(completion(u3Other, "G-S12")).isNotNull();
        assertThat(completion(u3Other, "G-S13")).isNotNull();
        assertLedger(u3, FOLLOW);
        assertLedger(u3, COLLECT);
        assertThat(ledgerRows(u3.getId())).isEqualTo(2);
        assertAccount(u3.getId(), 20);
        Map<String, Object> u3Account = account(u3.getId());
        assertThat(u3Account.get("backfill_popup_pending")).isEqualTo(true);
        assertThat(u3Account.get("backfill_active_days")).isEqualTo(3);
        assertThat(u3Account.get("backfill_milestone_count")).isEqualTo(0);
        assertThat(u3Account.get("backfill_milestone_exp")).isEqualTo(0);
        assertThat(u3Account.get("version")).as("重放不再 +1").isEqualTo(u3AccountAfterFirst.get("version"));

        // ④ 无宠物：flag + 流水照发
        assertFlag(u4, FOLLOW, "2026-01-01T00:00:00Z");
        assertThat(ledgerRows(u4.getId())).isEqualTo(1);
        assertAccount(u4.getId(), 5);
        assertThat(account(u4.getId()).get("backfill_popup_pending")).isEqualTo(false);

        // ⑤ ⑥ 无 flag、无流水、无完成行
        assertThat(flagCount(u5.getId(), FOLLOW) + flagCount(u5.getId(), COLLECT)).isZero();
        assertThat(completion(u5Cat, "C-S19")).isNull();
        assertThat(ledgerRows(u5.getId())).isZero();
        assertThat(flagCount(u6.getId(), FOLLOW) + flagCount(u6.getId(), COLLECT)).isZero();
        assertThat(completion(u6Cat, "C-S20")).isNull();
        assertThat(ledgerRows(u6.getId())).isZero();

        // ⑦ 混养：ACTIVE 各按物种编码；READ_ONLY 不写；流水按账号 1 条
        assertThat(completion(u7Cat, "C-S19")).isNotNull();
        assertThat(completion(u7Dog, "D-S19")).isNotNull();
        assertThat(completion(u7Ro, "G-S12")).as("READ_ONLY 不写").isNull();
        assertThat(ledgerRows(u7.getId())).isEqualTo(1);
        assertAccount(u7.getId(), 5);

        // ⑧ VIRTUAL / DEACTIVATED 有 flag 无流水；已注销无 flag
        assertThat(flagCount(v.getId(), FOLLOW)).isEqualTo(1);
        assertThat(ledgerRows(v.getId())).isZero();
        assertThat(flagCount(gone.getId(), FOLLOW)).isZero();
        assertThat(ledgerRows(gone.getId())).isZero();
        assertThat(flagCount(deact.getId(), FOLLOW)).isEqualTo(1);
        assertThat(completion(deactCat, "C-S19")).as("flag 是事实，ACTIVE 宠物照写完成行").isNotNull();
        assertThat(ledgerRows(deact.getId())).isZero();

        // ⑩ 注销已受理（PENDING / FAILED）：无 flag、无完成行、无流水
        assertThat(flagCount(delPending.getId(), FOLLOW) + flagCount(delPending.getId(), COLLECT)).isZero();
        assertThat(completion(delPendingCat, "C-S19")).isNull();
        assertThat(completion(delPendingCat, "C-S20")).isNull();
        assertThat(ledgerRows(delPending.getId())).isZero();
        assertThat(flagCount(delFailed.getId(), FOLLOW) + flagCount(delFailed.getId(), COLLECT)).isZero();
        assertThat(completion(delFailedDog, "D-S19")).isNull();
        assertThat(completion(delFailedDog, "D-S20")).isNull();
        assertThat(ledgerRows(delFailed.getId())).isZero();

        // ⑨ roster 缺失：flag 写、完成行不写、不报错
        assertThat(flagCount(u9.getId(), FOLLOW)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_milestones WHERE pet_profile_id = ?", Long.class,
                u9Bare)).isZero();

        // AC4.1：全局无重复完成行
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT pet_milestone_id FROM milestone_completions
                    GROUP BY pet_milestone_id HAVING count(*) > 1) dup
                """, Long.class)).isZero();

        // AC1.3：不发通知
        assertThat(countNotifications(all)).isEqualTo(notificationsBefore);

        // T2.2 联动：④' 取关 → 新建宠物 → 两条已完成且已庆祝、无通知、流水不变
        jdbc.update("DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?", u4b.getId(),
                u4bFollowee.getId());
        long u4bDog = rosteredPet(u4b, PetType.DOG);
        awaitAsyncIdle();
        assertThat(completion(u4bDog, "D-S19")).isNotNull();
        assertThat(completion(u4bDog, "D-S19").get("celebrated_at")).isNotNull();
        assertThat(completion(u4bDog, "D-S20")).isNotNull();
        assertThat(completion(u4bDog, "D-S20").get("celebrated_at")).isNotNull();
        assertThat(ledgerRows(u4b.getId())).isEqualTo(2);
        assertAccount(u4b.getId(), 10);

        // 补算过的老用户之后真实关注：flag 冲突 no-op → 不再通知、不再给分
        followService.follow(u1.getId(), newUser().getPublicToken());
        awaitAsyncIdle();
        assertThat(countNotifications(all)).isEqualTo(notificationsBefore);
        assertThat(ledgerRows(u1.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE idem_key = ?", Long.class,
                GrowthIdemKeys.milestoneAccount(u1.getId(), FOLLOW))).isZero();
    }

    /** 运行时已入账（MILESTONE_ACCT 键存在）的账号不再补发（零成本防双发）。 */
    @Test
    void skipsAccountsAlreadyCreditedByRuntimeKey() throws Exception {
        User u = newUser();
        follow(u, newUser(), "2026-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO growth_ledger (user_id, rule_code, delta, ref_type, ref_id, idem_key, wib_date, created_at)
                VALUES (?, 'MILESTONE_S', 5, 'MILESTONE', 'FIRST_FOLLOW_USER', ?, CURRENT_DATE, now())
                """, u.getId(), GrowthIdemKeys.milestoneAccount(u.getId(), FOLLOW));

        runScript();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE idem_key = ?", Long.class,
                GrowthIdemKeys.backfillAccountMilestone(u.getId(), FOLLOW))).isZero();
        assertThat(ledgerRows(u.getId())).isEqualTo(1);
    }

    // ===== 造数 =====

    private long rosteredPet(User u, PetType type) {
        return new TransactionTemplate(txManager).execute(st -> {
            long pid = petProfiles.save(PetProfile.create(u.getId(), type, "Momo", null, null, null, null,
                    "TOK-" + SEQ.incrementAndGet())).getId();
            milestoneService.assignRoster(pid, type);
            return pid;
        });
    }

    private void setPetCreatedAt(long petId, String at) {
        jdbc.update("UPDATE pet_profiles SET created_at = ? WHERE id = ?", Timestamp.from(Instant.parse(at)), petId);
    }

    /** 直写表（不经 FollowService）：模拟上线前的存量关注，不触发运行时事件。 */
    private void follow(User follower, User followee, String at) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, created_at, source) VALUES (?, ?, ?, 'USER')",
                follower.getId(), followee.getId(), Timestamp.from(Instant.parse(at)));
    }

    private void collect(User u, long postId, String at) {
        jdbc.update("INSERT INTO content_collections (user_id, post_id, created_at) VALUES (?, ?, ?)",
                u.getId(), postId, Timestamp.from(Instant.parse(at)));
    }

    private long publicPost(long authorId) {
        Timestamp at = Timestamp.from(Instant.now());
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, content_version) VALUES (?, 'DAILY', 'PUBLISHED', 'PUBLIC', ?, ?, 1) RETURNING id",
                Long.class, authorId, at, at);
    }

    private Place newPlace(long marker) {
        return places.save(Place.mark(java.util.UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + SEQ.incrementAndGet(), PlaceType.CAFE, List.of(PlaceTag.PETS_ALLOWED_INSIDE),
                -6.2351, 106.8101, "Jl. Test", null, marker, "Jakarta"));
    }

    private void runScript() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(SCRIPT));
        }
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

    // ===== 断言 =====

    private long flagCount(long userId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM user_milestone_flags WHERE user_id = ? AND flag_key = ?",
                Long.class, userId, key);
    }

    private void assertFlag(User u, String key, String occurredAt) {
        List<Timestamp> rows = jdbc.queryForList(
                "SELECT occurred_at FROM user_milestone_flags WHERE user_id = ? AND flag_key = ?", Timestamp.class,
                u.getId(), key);
        assertThat(rows).as("%s flag", key).hasSize(1);
        assertThat(rows.get(0).toInstant()).isEqualTo(Instant.parse(occurredAt));
    }

    private Map<String, Object> completion(long petId, String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT mc.completed_at, mc.celebrated_at, mc.source FROM milestone_completions mc
                JOIN pet_milestones pm ON pm.id = mc.pet_milestone_id
                WHERE pm.pet_profile_id = ? AND pm.code = ?
                """, petId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertCompletedCelebrated(long petId, String code, String completedAt) {
        Map<String, Object> c = completion(petId, code);
        assertThat(c).as("%s 应已完成", code).isNotNull();
        assertThat(c.get("source")).isEqualTo("SYSTEM_AUTO");
        assertThat(((Timestamp) c.get("completed_at")).toInstant()).isEqualTo(Instant.parse(completedAt));
        assertThat(c.get("celebrated_at")).as("已庆祝 = completed_at").isEqualTo(c.get("completed_at"));
    }

    private long ledgerRows(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Long.class, userId);
    }

    private void assertLedger(User u, String flagKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rule_code, delta, ref_type, ref_id FROM growth_ledger WHERE idem_key = ?",
                GrowthIdemKeys.backfillAccountMilestone(u.getId(), flagKey));
        assertThat(rows).as("%s 流水", flagKey).hasSize(1);
        assertThat(rows.get(0).get("rule_code")).isEqualTo("LAUNCH_BACKFILL");
        assertThat(rows.get(0).get("delta")).isEqualTo(5);
        assertThat(rows.get(0).get("ref_type")).isEqualTo("MILESTONE");
        assertThat(rows.get(0).get("ref_id")).isEqualTo(flagKey);
    }

    private Map<String, Object> account(long userId) {
        return jdbc.queryForMap("""
                SELECT lifetime_total, level, version, backfill_popup_pending, backfill_active_days,
                       backfill_milestone_count, backfill_milestone_exp, celebrate_from_level, celebrate_to_level
                FROM growth_accounts WHERE user_id = ?
                """, userId);
    }

    /** T2.3：总额恰为期望值、level 与 growth_levels 阈值一致、celebrate_* 仍 NULL。 */
    private void assertAccount(long userId, long expectedLifetime) {
        Map<String, Object> a = account(userId);
        assertThat(((Number) a.get("lifetime_total")).longValue()).isEqualTo(expectedLifetime);
        Integer expectedLevel = jdbc.queryForObject(
                "SELECT COALESCE(MAX(level), 1) FROM growth_levels WHERE threshold <= ?", Integer.class,
                expectedLifetime);
        assertThat(((Number) a.get("level")).intValue()).isEqualTo(expectedLevel);
        assertThat(a.get("celebrate_from_level")).isNull();
        assertThat(a.get("celebrate_to_level")).isNull();
    }

    private long countNotifications(List<Long> userIds) {
        String in = String.join(",", userIds.stream().map(String::valueOf).toList());
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id IN (" + in + ")",
                Long.class);
    }
}
