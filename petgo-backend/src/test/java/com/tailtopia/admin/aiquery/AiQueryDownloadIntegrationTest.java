package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（agent 桩）：Story 2.1 —— 文件卡、归属校验后取回、审计、他人 404、文件名拒绝、已清除。 */
class AiQueryDownloadIntegrationTest extends AiQueryIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbc;
    private AgentStub stub;

    @BeforeEach
    void setUp() throws Exception {
        stub = new AgentStub();
        enable();
        aiQueryProperties.setAgentUrl(stub.url());
        aiQueryProperties.setAgentSecret("stub-secret-0123456789abcdef");
    }

    @AfterEach
    void tearDown() {
        stub.close();
        aiQueryProperties.setAgentUrl("");
        aiQueryProperties.setAgentSecret("");
    }

    private String ask(Actor a, String q) throws Exception {
        return mvc.perform(post("/admin/ai-query").param("question", q).param("answerLang", "zh")
                        .with(csrf()).with(authentication(a.auth())))
                .andReturn().getResponse().getRedirectedUrl().substring("/admin/ai-query/".length());
    }

    /** 让对话的那一轮完成并带两个文件，返回消息 token。 */
    private String doneWithFiles(String conv) {
        jdbc.update("""
                UPDATE ai_query_messages SET status = 'DONE', answer_md = '本月共 **316** 笔，明细已导出。',
                       files = '[{"name":"KTP明细-11月.csv","size":32768},{"name":"b.xlsx","size":900}]'::jsonb,
                       agent_secs = 41, finished_at = now()
                 WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)""", conv);
        return jdbc.queryForObject("""
                SELECT m.token FROM ai_query_messages m JOIN ai_query_conversations c ON c.id = m.conversation_id
                 WHERE c.token = ?""", String.class, conv);
    }

    private String url(String conv, String msg, String name) {
        return "/admin/ai-query/" + conv + "/files/" + msg + "/" + name;
    }

    @Test
    void fileCardsShowNameSizeAndDownload() throws Exception {
        Actor a = querier();
        String conv = ask(a, "导出本月 KTP 高清解锁明细");
        String msg = doneWithFiles(conv);
        String html = visible(mvc.perform(get("/admin/ai-query/" + conv).param("lang", "zh_CN").with(authentication(a.auth())))
                .andReturn().getResponse().getContentAsString());
        assertThat(html).contains("KTP明细-11月.csv").contains("32 KB").contains("900 B").contains(">下载<");
        assertThat(html).contains("/admin/ai-query/" + conv + "/files/" + msg + "/");
        assertThat(html).doesNotContain("已清除");
    }

    @Test
    void ownerDownloadsThroughOpsWithAuditAndNoQuestionText() throws Exception {
        Actor a = querier();
        String conv = ask(a, "含手机号 081299998888 的导出");
        String msg = doneWithFiles(conv);
        stub.overrides.put("/jobs/" + msg + "/files/" + "KTP明细-11月.csv",
                new Object[] {200, "week,count\n1,1612\n".getBytes(StandardCharsets.UTF_8), "application/octet-stream"});
        var res = mvc.perform(get(url(conv, msg, "KTP明细-11月.csv")).with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("week,count\n1,1612\n");
        assertThat(res.getHeader("Content-Disposition")).startsWith("attachment;").contains("filename*=UTF-8''");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        AgentStub.Req req = stub.requests.get(stub.requests.size() - 1);
        assertThat(req.secret()).isEqualTo("stub-secret-0123456789abcdef");
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT * FROM admin_audit_logs WHERE action_type = 'AI_QUERY_DOWNLOAD' AND target_id = ? ORDER BY id DESC LIMIT 1""", msg);
        assertThat(audit.get("actor_account_id")).isEqualTo(a.accountId());
        assertThat((String) audit.get("summary")).contains("conversation=" + conv).contains("file=KTP明细-11月.csv")
                .doesNotContain("081299998888");
    }

    @Test
    void othersFilesAndUnlistedNamesAre404AndBadNamesRejected() throws Exception {
        Actor owner = querier();
        String conv = ask(owner, "q");
        String msg = doneWithFiles(conv);
        Actor other = querier();
        mvc.perform(get(url(conv, msg, "b.xlsx")).with(authentication(other.auth()))).andExpect(status().isNotFound());
        mvc.perform(get(url(conv, msg, "b.xlsx")).with(authentication(superAdmin().auth()))).andExpect(status().isNotFound());
        mvc.perform(get(url(conv, msg, "not-listed.csv")).with(authentication(owner.auth()))).andExpect(status().isNotFound());
        mvc.perform(get(url(conv, msg, ".env")).with(authentication(owner.auth()))).andExpect(status().isBadRequest());
        mvc.perform(get(url(conv, msg, "..")).with(authentication(owner.auth()))).andExpect(status().isBadRequest());
        mvc.perform(get(url(conv, msg, "b.xlsx")).with(authentication(staff(AdminPermissions.CONFIG_VIEW).auth())))
                .andExpect(status().isForbidden());
        assertThat(stub.requests).as("没通过校验的请求不会打到 agent").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_logs WHERE action_type = 'AI_QUERY_DOWNLOAD' AND target_id = ?",
                Long.class, msg)).isZero();
    }

    @Test
    void clearedFilesShowGoneAndCannotBeDownloaded() throws Exception {
        Actor a = querier();
        String conv = ask(a, "q");
        String msg = doneWithFiles(conv);
        // agent 上已不存在 → 410
        String html = mvc.perform(get(url(conv, msg, "b.xlsx")).param("lang", "zh_CN").with(authentication(a.auth())))
                .andExpect(status().isGone()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("文件已清除，请重新导出");
        // 超过保留期：卡片显示已清除、没有下载链接，下载 410 且不打到 agent
        jdbc.update("UPDATE ai_query_messages SET finished_at = now() - interval '31 days' WHERE token = ?", msg);
        String page = visible(mvc.perform(get("/admin/ai-query/" + conv).param("lang", "zh_CN").with(authentication(a.auth())))
                .andReturn().getResponse().getContentAsString());
        assertThat(page).contains("已清除").contains("文件已超过保留期，请重新导出").doesNotContain("/files/" + msg + "/");
        int before = stub.requests.size();
        mvc.perform(get(url(conv, msg, "b.xlsx")).with(authentication(a.auth()))).andExpect(status().isGone());
        assertThat(stub.requests).hasSize(before);
    }
}
