package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1：Story 1.4 —— 提交只写 PENDING、追问、校验、上限、归属 404、审计不含原文。 */
@ExtendWith(OutputCaptureExtension.class)
class AiQuerySubmitIntegrationTest extends AiQueryIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private String start(Actor a, String question, String lang) throws Exception {
        var res = mvc.perform(post("/admin/ai-query").param("question", question).param("answerLang", lang).with(csrf()).with(authentication(a.auth())))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        String loc = res.getRedirectedUrl();
        assertThat(loc).startsWith("/admin/ai-query/");
        return loc.substring("/admin/ai-query/".length());
    }

    private Map<String, Object> conversation(String token) {
        return jdbc.queryForMap("SELECT * FROM ai_query_conversations WHERE token = ?", token);
    }

    private List<Map<String, Object>> messages(String token) {
        return jdbc.queryForList("""
                SELECT m.* FROM ai_query_messages m JOIN ai_query_conversations c ON c.id = m.conversation_id
                 WHERE c.token = ? ORDER BY m.id""", token);
    }

    private void finishAll(String conversationToken) {
        jdbc.update("""
                UPDATE ai_query_messages SET status = 'DONE', answer_md = '答案', finished_at = now()
                 WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)""", conversationToken);
    }

    @Test
    void submitWritesOnlyPendingWithUnguessableTokensAndReturnsImmediately(CapturedOutput out) throws Exception {
        enable();
        Actor a = querier();
        String secret = "手机号 081234567890 的用户上月付了多少 " + System.nanoTime();
        String token = start(a, "  " + secret + "\n", "id");

        assertThat(token).matches("[A-Za-z0-9_-]{32}");
        Map<String, Object> c = conversation(token);
        assertThat(c.get("admin_account_id")).isEqualTo(a.accountId());
        assertThat(c.get("language")).isEqualTo("ID");
        assertThat((String) c.get("title")).isEqualTo(secret.substring(0, Math.min(80, secret.length())));
        var ms = messages(token);
        assertThat(ms).hasSize(1);
        assertThat(ms.get(0).get("status")).isEqualTo("PENDING");
        assertThat(ms.get(0).get("question")).isEqualTo(secret);
        assertThat(ms.get(0).get("submitted_at")).as("不同步调用 agent").isNull();
        assertThat((String) ms.get(0).get("token")).matches("[A-Za-z0-9_-]{32}").isNotEqualTo(token);

        // 审计：有 AI_QUERY_SUBMIT，只含 token / 语言 / 长度，不含原文
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT * FROM admin_audit_logs WHERE action_type = 'AI_QUERY_SUBMIT' AND target_id = ?""",
                ms.get(0).get("token"));
        String summary = (String) audit.get("summary");
        assertThat(audit.get("actor_account_id")).isEqualTo(a.accountId());
        assertThat(summary).contains("conversation=" + token).contains("lang=id")
                .contains("length=" + secret.codePointCount(0, secret.length()))
                .doesNotContain("081234567890").doesNotContain("付了多少");
        // 应用日志里没有问题原文
        assertThat(out.getAll()).doesNotContain("081234567890").doesNotContain(secret);

        // 对话页渲染问题与排队状态
        String html = mvc.perform(get("/admin/ai-query/" + token).param("lang", "zh_CN").with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(secret).contains("排队中").contains("回答语言：Bahasa Indonesia");
        assertThat(html).contains("aiq-box is-dis").contains("当前问题完成后可以继续追问");
    }

    @Test
    void emptyAndTooLongAreRejectedNextToTheBoxKeepingTheText() throws Exception {
        enable();
        Actor a = querier();
        String html = mvc.perform(post("/admin/ai-query").param("question", "   ").param("answerLang", "zh")
                        .param("lang", "zh_CN").with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("role=\"alert\"").contains("请输入问题");

        String longQ = "查".repeat(2135);
        html = mvc.perform(post("/admin/ai-query").param("question", longQ).param("answerLang", "zh")
                        .param("lang", "zh_CN").with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("2,135").contains(longQ);
        // 2000 字正好可以
        start(a, "查".repeat(2000), "zh");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ai_query_conversations WHERE admin_account_id = ?""", Long.class, a.accountId()))
                .isEqualTo(1L);
    }

    @Test
    void modeOffRejectsSubmission() throws Exception {
        Actor a = querier();
        String html = mvc.perform(post("/admin/ai-query").param("question", "q").param("answerLang", "zh")
                        .param("lang", "en").with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("AI Query is not enabled yet").doesNotContain("redirect");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_query_conversations WHERE admin_account_id = ?",
                Long.class, a.accountId())).isZero();
    }

    @Test
    void followUpKeepsLanguageAndIsBlockedWhileUnfinished() throws Exception {
        enable();
        Actor a = querier();
        String token = start(a, "第一问", "en");
        // 有未完成任务：拒绝追问
        String html = mvc.perform(post("/admin/ai-query/" + token + "/messages").param("question", "追问")
                        .param("lang", "zh_CN").with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("当前问题完成后可以继续追问。").contains("追问");
        assertThat(messages(token)).hasSize(1);

        finishAll(token);
        mvc.perform(post("/admin/ai-query/" + token + "/messages").param("question", "追问").param("answerLang", "zh")
                        .with(csrf()).with(authentication(a.auth())))
                .andExpect(status().is3xxRedirection());
        var ms = messages(token);
        assertThat(ms).hasSize(2);
        assertThat(ms.get(1).get("status")).isEqualTo("PENDING");
        assertThat(conversation(token).get("language")).as("语言按对话保存，追问不可改").isEqualTo("EN");
        assertThat(jdbc.queryForObject("""
                SELECT summary FROM admin_audit_logs WHERE action_type = 'AI_QUERY_SUBMIT' AND target_id = ?""",
                String.class, ms.get(1).get("token"))).contains("followUp=true").contains("lang=en");
    }

    @Test
    void atMostThreeUnfinishedPerUser() throws Exception {
        enable();
        Actor a = querier();
        for (int i = 0; i < 3; i++) {
            start(a, "问题" + i, "zh");
        }
        String html = mvc.perform(post("/admin/ai-query").param("question", "第四个").param("answerLang", "zh")
                        .param("lang", "zh_CN").with(csrf()).with(authentication(a.auth())))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("你有 3 个查询正在进行").contains("第四个");
        // 别人不受影响
        start(querier(), "别人的问题", "zh");
    }

    @Test
    void othersConversationsAreUniform404EvenForSuperAdmin() throws Exception {
        enable();
        Actor owner = querier();
        String token = start(owner, "我的问题", "zh");
        finishAll(token);

        for (Actor other : List.of(querier(), superAdmin())) {
            String html = mvc.perform(get("/admin/ai-query/" + token).param("lang", "zh_CN").with(authentication(other.auth())))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("对话不存在或已过期").doesNotContain("我的问题");
            mvc.perform(post("/admin/ai-query/" + token + "/messages").param("question", "偷看").with(csrf())
                            .with(authentication(other.auth())))
                    .andExpect(status().isNotFound());
        }
        // 不存在、格式非法：同一结果
        String missing = mvc.perform(get("/admin/ai-query/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").param("lang", "zh_CN")
                        .with(authentication(owner.auth())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(missing).contains("对话不存在或已过期");
        mvc.perform(get("/admin/ai-query/bad!token").with(authentication(owner.auth()))).andExpect(status().isNotFound());
        // 已过期（超过保留期）
        jdbc.update("UPDATE ai_query_conversations SET updated_at = now() - interval '31 days' WHERE token = ?", token);
        mvc.perform(get("/admin/ai-query/" + token).with(authentication(owner.auth()))).andExpect(status().isNotFound());
        assertThat(messages(token)).hasSize(1);
        // 左栏只列本人未过期对话
        String list = mvc.perform(get("/admin/ai-query").with(authentication(owner.auth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(token);
    }

    @Test
    void withoutCodeEveryRouteIsDenied() throws Exception {
        enable();
        Actor owner = querier();
        String token = start(owner, "q", "zh");
        Actor other = staff(AdminPermissions.CONFIG_VIEW);
        mvc.perform(get("/admin/ai-query").with(authentication(other.auth()))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ai-query/" + token).with(authentication(other.auth()))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/ai-query").param("question", "q").with(csrf()).with(authentication(other.auth())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/ai-query/" + token + "/messages").param("question", "q").with(csrf())
                        .with(authentication(other.auth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listShowsOwnConversationsNewestFirstWithStatus() throws Exception {
        enable();
        Actor a = querier();
        String first = start(a, "较早的问题", "zh");
        finishAll(first);
        String second = start(a, "较新的问题", "zh");
        String html = mvc.perform(get("/admin/ai-query/" + second).param("lang", "zh_CN").with(authentication(a.auth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(html.indexOf("较新的问题")).isLessThan(html.indexOf("较早的问题"));
        assertThat(html).contains("aiq-row-" + first).contains("aiq-row-" + second).contains("已完成").contains("排队中");
        assertThat(html).containsPattern("class=\"q-row on\"[^>]*href=\"/admin/ai-query/" + second + "\"");
    }
}
