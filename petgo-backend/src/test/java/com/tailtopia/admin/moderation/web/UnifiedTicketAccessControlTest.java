package com.tailtopia.admin.moderation.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.moderation.service.UnifiedTicketQueryService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.moderation.repository.AccountDisposalRepository;
import com.tailtopia.moderation.repository.AccountReportEntryRepository;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;

/**
 * L0：统一工单队列的门控（Story 3.1 AC11）—— 列表与详情都需要 {@code content.view_tickets}，
 * {@code SUPER_ADMIN} 隐式全权，无权 → 403（{@link AccessDeniedException}）。
 *
 * <p>⚠️ 特意验了「有旧的 {@code content.view_reports} 但没有新码 → 仍然 403」：
 * 统一视图里多了**账号举报**与**账号标识字段审核**两类数据，权限粒度得跟着能看见的数据走，
 * 不能靠「反正他本来就能看举报队列」把两类新数据顺带放出去。
 */
class UnifiedTicketAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static UnifiedTicketController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        UnifiedTicketQueryService query() {
            var q = mock(UnifiedTicketQueryService.class);
            when(q.search(any(), any(), any(), any())).thenReturn(Page.empty());
            return q;
        }

        @Bean
        AccountReportEntryRepository entries() {
            return mock(AccountReportEntryRepository.class);
        }

        @Bean
        AccountDisposalRepository disposals() {
            return mock(AccountDisposalRepository.class);
        }

        @Bean
        AccountQueryService accounts() {
            return mock(AccountQueryService.class);
        }

        @Bean
        UnifiedTicketController controller(UnifiedTicketQueryService q,
                AccountReportEntryRepository e, AccountDisposalRepository d, AccountQueryService a) {
            return new UnifiedTicketController(q, e, d, a);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(UnifiedTicketController.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(AdminAccountType type, String... authorities) {
        AdminUserDetails principal = new AdminUserDetails(1L, null, "a@tailtopia.test", "{bcrypt}x",
                type, new java.util.HashSet<>(Arrays.asList(authorities)));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(principal, null,
                        new java.util.ArrayList<>(principal.getAuthorities())));
    }

    @Test
    void superAdminCanView() {
        authenticate(AdminAccountType.SUPER_ADMIN);
        assertThatCode(() -> controller.tickets(null, null, null, 0, null, new ConcurrentModel()))
                .doesNotThrowAnyException();
    }

    @Test
    void staffWithPermissionCanView() {
        authenticate(AdminAccountType.STAFF, "content.view_tickets");
        assertThatCode(() -> controller.tickets(null, null, null, 0, null, new ConcurrentModel()))
                .doesNotThrowAnyException();
    }

    @Test
    void staffWithoutPermissionIsDenied() {
        authenticate(AdminAccountType.STAFF, "content.view");
        assertThatThrownBy(() -> controller.tickets(null, null, null, 0, null, new ConcurrentModel()))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** ⚠️ 旧的举报队列权限**不自动放行**统一视图 —— 那里面还有他本来看不到的两类数据。 */
    @Test
    void legacyReportPermissionDoesNotGrantTheUnifiedView() {
        authenticate(AdminAccountType.STAFF, "content.view_reports");
        assertThatThrownBy(() -> controller.tickets(null, null, null, 0, null, new ConcurrentModel()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void detailIsGuardedToo() {
        authenticate(AdminAccountType.STAFF, "content.view");
        assertThatThrownBy(
                () -> controller.detail("ACCOUNT_REPORT", 1L, 2L, new ConcurrentModel()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
