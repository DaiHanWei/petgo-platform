package com.petgo.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/**
 * L0 单元测试（无 DB/容器）：401/403 输出 RFC 9457 ProblemDetail 信封，二者不混用。
 */
class ProblemDetailAuthHandlersTest {

    private final ProblemDetailAuthHandlers handlers = new ProblemDetailAuthHandlers();

    @Test
    void unauthenticatedWrites401ProblemDetail() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/_guarded-ping");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handlers.commence(req, resp, new InsufficientAuthenticationException("no token"));

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentType()).contains("application/problem+json");
        assertThat(resp.getContentAsString()).contains("\"status\":401");
        assertThat(resp.getContentAsString()).contains("errors/unauthorized");
        assertThat(resp.getContentAsString()).contains("/api/v1/_guarded-ping");
    }

    @Test
    void forbiddenWrites403ProblemDetailDistinctFrom401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/x");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handlers.handle(req, resp, new AccessDeniedException("denied"));

        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("\"status\":403");
        assertThat(resp.getContentAsString()).contains("errors/forbidden");
    }
}
