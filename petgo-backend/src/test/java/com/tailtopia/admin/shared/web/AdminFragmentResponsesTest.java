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

    /**
     * 🔴 两个事件名一次传入（V1.4.0 L2 回归）：曾与载荷版同名重载，{@code trigger(resp, "a", "b")} 命中 3 参数版本，
     * 输出 {@code {"a":b}} 非法 JSON → htmx 两个事件都不触发（新建礼物后抽屉不关、列表不刷新）。
     */
    @Test
    void twoEventNamesProduceValidJsonWithBothEvents() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AdminFragmentResponses.trigger(resp, AdminHxEvents.GIFT_CATALOG_LIST_REFRESH, AdminHxEvents.DRAWER_CLOSE);
        String header = resp.getHeader("HX-Trigger");
        assertThat(header).isEqualTo("{\"" + AdminHxEvents.GIFT_CATALOG_LIST_REFRESH + "\":{},\""
                + AdminHxEvents.DRAWER_CLOSE + "\":{}}");
        tools.jackson.databind.JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(header);
        assertThat(node.has(AdminHxEvents.GIFT_CATALOG_LIST_REFRESH)).isTrue();
        assertThat(node.has(AdminHxEvents.DRAWER_CLOSE)).isTrue();
        assertThat(node.size()).isEqualTo(2);
    }

    /**
     * 🔴 带载荷的事件（Story 7.4：{@code admin:drawer-open} 要把新标签的抽屉 URL 带给前端）
     * 再追加一个普通事件时，<b>载荷不许被抹掉</b>。
     *
     * <p>抹掉之后界面上的表现只是「新建完抽屉没打开」—— 很难往响应头上想。
     */
    @Test
    void payloadSurvivesLaterPlainTriggers() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AdminFragmentResponses.triggerWithPayload(resp, AdminHxEvents.DRAWER_OPEN,
                "{\"url\":\"/admin/content-tags/7/drawer?tab=assignments\",\"id\":7}");
        AdminFragmentResponses.trigger(resp, AdminHxEvents.TAG_LIST_REFRESH);
        assertThat(resp.getHeader("HX-Trigger")).isEqualTo(
                "{\"admin:drawer-open\":{\"url\":\"/admin/content-tags/7/drawer?tab=assignments\",\"id\":7},"
                        + "\"admin:tag-list-refresh\":{}}");
    }

    /** 载荷解析：嵌套对象里的 key、字符串里的花括号都不算事件名。 */
    @Test
    void parseEntriesKeepsPayloadsAndIgnoresNestedKeys() {
        assertThat(AdminFragmentResponses.parseEntries("{\"a\":{\"url\":\"/x?q={z}\"},\"b\":{}}"))
                .containsExactly(
                        org.assertj.core.api.Assertions.entry("a", "{\"url\":\"/x?q={z}\"}"),
                        org.assertj.core.api.Assertions.entry("b", "{}"));
    }
}
