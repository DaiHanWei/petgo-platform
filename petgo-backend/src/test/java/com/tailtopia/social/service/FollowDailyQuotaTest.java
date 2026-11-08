package com.tailtopia.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.social.repository.PetFollowRepository;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** L0：关注 + 追更两表合计的每日额度（V1.4.2 social-topic · Story 1.1 · AC5 · D-8 / P-6）。 */
class FollowDailyQuotaTest {

    private static final long ME = 1L;

    private final UserFollowRepository follows = mock(UserFollowRepository.class);
    private final PetFollowRepository petFollows = mock(PetFollowRepository.class);

    private FollowDailyQuota quota(String now) {
        return new FollowDailyQuota(follows, petFollows, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    @Test
    void combined499Passes() {
        when(follows.countCreatedSince(eq(ME), any())).thenReturn(250L);
        when(petFollows.countCreatedSince(eq(ME), any())).thenReturn(249L);
        assertThatCode(() -> quota("2026-11-04T05:00:00Z").check(ME)).doesNotThrowAnyException();
    }

    @Test
    void combined500Is429() {
        when(follows.countCreatedSince(eq(ME), any())).thenReturn(250L);
        when(petFollows.countCreatedSince(eq(ME), any())).thenReturn(250L);
        assertThatThrownBy(() -> quota("2026-11-04T05:00:00Z").check(ME))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.RATE_LIMITED);
                });
    }

    /** 只有追更占满也算（反之亦然）。 */
    @Test
    void eitherTableAloneCanFillTheQuota() {
        when(follows.countCreatedSince(eq(ME), any())).thenReturn(0L);
        when(petFollows.countCreatedSince(eq(ME), any())).thenReturn(500L);
        assertThatThrownBy(() -> quota("2026-11-04T05:00:00Z").check(ME)).isInstanceOf(AppException.class);

        when(follows.countCreatedSince(eq(ME), any())).thenReturn(500L);
        when(petFollows.countCreatedSince(eq(ME), any())).thenReturn(0L);
        assertThatThrownBy(() -> quota("2026-11-04T05:00:00Z").check(ME)).isInstanceOf(AppException.class);
    }

    /** WIB = UTC+7：UTC 16:59 仍是 WIB 当天 23:59，两张表用同一个窗口起点（当天 WIB 00:00 = 前一天 UTC 17:00）。 */
    @Test
    void wibWindowBeforeMidnight() {
        ArgumentCaptor<Instant> a = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> b = ArgumentCaptor.forClass(Instant.class);
        when(follows.countCreatedSince(eq(ME), a.capture())).thenReturn(0L);
        when(petFollows.countCreatedSince(eq(ME), b.capture())).thenReturn(0L);

        quota("2026-11-04T16:59:00Z").check(ME);

        assertThat(a.getValue()).isEqualTo(Instant.parse("2026-11-03T17:00:00Z"));
        assertThat(b.getValue()).isEqualTo(a.getValue());
    }

    /** UTC 17:00 = WIB 次日 00:00：窗口切到新的一天。 */
    @Test
    void wibWindowAtMidnight() {
        assertThat(quota("2026-11-04T17:00:00Z").dayStart()).isEqualTo(Instant.parse("2026-11-04T17:00:00Z"));
    }
}
