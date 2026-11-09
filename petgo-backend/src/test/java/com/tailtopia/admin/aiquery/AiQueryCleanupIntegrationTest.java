package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.tailtopia.admin.aiquery.service.AiQueryCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（agent 桩）：Story 2.2 —— 先删 agent 再删库；agent 不可达跳过；审计保留且不含问题原文。 */
class AiQueryCleanupIntegrationTest extends AiQueryIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiQueryCleanup cleanup;
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

    private void age(String conv, int days) {
        jdbc.update("UPDATE ai_query_messages SET status = 'DONE', answer_md = 'a' WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)", conv);
        jdbc.update("UPDATE ai_query_conversations SET updated_at = now() - make_interval(days => ?) WHERE token = ?", days, conv);
    }

    private long exists(String conv) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_query_conversations WHERE token = ?", Long.class, conv);
    }

    @Test
    void expiredConversationsAreDeletedOnAgentFirstThenInDbAndAuditStays() throws Exception {
        Actor a = querier();
        String old = ask(a, "三十天前的问题 0812-1111-2222");
        String recent = ask(a, "最近的问题");
        age(old, 31);
        age(recent, 29);
        String msg = jdbc.queryForObject("SELECT m.token FROM ai_query_messages m JOIN ai_query_conversations c ON c.id = m.conversation_id WHERE c.token = ?", String.class, old);

        cleanup.run();

        assertThat(stub.requests).anySatisfy(r -> {
            assertThat(r.method()).isEqualTo("DELETE");
            assertThat(r.path()).isEqualTo("/conversations/" + old);
            assertThat(r.secret()).isEqualTo("stub-secret-0123456789abcdef");
        });
        assertThat(stub.requests).noneSatisfy(r -> assertThat(r.path()).isEqualTo("/conversations/" + recent));
        assertThat(exists(old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_query_messages WHERE token = ?", Long.class, msg))
                .as("消息级联删除").isZero();
        assertThat(exists(recent)).isOne();
        // 审计保留，且不含问题原文
        String summary = jdbc.queryForObject("SELECT summary FROM admin_audit_logs WHERE action_type = 'AI_QUERY_SUBMIT' AND target_id = ?",
                String.class, msg);
        assertThat(summary).contains("conversation=" + old).doesNotContain("0812-1111-2222");
    }

    @Test
    void agentUnreachableOrFailingSkipsAndRetriesNextRound() throws Exception {
        Actor a = querier();
        String old = ask(a, "q");
        age(old, 40);
        stub.deleteStatus = 500;
        cleanup.run();
        assertThat(exists(old)).as("agent 失败不删库").isOne();
        aiQueryProperties.setAgentUrl("http://127.0.0.1:1");
        cleanup.run();
        assertThat(exists(old)).as("agent 不可达不删库").isOne();
        aiQueryProperties.setAgentUrl(stub.url());
        stub.deleteStatus = 200;
        cleanup.run();
        assertThat(exists(old)).as("下轮重试成功").isZero();
    }

    @Test
    void failingConversationsDoNotBlockTheOnesBehindThem() throws Exception {
        Actor a = querier();
        String first = ask(a, "最旧，agent 一直失败");
        String second = ask(a, "较新");
        age(first, 45);
        age(second, 35);
        stub.overrides.put("/conversations/" + first, new Object[] {409, "{}".getBytes(), "application/json"});
        cleanup.run();
        assertThat(exists(first)).isOne();
        assertThat(exists(second)).isZero();
    }

    @Test
    void runsEvenWhenTheFeatureIsOff() throws Exception {
        Actor a = querier();
        String old = ask(a, "q");
        age(old, 31);
        aiQueryProperties.setMode("off");
        cleanup.run();
        assertThat(exists(old)).isZero();
    }
}
