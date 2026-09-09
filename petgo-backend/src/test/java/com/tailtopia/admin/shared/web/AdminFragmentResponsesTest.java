package com.tailtopia.admin.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** L0：HX-Trigger 多事件合并（Story 2.3a AC5）。 */
class AdminFragmentResponsesTest {

    @Test
    void badgeRefreshWritesJsonObjectHeader() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AdminFragmentResponses.triggerBadgeRefresh(resp);
        assertThat(resp.getHeader("HX-Trigger")).isEqualTo("{\"admin:badge-refresh\":{}}");
    }

    @Test
    void repeatedTriggersAccumulateWithoutDuplicates() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AdminFragmentResponses.trigger(resp, AdminHxEvents.DRAWER_CLOSE);
        AdminFragmentResponses.triggerBadgeRefresh(resp);
        AdminFragmentResponses.triggerBadgeRefresh(resp);
        assertThat(resp.getHeader("HX-Trigger"))
                .isEqualTo("{\"admin:drawer-close\":{},\"admin:badge-refresh\":{}}");
        assertThat(AdminFragmentResponses.parse("a, b")).containsExactly("a", "b");
        assertThat(AdminFragmentResponses.parse("{\"a\":{\"x\":1},\"b\":{}}")).containsExactly("a", "b");
        assertThat(AdminFragmentResponses.parse(null)).isEmpty();
    }
}
