package com.tailtopia.admin.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** L0：HX-* 头解析（Story 2.3a AC1）。 */
class HxRequestTest {

    @Test
    void parsesHtmxHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("HX-Request", "true");
        req.addHeader("HX-Target", "row-7");
        req.addHeader("HX-Trigger", "btn-deactivate");
        HxRequest hx = HxRequest.of(req);
        assertThat(hx.isHtmx()).isTrue();
        assertThat(hx.target()).isEqualTo("row-7");
        assertThat(hx.trigger()).isEqualTo("btn-deactivate");
        assertThat(hx.currentUrl()).isNull();
        assertThat(hx.view("admin/fragments/x :: y", "admin/x")).isEqualTo("admin/fragments/x :: y");
    }

    @Test
    void noHeaderMeansPlainRequest() {
        HxRequest hx = HxRequest.of(new MockHttpServletRequest());
        assertThat(hx.isHtmx()).isFalse();
        assertThat(hx.target()).isNull();
        assertThat(hx.view("f", "p")).isEqualTo("p");
        assertThat(HxRequest.of(null)).isEqualTo(HxRequest.NONE);
        assertThat(new HxRequestArgumentResolver().supportsParameter(
                org.springframework.core.MethodParameter.forExecutable(
                        HxRequestTest.class.getDeclaredMethods()[0], -1))).isFalse();
    }
}
