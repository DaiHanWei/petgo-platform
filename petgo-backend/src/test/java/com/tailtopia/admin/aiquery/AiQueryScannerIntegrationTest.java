package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import com.tailtopia.admin.aiquery.service.AiQueryScanner;
import com.tailtopia.admin.aiquery.service.AiQueryTransitions;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（agent 桩）：Story 1.5 —— 派发、轮询、完成 / 失败、404、超时、条件更新、重启后幂等。 */
@ExtendWith(OutputCaptureExtension.class)
class AiQueryScannerIntegrationTest extends AiQueryIntegrationSupport {

    static final String SECRET = "stub-secret-0123456789abcdef";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiQueryScanner scanner;
    @Autowired
    private AiQueryTransitions transitions;
    @Autowired
    private AiQueryMessageRepository messageRepo;

    private AgentStub stub;

    @BeforeEach
    void setUp() throws Exception {
        // 共享库里别的用例可能留下未完成任务：先清空本表的未完成状态，避免本类派发它们
        jdbc.update("UPDATE ai_query_messages SET status = 'FAILED', error_code = 'TEST_RESET' WHERE status IN ('PENDING','RUNNING')");
        stub = new AgentStub();
        enable();
        aiQueryProperties.setAgentUrl(stub.url());
        aiQueryProperties.setAgentSecret(SECRET);
    }

    @AfterEach
    void tearDown() {
        stub.close();
        aiQueryProperties.setAgentUrl("");
        aiQueryProperties.setAgentSecret("");
    }

    private String ask(Actor a, String question) throws Exception {
        String loc = mvc.perform(post("/admin/ai-query").param("question", question).param("answerLang", "id")
                        .with(csrf()).with(authentication(a.auth())))
                .andReturn().getResponse().getRedirectedUrl();
        return loc.substring("/admin/ai-query/".length());
    }

    private void followUp(Actor a, String conv, String question) throws Exception {
        mvc.perform(post("/admin/ai-query/" + conv + "/messages").param("question", question)
                .with(csrf()).with(authentication(a.auth())));
    }

    private Map<String, Object> latest(String conv) {
        return jdbc.queryForMap("""
                SELECT m.* FROM ai_query_messages m JOIN ai_query_conversations c ON c.id = m.conversation_id
                 WHERE c.token = ? ORDER BY m.id DESC LIMIT 1""", conv);
    }

    @Test
    void pendingIsDispatchedThenDoneIsStoredAndFollowUpResumesTheSession(CapturedOutput out) throws Exception {
        Actor a = querier();
        String question = "机密问题 0812-3456-7890";
        String conv = ask(a, question);
        String job = (String) latest(conv).get("token");

        scanner.scan();
        assertThat(latest(conv).get("status")).isEqualTo("RUNNING");
        assertThat(latest(conv).get("submitted_at")).isNotNull();
        AgentStub.Req sub = stub.submits().get(0);
        assertThat(sub.secret()).isEqualTo(SECRET);
        assertThat(sub.body()).contains("\"jobToken\":\"" + job + "\"").contains("\"conversationToken\":\"" + conv + "\"")
                .contains("\"lang\":\"id\"").doesNotContain("resumeSessionId");

        stub.running(job);
        scanner.scan();
        assertThat(latest(conv).get("status")).isEqualTo("RUNNING");

        stub.done(job, "## 结论\n共 **8,420** 人", "11111111-2222-3333-4444-555555555555",
                "[{\"name\":\"a.csv\",\"size\":32768},{\"name\":\"../evil\",\"size\":1}]");
        scanner.scan();
        Map<String, Object> m = latest(conv);
        assertThat(m.get("status")).isEqualTo("DONE");
        assertThat(m.get("answer_md")).isEqualTo("## 结论\n共 **8,420** 人");
        assertThat(m.get("agent_secs")).isEqualTo(28);
        assertThat(m.get("agent_turns")).isEqualTo(3);
        assertThat(m.get("cost_usd").toString()).isEqualTo("0.1235");
        assertThat(m.get("finished_at")).isNotNull();
        assertThat(m.get("files").toString()).contains("a.csv").contains("32768").doesNotContain("evil");
        assertThat(jdbc.queryForObject("SELECT agent_session_id FROM ai_query_conversations WHERE token = ?", String.class, conv))
                .isEqualTo("11111111-2222-3333-4444-555555555555");

        followUp(a, conv, "按平台拆分");
        scanner.scan();
        assertThat(stub.submits().get(stub.submits().size() - 1).body())
                .contains("\"resumeSessionId\":\"11111111-2222-3333-4444-555555555555\"");
        // 日志不含问题和答案
        assertThat(out.getAll()).doesNotContain("0812-3456-7890").doesNotContain("8,420").doesNotContain("按平台拆分");
    }

    @Test
    void queueFullOrUnreachableKeepsPendingAndRetriesNextRound() throws Exception {
        Actor a = querier();
        String conv = ask(a, "q1");
        stub.submitStatus = 429;
        scanner.scan();
        assertThat(latest(conv).get("status")).isEqualTo("PENDING");
        aiQueryProperties.setAgentUrl("http://127.0.0.1:1");
        scanner.scan();
        assertThat(latest(conv).get("status")).isEqualTo("PENDING");
        aiQueryProperties.setAgentUrl(stub.url());
        stub.submitStatus = 202;
        scanner.scan();
        assertThat(latest(conv).get("status")).isEqualTo("RUNNING");
    }

    @Test
    void agentFailureCodesAreStoredAndUnknownJobBecomesFailed(CapturedOutput out) throws Exception {
        Actor a = querier();
        String c1 = ask(a, "q1");
        String c2 = ask(a, "授权失效时的问题原文");
        String c3 = ask(a, "q3");
        scanner.scan();
        stub.failed((String) latest(c1).get("token"), "AGENT_RATE_LIMITED");
        stub.failed((String) latest(c2).get("token"), "AGENT_AUTH_FAILED");
        // c3：agent 不认识（重启丢失）→ 404
        stub.forget((String) latest(c3).get("token"));
        scanner.scan();
        assertThat(latest(c1)).containsEntry("status", "FAILED").containsEntry("error_code", "AGENT_RATE_LIMITED");
        assertThat(latest(c2)).containsEntry("status", "FAILED").containsEntry("error_code", "AGENT_AUTH_FAILED");
        assertThat(latest(c3)).containsEntry("status", "FAILED").containsEntry("error_code", "AGENT_LOST");
        // 授权失效记 error 日志（Story 1.8），只有 token，不含问题和答案
        String authLog = out.getAll().lines().filter(l -> l.contains("authorization failed")).findFirst().orElse("");
        assertThat(authLog).contains("ERROR").contains((String) latest(c2).get("token"));
        assertThat(out.getAll()).doesNotContain("授权失效时的问题原文");
    }

    @Test
    void unfinishedForMoreThan15MinutesTimesOut() throws Exception {
        Actor a = querier();
        String pending = ask(a, "排队太久");
        String running = ask(a, "跑太久");
        jdbc.update("UPDATE ai_query_messages SET status = 'RUNNING' WHERE token = ?", latest(running).get("token"));
        jdbc.update("""
                UPDATE ai_query_messages SET created_at = now() - interval '16 minutes'
                 WHERE conversation_id IN (SELECT id FROM ai_query_conversations WHERE token IN (?, ?))""", pending, running);
        stub.submitStatus = 429;
        scanner.scan();
        assertThat(latest(pending)).containsEntry("status", "TIMEOUT").containsEntry("error_code", "TIMEOUT");
        assertThat(latest(running)).containsEntry("status", "TIMEOUT");
        assertThat(latest(running).get("finished_at")).isNotNull();
    }

    @Test
    void conditionalUpdatesSucceedOnlyOnce() throws Exception {
        Actor a = querier();
        String conv = ask(a, "q");
        AiQueryMessage m = messageRepo.findByConversationIdOrderByIdAsc(
                jdbc.queryForObject("SELECT id FROM ai_query_conversations WHERE token = ?", Long.class, conv)).get(0);
        assertThat(transitions.running(m)).isTrue();
        assertThat(transitions.running(m)).as("重复派发").isFalse();
        stub.done(m.getToken(), "答案一", "s1", "[]");
        scanner.scan();
        assertThat(latest(conv).get("status")).isEqualTo("DONE");
        // 结果已落定后，迟到的失败 / 超时都不能覆盖
        assertThat(transitions.failed(m, com.tailtopia.admin.aiquery.domain.AiQueryStatus.RUNNING, "AGENT_ERROR", null)).isFalse();
        assertThat(transitions.timeout(m, Instant.now().plusSeconds(3600))).isFalse();
        assertThat(latest(conv)).containsEntry("status", "DONE").containsEntry("answer_md", "答案一");
    }

    @Test
    void afterOpsRestartResubmittingIsIdempotentAndResultIsStillFetched() throws Exception {
        Actor a = querier();
        String conv = ask(a, "q");
        String job = (String) latest(conv).get("token");
        scanner.scan();
        // 模拟「已提交给 agent、但 ops 在写 RUNNING 前重启」：状态回到 PENDING，重启后再提交一次
        jdbc.update("UPDATE ai_query_messages SET status = 'PENDING', submitted_at = NULL WHERE token = ?", job);
        scanner.scan();
        assertThat(stub.submits()).hasSize(2).allSatisfy(r -> assertThat(r.body()).contains(job));
        stub.done(job, "重启后的答案", "s2", "[]");
        scanner.scan();
        assertThat(latest(conv)).containsEntry("status", "DONE").containsEntry("answer_md", "重启后的答案");
    }

    @Test
    void modeOffDoesNothing() throws Exception {
        Actor a = querier();
        String conv = ask(a, "q");
        aiQueryProperties.setMode("off");
        scanner.scan();
        assertThat(stub.requests).isEmpty();
        assertThat(latest(conv).get("status")).isEqualTo("PENDING");
    }
}
