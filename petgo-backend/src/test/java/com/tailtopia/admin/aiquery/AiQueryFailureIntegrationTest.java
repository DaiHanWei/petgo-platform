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

/** L1：Story 1.8 —— 失败原因（D1–D4）、用原问题重试、授权失效不可重试且输入框禁用。 */
class AiQueryFailureIntegrationTest extends AiQueryIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private String ask(Actor a, String q) throws Exception {
        return mvc.perform(post("/admin/ai-query").param("question", q).param("answerLang", "zh")
                        .with(csrf()).with(authentication(a.auth())))
                .andReturn().getResponse().getRedirectedUrl().substring("/admin/ai-query/".length());
    }

    private void fail(String conv, String status, String code) {
        jdbc.update("""
                UPDATE ai_query_messages SET status = ?, error_code = ?, finished_at = now()
                 WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)
                   AND status IN ('PENDING', 'RUNNING')""", status, code, conv);
    }

    private List<Map<String, Object>> messages(String conv) {
        return jdbc.queryForList("""
                SELECT m.* FROM ai_query_messages m JOIN ai_query_conversations c ON c.id = m.conversation_id
                 WHERE c.token = ? ORDER BY m.id""", conv);
    }

    private String view(Actor a, String conv) throws Exception {
        return visible(mvc.perform(get("/admin/ai-query/" + conv).param("lang", "zh_CN").with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void eachFailureShowsItsReasonInPlaceWithTheRightColorAndRetry() throws Exception {
        enable();
        Actor a = querier();
        record Case(String status, String code, String cls, String title, String note, boolean retry) {
        }
        for (Case c : List.of(
                new Case("FAILED", "AGENT_ERROR", "bad", "查询没有成功", "可以用原问题重试。", true),
                new Case("FAILED", "AGENT_LOST", "bad", "查询没有成功", "可以用原问题重试。", true),
                new Case("TIMEOUT", "TIMEOUT", "warn", "查询超时", "可以把问题拆小后再试。", true),
                new Case("FAILED", "AGENT_TIMEOUT", "warn", "查询超时", "可以把问题拆小后再试。", true),
                new Case("FAILED", "AGENT_RATE_LIMITED", "warn", "AI 额度暂时用尽", "请稍后重试。", true),
                new Case("FAILED", "AGENT_AUTH_FAILED", "bad", "AI 服务授权已失效", "请联系管理员处理。", false))) {
            String conv = ask(a, "问题-" + c.code());
            fail(conv, c.status(), c.code());
            String html = view(a, conv);
            assertThat(html).as(c.code()).containsPattern("class=\"aiq-err " + c.cls() + "\" role=\"alert\"");
            assertThat(html).as(c.code()).contains(c.title()).contains(c.note());
            assertThat(html.contains("用原问题重试")).as(c.code() + " 重试按钮").isEqualTo(c.retry());
            assertThat(html).as("不出现技术错误码").doesNotContain(">" + c.code() + "<");
            boolean locked = !c.retry();
            assertThat(html.contains("暂时无法提问")).as(c.code() + " 输入框禁用").isEqualTo(locked);
        }
    }

    @Test
    void retryCreatesANewPendingTurnWithTheOriginalQuestion() throws Exception {
        enable();
        Actor a = querier();
        String conv = ask(a, "上周 GMV 按品类拆分");
        fail(conv, "TIMEOUT", "TIMEOUT");
        String first = (String) messages(conv).get(0).get("token");
        mvc.perform(post("/admin/ai-query/" + conv + "/requery").param("m", first).with(csrf()).with(authentication(a.auth())))
                .andExpect(status().is3xxRedirection());
        var ms = messages(conv);
        assertThat(ms).hasSize(2);
        assertThat(ms.get(1)).containsEntry("question", "上周 GMV 按品类拆分").containsEntry("status", "PENDING");
        String html = view(a, conv);
        assertThat(html).contains("查询超时").contains("排队中");
    }

    @Test
    void authLockAlsoBlocksFollowUpAndClearsOnceAnyQuerySucceedsAgain() throws Exception {
        enable();
        // 共享库里别的用例的成功记录不能影响本用例的时间线：失败时刻设在远未来；结束时复原，免得污染后续用例
        Actor a = querier();
        String conv = ask(a, "q");
        String probe = null;
        try {
            jdbc.update("""
                    UPDATE ai_query_messages SET status = 'FAILED', error_code = 'AGENT_AUTH_FAILED',
                           finished_at = now() + interval '100 years'
                     WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)""", conv);
            mvc.perform(post("/admin/ai-query/" + conv + "/messages").param("question", "追问").param("lang", "zh_CN")
                            .with(csrf()).with(authentication(a.auth())))
                    .andExpect(status().isUnprocessableEntity());
            assertThat(messages(conv)).hasSize(1);
            // 新建对话不加锁（总得有人验证更新后的 token）
            probe = ask(a, "验证新 token");
            // 管理员更新 token 后有查询成功 → 锁解除
            jdbc.update("""
                    UPDATE ai_query_messages SET status = 'DONE', answer_md = 'ok', finished_at = now() + interval '101 years'
                     WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)""", probe);
            String html = view(a, conv);
            assertThat(html).contains("AI 服务授权已失效").doesNotContain("暂时无法提问");
            mvc.perform(post("/admin/ai-query/" + conv + "/messages").param("question", "追问").with(csrf()).with(authentication(a.auth())))
                    .andExpect(status().is3xxRedirection());
            assertThat(messages(conv)).hasSize(2);
        } finally {
            jdbc.update("""
                    UPDATE ai_query_messages SET finished_at = now() - interval '1 day'
                     WHERE conversation_id IN (SELECT id FROM ai_query_conversations WHERE token IN (?, ?))""",
                    conv, probe == null ? conv : probe);
        }
    }

    @Test
    void actionsAreHiddenWhileBusyOrDisabled() throws Exception {
        enable();
        Actor a = querier();
        String conv = ask(a, "q");
        fail(conv, "TIMEOUT", "TIMEOUT");
        String first = (String) messages(conv).get(0).get("token");
        mvc.perform(post("/admin/ai-query/" + conv + "/requery").param("m", first).with(csrf()).with(authentication(a.auth())));
        assertThat(view(a, conv)).as("新一轮未完成时不再显示重试").doesNotContain("用原问题重试");
        jdbc.update("UPDATE ai_query_messages SET status = 'DONE', answer_md = '答案' WHERE status = 'PENDING' AND conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)", conv);
        assertThat(view(a, conv)).contains("用原问题重试").contains("重新查询");
        aiQueryProperties.setMode("off");
        assertThat(view(a, conv)).doesNotContain("用原问题重试").doesNotContain("重新查询");
    }

    @Test
    void authFailedCannotBeRetried() throws Exception {
        enable();
        Actor a = querier();
        String conv = ask(a, "q");
        fail(conv, "FAILED", "AGENT_AUTH_FAILED");
        String first = (String) messages(conv).get(0).get("token");
        String html = mvc.perform(post("/admin/ai-query/" + conv + "/requery").param("m", first).param("lang", "zh_CN")
                        .with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("AI 服务授权已失效，请联系管理员处理。");
        assertThat(messages(conv)).hasSize(1);
    }
}
