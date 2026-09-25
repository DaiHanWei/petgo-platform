package com.tailtopia.admin.account.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/** L0：admin 链未认证入口 —— htmx 走 HX-Redirect；fetch（Accept 任意）/ 整页导航一律 302（复审 0914 P0 回归）。 */
class AdminLoginEntryPointTest {

    private final AdminLoginEntryPoint entryPoint = new AdminLoginEntryPoint();

    private MockHttpServletResponse commence(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        entryPoint.commence(req, resp, new InsufficientAuthenticationException("expired"));
        return resp;
    }

    @Test
    void htmxRequestGetsHxRedirect() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/refunds/tok/drawer");
        req.addHeader("HX-Request", "true");
        MockHttpServletResponse resp = commence(req);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getHeader("HX-Redirect")).isEqualTo("/admin/login?expired");
        assertThat(resp.getRedirectedUrl()).isNull();
    }

    @Test
    void fetchUploadWithWildcardAcceptStillRedirects() throws Exception {
        // admin-core.js 的图片上传：无 HX-Request、Accept */*，前端靠 r.redirected 认会话过期
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/admin/seed/images");
        req.addHeader("Accept", "*/*");
        MockHttpServletResponse resp = commence(req);
        assertThat(resp.getStatus()).isEqualTo(302);
        assertThat(resp.getRedirectedUrl()).endsWith("/admin/login");
        assertThat(resp.getHeader("HX-Redirect")).isNull();
    }

    @Test
    void pageNavigationRedirects() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/dashboard");
        req.addHeader("Accept", "text/html");
        MockHttpServletResponse resp = commence(req);
        assertThat(resp.getStatus()).isEqualTo(302);
        assertThat(resp.getRedirectedUrl()).endsWith("/admin/login");
    }
}
