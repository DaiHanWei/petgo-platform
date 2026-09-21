package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.dashboard.metrics.SyntheticAccountSql;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：Java {@code User.isSyntheticAccount()} / SQL {@code SyntheticAccountSql.IS_SYNTHETIC} / 前缀兜底 {@code looksSyntheticBySub}
 * 对六类账号结论逐一相等（V1.3.0 Story 3.1 AC3）：Google 真实、Apple 真实（google_sub NULL）、admin: 运营号、virtual: 虚拟号、
 * seed-tailtopia- 种子号（account_type=VIRTUAL）、已注销真实用户 → 真实 ×2 + 已注销 = false，其余 = true。
 */
class SyntheticAccountParityTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void javaSqlAndPrefixAgreeOnSixAccountKinds() {
        long n = SEQ.incrementAndGet();
        Map<String, User> samples = new LinkedHashMap<>();
        samples.put("google-real", users.save(User.newGoogleUser("parity-g-" + n, "pg" + n + "@t.test", "G", null)));
        samples.put("apple-real", users.save(User.newAppleUser("parity-a-" + n, "pa" + n + "@t.test")));
        samples.put("admin-legacy", users.save(User.newAdmin("parity-admin-" + n + "@t.test", "运营", "{bcrypt}x")));
        samples.put("virtual", users.save(User.newVirtual("virtual:parity-" + n, "马甲" + n, null, 1L)));
        User seed = users.save(User.newVirtual("seed-tailtopia-parity-" + n, "种子" + n, null, 1L));
        samples.put("seed", seed);
        User deleted = users.save(User.newGoogleUser("parity-d-" + n, "pd" + n + "@t.test", "D", null));
        deleted.anonymizeForDeletion(Instant.now());
        samples.put("deleted-real", users.save(deleted));

        Map<String, Boolean> expected = Map.of("google-real", false, "apple-real", false, "admin-legacy", true,
                "virtual", true, "seed", true, "deleted-real", false);
        for (var e : samples.entrySet()) {
            User u = users.findById(e.getValue().getId()).orElseThrow();
            boolean java = u.isSyntheticAccount();
            Boolean sql = jdbc.queryForObject("select " + SyntheticAccountSql.IS_SYNTHETIC + " from users u where u.id = ?",
                    Boolean.class, u.getId());
            boolean prefix = User.looksSyntheticBySub(u.getGoogleSub());
            boolean want = expected.get(e.getKey());
            assertThat(java).as("Java " + e.getKey()).isEqualTo(want);
            assertThat(sql).as("SQL " + e.getKey()).isEqualTo(want);
            assertThat(prefix).as("prefix " + e.getKey()).isEqualTo(want);
            // 排除片段与判定片段互为否定
            Boolean excluded = jdbc.queryForObject("select (" + SyntheticAccountSql.EXCLUDE_WHERE + ") from users u where u.id = ?",
                    Boolean.class, u.getId());
            assertThat(excluded).as("EXCLUDE_WHERE " + e.getKey()).isEqualTo(!want);
        }
    }

    /**
     * 全表护栏（Dev Notes 承诺）：存量任一行「按列判定」与「按前缀判定」不一致 → 红。
     * 典型脏数据：忘了设 account_type 的 virtual:* 号、或前缀不规范的虚拟号；红了先清数据再改口径。
     */
    @Test
    void wholeTableColumnAndPrefixJudgementsAgree() {
        Integer mismatched = jdbc.queryForObject("select count(*) from users u where " + SyntheticAccountSql.IS_SYNTHETIC
                + " <> (coalesce(u.google_sub, '') like 'admin:%' or coalesce(u.google_sub, '') like 'virtual:%'"
                + " or coalesce(u.google_sub, '') like 'seed-tailtopia-%')", Integer.class);
        assertThat(mismatched).as("users 表中按列判定与按前缀判定不一致的行数").isZero();
    }
}
