package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：Story 1.7 —— 答案原文经服务端转义后交给前端净化渲染（不直接进 DOM）、固定版本 vendor、页脚、重新查询。
 * 净化配置本身的 XSS 用例在 {@code src/test/js/ai-query-render.test.mjs}（NFR13）。
 */
class AiQueryAnswerIntegrationTest extends AiQueryIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private String ask(Actor a, String q) throws Exception {
        return mvc.perform(post("/admin/ai-query").param("question", q).param("answerLang", "zh")
                        .with(csrf()).with(authentication(a.auth())))
                .andReturn().getResponse().getRedirectedUrl().substring("/admin/ai-query/".length());
    }

    private List<Map<String, Object>> messages(String conv) {
        return jdbc.queryForList("""
                SELECT m.* FROM ai_query_messages m JOIN ai_query_conversations c ON c.id = m.conversation_id
                 WHERE c.token = ? ORDER BY m.id""", conv);
    }

    private void done(String conv, String answer) {
        jdbc.update("""
                UPDATE ai_query_messages SET status = 'DONE', answer_md = ?, agent_secs = 28, finished_at = now()
                 WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?) AND status = 'PENDING'""",
                answer, conv);
    }

    @Test
    void answerIsEscapedIntoATemplateAndRenderedOnlyThroughTheSanitizer() throws Exception {
        enable();
        Actor a = querier();
        String conv = ask(a, "注册数");
        done(conv, "10 月共 **8,420** 人 <script>alert(1)</script><img src=x onerror=alert(2)>");
        String html = mvc.perform(get("/admin/ai-query/" + conv).param("lang", "zh_CN").with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("<script>alert(1)").doesNotContain("<img src=x");
        assertThat(html).contains("<template data-aiq-md>10 月共 **8,420** 人 &lt;script&gt;alert(1)&lt;/script&gt;&lt;img src=x onerror=alert(2)&gt;</template>");
        assertThat(html).contains("data-aiq-rendered");
        assertThat(html).containsPattern("src=\"/admin/vendor/marked\\.umd[-0-9a-f]*\\.js\"")
                .containsPattern("src=\"/admin/vendor/purify\\.min[-0-9a-f]*\\.js\"")
                .containsPattern("src=\"/admin/admin-ai-query-render[-0-9a-f]*\\.js\"");
        assertThat(html).contains("耗时 28 秒").contains("data-aiq-copy").contains("复制").contains("重新查询");
    }

    @Test
    void requeryAddsATurnWithTheOriginalQuestionAndRespectsLimits() throws Exception {
        enable();
        Actor a = querier();
        String conv = ask(a, "原问题 A");
        String first = (String) messages(conv).get(0).get("token");
        // 未完成时重新查询被拒（同一对话忙）
        String html = mvc.perform(post("/admin/ai-query/" + conv + "/requery").param("m", first).param("lang", "zh_CN")
                        .with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("当前问题完成后可以继续追问。");
        done(conv, "答案");
        mvc.perform(post("/admin/ai-query/" + conv + "/requery").param("m", first).with(csrf()).with(authentication(a.auth())))
                .andExpect(status().is3xxRedirection());
        var ms = messages(conv);
        assertThat(ms).hasSize(2);
        assertThat(ms.get(1).get("question")).isEqualTo("原问题 A");
        assertThat(ms.get(1).get("status")).isEqualTo("PENDING");

        // 单人上限 3：再开两个未完成对话后，第 4 个（重新查询）被拒
        done(conv, "答案2");
        ask(a, "q2");
        ask(a, "q3");
        ask(a, "q4");
        html = mvc.perform(post("/admin/ai-query/" + conv + "/requery").param("m", first).param("lang", "zh_CN")
                        .with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("你有 3 个查询正在进行");
    }

    @Test
    void requeryOfOthersConversationOrForeignMessageIs404() throws Exception {
        enable();
        Actor owner = querier();
        String conv = ask(owner, "我的");
        done(conv, "答案");
        String msg = (String) messages(conv).get(0).get("token");
        Actor other = querier();
        mvc.perform(post("/admin/ai-query/" + conv + "/requery").param("m", msg).with(csrf()).with(authentication(other.auth())))
                .andExpect(status().isNotFound());
        String otherConv = ask(other, "别人的");
        // 拿自己的对话 + 别人的消息 token：也是 404
        mvc.perform(post("/admin/ai-query/" + otherConv + "/requery").param("m", msg).with(csrf()).with(authentication(other.auth())))
                .andExpect(status().isNotFound());
        assertThat(messages(conv)).hasSize(1);
    }
}
