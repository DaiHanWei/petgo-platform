package com.tailtopia.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.dashboard.metrics.SyntheticAccountSql;
import org.junit.jupiter.api.Test;

/** L0：马甲 / 种子号判定单一出口（V1.3.0 Story 3.1 AC1 / AC2）。 */
class UserSyntheticAccountTest {

    @Test
    void javaJudgementByColumnsNotPrefix() {
        assertThat(User.newGoogleUser("g-1", "g@t.test", "G", null).isSyntheticAccount()).isFalse();
        assertThat(User.newAppleUser("a-1", "a@t.test").isSyntheticAccount()).isFalse();
        assertThat(User.newAdmin("ops@t.test", "运营", "{bcrypt}x").isSyntheticAccount()).isTrue();
        assertThat(User.newVirtual("virtual:x", "马甲", null, 1L).isSyntheticAccount()).isTrue();
        assertThat(User.newVirtual("seed-tailtopia-1", "种子", null, 1L).isSyntheticAccount()).isTrue();
    }

    @Test
    void prefixFallbackIsNullSafe() {
        assertThat(User.looksSyntheticBySub(null)).isFalse();
        assertThat(User.looksSyntheticBySub("108-google")).isFalse();
        assertThat(User.looksSyntheticBySub("admin:ops@t.test")).isTrue();
        assertThat(User.looksSyntheticBySub("virtual:abc")).isTrue();
        assertThat(User.looksSyntheticBySub("seed-tailtopia-9")).isTrue();
    }

    @Test
    void sqlFragmentsMatchAcText() {
        assertThat(SyntheticAccountSql.EXCLUDE_WHERE).isEqualTo("u.account_type = 'REAL' AND u.role <> 'ADMIN'");
        assertThat(SyntheticAccountSql.IS_SYNTHETIC).isEqualTo("(u.account_type = 'VIRTUAL' OR u.role = 'ADMIN')");
    }
}
