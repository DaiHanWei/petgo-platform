package com.tailtopia.auth.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.event.UserDailyActiveEvent;
import com.tailtopia.auth.service.AccountQueryService;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** V1.4.0 ① Story 1.1 · AC5.1（L0）：当天首次活跃才发 {@link UserDailyActiveEvent}；异常吞掉；只认普通用户。 */
class UserActivityFilterTest {

    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UserActivityFilter filter = new UserActivityFilter(accounts, events);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String sub, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(sub, null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    private void run() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/feed");
        req.setRequestURI("/api/v1/feed");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
    }

    @Test
    void firstActivityOfTheDayPublishes() throws Exception {
        loginAs("7", "ROLE_USER");
        when(accounts.touchLastActive(eq(7L), any())).thenReturn(true);

        run();

        verify(events).publishEvent(any(UserDailyActiveEvent.class));
    }

    @Test
    void laterActivityDoesNotPublish() throws Exception {
        loginAs("7", "ROLE_USER");
        when(accounts.touchLastActive(eq(7L), any())).thenReturn(false);

        run();

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void failureIsSwallowedAndNothingPublished() throws Exception {
        loginAs("7", "ROLE_USER");
        when(accounts.touchLastActive(eq(7L), any())).thenThrow(new IllegalStateException("db down"));

        run(); // 不抛

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void nonUserRoleIsIgnored() throws Exception {
        loginAs("7", "ROLE_VET");

        run();

        verify(accounts, never()).touchLastActive(anyLong(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
