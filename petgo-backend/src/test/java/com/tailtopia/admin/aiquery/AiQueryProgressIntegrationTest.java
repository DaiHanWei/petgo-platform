package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1：Story 1.6 —— 状态与计时、轮询片段、只关注本人、全部结束后输入框恢复、列表滚动加载。 */
class AiQueryProgressIntegrationTest extends AiQueryIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private String ask(Actor a, String q) throws Exception {
        String loc = mvc.perform(post("/admin/ai-query").param("question", q).param("answerLang", "zh")
                        .with(csrf()).with(authentication(a.auth())))
                .andReturn().getResponse().getRedirectedUrl();
        return loc.substring("/admin/ai-query/".length());
    }

    private String msgToken(String conv) {
        return jdbc.queryForObject("""
                SELECT m.token FROM ai_query_messages m JOIN ai_query_conversations c ON c.id = m.conversation_id
                 WHERE c.token = ? ORDER BY m.id DESC LIMIT 1""", String.class, conv);
    }

    private String page(Actor a, String path) throws Exception {
        return visible(mvc.perform(get(path).param("lang", "zh_CN").with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void pendingAndRunningShowStatusElapsedAndLeaveHintWithoutCancel() throws Exception {
        enable();
        Actor a = querier();
        String conv = ask(a, "排队的问题");
        String html = page(a, "/admin/ai-query/" + conv);
        assertThat(html).contains("排队中").contains("已等待").contains("可以离开页面").contains("data-aiq-elapsed=\"");
        assertThat(html).containsPattern("id=\"aiq-row-" + conv + "\"[^>]*data-aiq-unfinished=\"true\"");
        assertThat(html).contains("aria-live=\"polite\"");
        assertThat(html).doesNotContain("取消").doesNotContainIgnoringCase("cancel");

        jdbc.update("UPDATE ai_query_messages SET status = 'RUNNING', submitted_at = now() - interval '32 seconds' WHERE token = ?", msgToken(conv));
        html = page(a, "/admin/ai-query/" + conv);
        assertThat(html).contains("正在查询").contains("已用时").containsPattern("data-aiq-elapsed=\"3[2-9]\"");
        assertThat(html).contains("查询中");
        // 连接中断提示默认隐藏
        assertThat(html).containsPattern("id=\"aiq-conn\"\\s+hidden");
    }

    @Test
    void pollReturnsFreshRowsMessagesAndComposerOnlyForOwnData() throws Exception {
        enable();
        Actor a = querier();
        String conv = ask(a, "我的问题");
        String m = msgToken(conv);
        Actor other = querier();
        String othersConv = ask(other, "别人的问题");
        String othersMsg = msgToken(othersConv);

        String body = mvc.perform(get("/admin/ai-query/poll").param("w", conv, othersConv, "../bad").param("m", m, othersMsg)
                        .param("c", conv).with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("<html").contains("data-aiq-poll").contains("aiq-row-" + conv).contains("aiq-msg-" + m);
        assertThat(body).doesNotContain(othersConv).doesNotContain(othersMsg).doesNotContain("别人的问题");
        assertThat(body).as("未结束时不替换输入框").doesNotContain("aiq-composer-inner");

        jdbc.update("UPDATE ai_query_messages SET status = 'DONE', answer_md = '答案', finished_at = now() WHERE token = ?", m);
        body = mvc.perform(get("/admin/ai-query/poll").param("w", conv).param("m", m).param("c", conv)
                        .param("lang", "zh_CN").with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).containsPattern("id=\"aiq-row-" + conv + "\"(?![^>]*data-aiq-unfinished)");
        assertThat(body).contains("已完成").contains("id=\"aiq-composer-inner\"").contains("继续追问");
        assertThat(body).doesNotContain("data-aiq-unfinished");
    }

    @Test
    void pollAndRowsRequireTheCode() throws Exception {
        Actor other = staff(AdminPermissions.CONFIG_VIEW);
        mvc.perform(get("/admin/ai-query/poll").with(authentication(other.auth()))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ai-query/rows").with(authentication(other.auth()))).andExpect(status().isForbidden());
    }

    @Test
    void longListLoadsMoreOnScroll() throws Exception {
        enable();
        Actor a = querier();
        for (int i = 0; i < 21; i++) {
            String conv = ask(a, "问题编号" + (100 + i));
            jdbc.update("UPDATE ai_query_messages SET status = 'DONE' WHERE token = ?", msgToken(conv));
        }
        String first = page(a, "/admin/ai-query");
        assertThat(first).contains("问题编号120").doesNotContain("问题编号100");
        assertThat(first).contains("class=\"q-more\"").contains("hx-trigger=\"revealed\"");
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("hx-get=\"/admin/ai-query/rows\\?after=([^\"&]+)").matcher(first);
        assertThat(mm.find()).as("q-more 带游标").isTrue();
        // 列表在打开期间变化（最早的对话被刷新到最前）：游标分页不重复、不漏
        jdbc.update("UPDATE ai_query_conversations SET updated_at = now() WHERE title = '问题编号100'");
        String more = mvc.perform(get("/admin/ai-query/rows").param("after", mm.group(1)).param("lang", "zh_CN")
                        .with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(more).doesNotContain("<html").doesNotContain("问题编号120").doesNotContain("q-more");
        // 非法游标按首屏处理
        mvc.perform(get("/admin/ai-query/rows").param("after", "x.y").with(authentication(a.auth())))
                .andExpect(status().isOk());
    }
}
