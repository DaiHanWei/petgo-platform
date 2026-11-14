package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.repository.FollowUpdatePushDao;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：两类合计每人每 WIB 日推送名额（V1.5.0 onboarding-recall Story 5.1 · AC7 · OR-14）。原子性在 L1 集成测试验。 */
class FollowUpdatePushQuotaTest {

    /** UTC 11-14 18:00 = WIB 11-15 01:00 —— 名额按 WIB 日计。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T18:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("today 是 WIB 日；tryClaim 用 WIB 日 + 配置的 cap 调 DAO 原子 UPSERT")
    void claimsWithWibDateAndCap() {
        FollowUpdatePushDao dao = mock(FollowUpdatePushDao.class);
        LocalDate wib = LocalDate.of(2026, 11, 15);
        when(dao.tryClaimQuota(7L, wib, 3)).thenReturn(true, true, true, false);
        FollowUpdatePushQuota q = new FollowUpdatePushQuota(dao, 3, CLOCK);

        assertThat(q.today()).isEqualTo(wib);
        assertThat(q.dailyCap()).isEqualTo(3);
        assertThat(q.tryClaim(7L)).isTrue();
        assertThat(q.tryClaim(7L)).isTrue();
        assertThat(q.tryClaim(7L, wib)).isTrue();
        assertThat(q.tryClaim(7L)).isFalse();
    }

    @Test
    @DisplayName("cap 可配置（env FOLLOW_UPDATE_PUSH_DAILY_CAP）原样透传")
    void customCap() {
        FollowUpdatePushDao dao = mock(FollowUpdatePushDao.class);
        LocalDate d = LocalDate.of(2026, 11, 20);
        new FollowUpdatePushQuota(dao, 5, CLOCK).tryClaim(9L, d);
        verify(dao).tryClaimQuota(9L, d, 5);
    }
}
