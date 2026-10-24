package com.tailtopia.merchant.po.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** L0：内部接口端口分区、来源与密钥守卫（契约 5.1）。 */
class InternalApiGuardFilterTest {

    private static final int INTERNAL_PORT = 8095;

    private static InternalApiGuardFilter filter(int port, String secret) {
        MerchantInternalProperties p = new MerchantInternalProperties();
        p.setPort(port);
        p.setSecret(secret);
        return new InternalApiGuardFilter(p);
    }

    private static MockHttpServletRequest req(int localPort, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setLocalPort(localPort);
        return r;
    }

    private static MockHttpServletResponse run(InternalApiGuardFilter f, MockHttpServletRequest r,
            MockFilterChain chain) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f.doFilter(r, resp, chain);
        return resp;
    }

    @Test
    @DisplayName("主端口上的 /internal/** → 404；内部端口上的非 /internal 路径 → 404")
    void portPartition() throws Exception {
        InternalApiGuardFilter f = filter(INTERNAL_PORT, "s3cret");
        MockFilterChain c1 = new MockFilterChain();
        MockHttpServletResponse r1 = run(f, req(8084, "/internal/merchant/purchase-orders"), c1);
        assertThat(r1.getStatus()).isEqualTo(404);
        assertThat(c1.getRequest()).isNull();

        MockFilterChain c2 = new MockFilterChain();
        MockHttpServletResponse r2 = run(f, req(INTERNAL_PORT, "/api/v1/me"), c2);
        assertThat(r2.getStatus()).isEqualTo(404);
        assertThat(c2.getRequest()).isNull();

        MockFilterChain c3 = new MockFilterChain();
        run(f, req(8084, "/api/v1/me"), c3);
        assertThat(c3.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("端口未配置（0）时内部接口整体关闭")
    void disabledByDefault() throws Exception {
        MockFilterChain c = new MockFilterChain();
        MockHttpServletRequest r = req(0, "/internal/merchant/purchase-orders");
        r.addHeader("X-Internal-Secret", "s3cret");
        assertThat(run(filter(0, "s3cret"), r, c).getStatus()).isEqualTo(404);
        assertThat(c.getRequest()).isNull();
    }

    @Test
    @DisplayName("带 CF-Connecting-IP → 403 forbidden；密钥不符 / 未配置 → 401 unauthorized；密钥对 → 放行")
    void sourceAndSecret() throws Exception {
        InternalApiGuardFilter f = filter(INTERNAL_PORT, "s3cret");

        MockHttpServletRequest viaCf = req(INTERNAL_PORT, "/internal/merchant/purchase-orders");
        viaCf.addHeader("CF-Connecting-IP", "1.2.3.4");
        viaCf.addHeader("X-Internal-Secret", "s3cret");
        MockHttpServletResponse r1 = run(f, viaCf, new MockFilterChain());
        assertThat(r1.getStatus()).isEqualTo(403);
        assertThat(r1.getContentType()).startsWith("application/problem+json");
        assertThat(r1.getContentAsString()).contains("urn:tailtopia:internal:merchant:forbidden");

        MockHttpServletRequest wrong = req(INTERNAL_PORT, "/internal/merchant/purchase-orders");
        wrong.addHeader("X-Internal-Secret", "nope");
        MockHttpServletResponse r2 = run(f, wrong, new MockFilterChain());
        assertThat(r2.getStatus()).isEqualTo(401);
        assertThat(r2.getContentAsString()).contains("urn:tailtopia:internal:merchant:unauthorized");

        MockHttpServletRequest noSecretConfigured = req(INTERNAL_PORT, "/internal/merchant/purchase-orders");
        noSecretConfigured.addHeader("X-Internal-Secret", "");
        assertThat(run(filter(INTERNAL_PORT, ""), noSecretConfigured, new MockFilterChain()).getStatus())
                .isEqualTo(401);

        MockHttpServletRequest ok = req(INTERNAL_PORT, "/internal/merchant/purchase-orders");
        ok.addHeader("X-Internal-Secret", "s3cret");
        MockFilterChain chain = new MockFilterChain();
        run(f, ok, chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
