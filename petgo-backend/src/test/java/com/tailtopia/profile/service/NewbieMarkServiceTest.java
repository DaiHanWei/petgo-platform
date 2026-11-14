package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** L0：V1.5.0 onboarding-recall Story 2.1 · AC11 / AC12 —— 只在真正新写入时埋点 + 调扩展点。 */
class NewbieMarkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");

    private UserNewbieMarkDao dao;
    private AccountQueryService accounts;
    private AnalyticsClient analytics;
    private NewbieMarkHooks hook;
    private NewbieMarkService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dao = mock(UserNewbieMarkDao.class);
        accounts = mock(AccountQueryService.class);
        analytics = mock(AnalyticsClient.class);
        hook = mock(NewbieMarkHooks.class);
        ObjectProvider<NewbieMarkHooks> hooks = mock(ObjectProvider.class);
        when(hooks.orderedStream()).thenAnswer(inv -> Stream.of(hook));
        service = new NewbieMarkService(dao, accounts, analytics, hooks, Clock.fixed(NOW, ZoneOffset.UTC));
        when(accounts.createdAtOf(7L)).thenReturn(Optional.of(Instant.parse("2026-11-12T20:00:00Z"))); // WIB 11-13
    }

    @Test
    void 新写入才埋点并调扩展点() {
        when(dao.insertIfAbsent(eq(7L), eq("FIRST_LIKE_GIVEN"), eq("RUNTIME"), any())).thenReturn(1);
        assertThat(service.markIfAbsent(7L, NewbieTask.FIRST_LIKE_GIVEN)).isTrue();
        verify(analytics).capture(AnalyticsDistinctId.of(7L), "newbie_task_completed",
                Map.of("task", 4, "days_since_signup", 2L));
        verify(hook).afterMarked(7L, NewbieTask.FIRST_LIKE_GIVEN);
    }

    @Test
    void 已有则不埋点不调扩展点() {
        when(dao.insertIfAbsent(anyLong(), anyString(), anyString(), any())).thenReturn(0);
        assertThat(service.markIfAbsent(7L, NewbieTask.FIRST_LIKE_GIVEN)).isFalse();
        verify(analytics, never()).capture(anyString(), anyString(), any());
        verify(hook, never()).afterMarked(anyLong(), any());
    }

    @Test
    void 写入方法是另开事务() throws Exception {
        var m = NewbieMarkService.class.getMethod("markIfAbsent", long.class, NewbieTask.class);
        var tx = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }
}
