package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import org.junit.jupiter.api.Test;

/** L1：Story 1.3 —— 入口、拒绝页、未启用、空态。 */
class AiQueryPageIntegrationTest extends AiQueryIntegrationSupport {

    private String nav(String html) {
        int from = html.indexOf("<nav");
        int to = html.indexOf("</nav>");
        return from >= 0 && to > from ? html.substring(from, to) : "";
    }

    @Test
    void querierSeesSingleLinkRightBelowOverview() throws Exception {
        String html = mvc.perform(get("/admin/dashboard").param("lang", "zh_CN").with(authentication(querier().auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String nav = nav(html);
        int overview = nav.indexOf("📊 概览");
        int aiq = nav.indexOf("🤖 AI 查数");
        assertThat(overview).isPositive();
        assertThat(aiq).as("AI 查数应紧跟概览之后").isGreaterThan(overview);
        assertThat(nav.substring(overview, aiq)).doesNotContain("<details");
        assertThat(nav).contains("href=\"/admin/ai-query\"");
    }

    @Test
    void superAdminSeesTheLinkToo() throws Exception {
        String html = mvc.perform(get("/admin/dashboard").param("lang", "zh_CN").with(authentication(superAdmin().auth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(nav(html)).contains("href=\"/admin/ai-query\"");
    }

    @Test
    void withoutCodeNoLinkAndDeniedPage() throws Exception {
        var other = staff(AdminPermissions.CONFIG_VIEW);
        String html = mvc.perform(get("/admin/dashboard").param("lang", "zh_CN").with(authentication(other.auth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(nav(html)).doesNotContain("/admin/ai-query");
        var res = mvc.perform(get("/admin/ai-query").param("lang", "zh_CN").with(authentication(other.auth())))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertThat(res.getForwardedUrl()).isEqualTo("/admin/denied");
    }

    @Test
    void modeOffShowsNotEnabledAndDisablesComposer() throws Exception {
        String html = visible(mvc.perform(get("/admin/ai-query").param("lang", "zh_CN").with(authentication(querier().auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(html).contains("AI 查数暂未启用").contains("请联系管理员。");
        assertThat(html).doesNotContain("data-aiq-sample=");
        assertThat(html).contains("aiq-box is-dis");
        assertThat(html).containsPattern("<textarea[^>]*disabled");
        assertThat(html).containsPattern("<button type=\"submit\"[^>]*disabled");
    }

    @Test
    void enabledWithNoHistoryShowsEmptyListAndFourSamples() throws Exception {
        enable();
        String html = visible(mvc.perform(get("/admin/ai-query").param("lang", "zh_CN").with(authentication(querier().auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(html).contains("还没有对话").contains("想查什么数据？");
        assertThat(html.split("data-aiq-sample=\"", -1)).hasSize(5);
        assertThat(html).contains("上个月新注册用户有多少？按周拆分").contains("最近 7 天接口 5xx 最多的是哪些？");
        assertThat(html).doesNotContain("aiq-box is-dis");
        assertThat(html).containsPattern("<option value=\"zh\"\\s+selected");
    }

    @Test
    void defaultAnswerLanguageFollowsAdminLocale() throws Exception {
        enable();
        String html = mvc.perform(get("/admin/ai-query").param("lang", "id").with(authentication(querier().auth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).containsPattern("<option value=\"id\"\\s+selected").contains("Data apa yang ingin dicari?");
    }
}
