package com.tailtopia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** L0：v1.4.2 ocr 审查 M2 —— 限流用的客户端 IP 不能取客户端可伪造的 XFF 首段。 */
class ClientIpTest {

    @Test
    @DisplayName("优先 CF-Connecting-IP（Cloudflare 覆盖写入，不可伪造）")
    void prefersCloudflareHeader() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("CF-Connecting-IP", "203.0.113.9");
        r.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.9");
        assertThat(ClientIp.of(r)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("🔴 无 CF 头 → 取 XFF 末段（代理追加的真实地址），客户端自带的首段不采信")
    void usesLastForwardedHop() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7");
        assertThat(ClientIp.of(r)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("都没有 → remoteAddr")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.5");
        assertThat(ClientIp.of(r)).isEqualTo("10.0.0.5");
    }
}
