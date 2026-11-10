package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1：新对话的推荐问题按个人历史显示（本人各对话的首问，去重、倒序、最多 4 条，不足用固定示例补齐）。 */
class AiQueryRecentQuestionsIntegrationTest extends AiQueryIntegrationSupport {

    private static final Pattern SAMPLE = Pattern.compile("data-aiq-sample=\"([^\"]*)\"");

    @Autowired
    private JdbcTemplate jdbc;

    private String start(Actor a, String question) throws Exception {
        String loc = mvc.perform(post("/admin/ai-query").param("question", question).param("answerLang", "zh")
                        .with(csrf()).with(authentication(a.auth())))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        String token = loc.substring("/admin/ai-query/".length());
        finish(token);
        return token;
    }

    private void followUp(Actor a, String token, String question) throws Exception {
        mvc.perform(post("/admin/ai-query/" + token + "/messages").param("question", question).param("answerLang", "zh")
                        .with(csrf()).with(authentication(a.auth())))
                .andExpect(status().is3xxRedirection());
        finish(token);
    }

    private void finish(String token) {
        jdbc.update("""
                UPDATE ai_query_messages SET status = 'DONE', answer_md = '答案', finished_at = now()
                 WHERE conversation_id = (SELECT id FROM ai_query_conversations WHERE token = ?)""", token);
    }

    private List<String> samples(Actor a) throws Exception {
        String html = mvc.perform(get("/admin/ai-query").param("lang", "zh_CN").with(authentication(a.auth())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> out = new ArrayList<>();
        Matcher m = SAMPLE.matcher(html);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void ownFirstQuestionsComeFirstDedupedNewestFirstAndPaddedWithFixedSamples() throws Exception {
        enable();
        Actor a = querier();
        start(a, "问题甲");
        String b = start(a, "问题乙");
        followUp(a, b, "乙的追问");
        start(a, "问题甲");

        List<String> s = samples(a);
        assertThat(s).hasSize(4);
        assertThat(s.subList(0, 2)).containsExactly("问题甲", "问题乙");
        assertThat(s).doesNotContain("乙的追问");
        assertThat(s.subList(2, 4)).containsExactly("上个月新注册用户有多少？按周拆分", "昨天 QRIS 支付成功率是多少？");
    }

    @Test
    void atMostFourAndNoFixedSamplesWhenHistoryIsEnough() throws Exception {
        enable();
        Actor a = querier();
        for (String q : List.of("一", "二", "三", "四", "五")) start(a, q);
        assertThat(samples(a)).containsExactly("五", "四", "三", "二");
    }

    @Test
    void othersHistoryIsNeverShown() throws Exception {
        enable();
        start(querier(), "别人的问题");
        List<String> s = samples(querier());
        assertThat(s).doesNotContain("别人的问题").hasSize(4);
        assertThat(s.get(0)).isEqualTo("上个月新注册用户有多少？按周拆分");
    }
}
