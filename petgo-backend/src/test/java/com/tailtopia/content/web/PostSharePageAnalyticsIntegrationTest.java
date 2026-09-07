package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;

/**
 * L1：单条内容分享页埋点（V1.1.6 Story 10.1 · E-14 {@code post_share_link_opened}）。
 *
 * <p>这一页此前一个埋点都没有 —— FR-73 的漏斗断在最后一环：
 * 点分享（E-11）→ 出图（E-12）→ 递出（E-13）→ <b>？</b>
 *
 * <p>本类钉住三件事：
 * ① {@code open_method} 真能分出「扫码」和「点链接」（否则下载二维码永远无法验收）；
 * ② 🛡 失效链接也上报（分母不能缺这一类）；
 * ③ 🛡 <b>不上报</b> {@code viewer_state} / {@code is_app_installed} ——
 *    服务端做不出这两个判断，报了就是编数据（AD-16 Rule 4）。
 */
@Import(PostSharePageAnalyticsIntegrationTest.CapturingAnalytics.class)
class PostSharePageAnalyticsIntegrationTest extends ApiIntegrationTest {

    /** 用会记账的假客户端替掉真 PostHog 实现（做法同 1-4 的 CardPageAnalyticsIntegrationTest）。 */
    @TestConfiguration
    static class CapturingAnalytics {
        static final List<Captured> EVENTS = new ArrayList<>();

        record Captured(String distinctId, String event, Map<String, Object> props) {
        }

        @Bean
        @Primary
        AnalyticsClient capturingAnalyticsClient() {
            return (distinctId, event, props) -> EVENTS.add(new Captured(distinctId, event, props));
        }
    }

    @Autowired
    private ContentPostRepository posts;

    @BeforeEach
    void clearEvents() {
        CapturingAnalytics.EVENTS.clear();
    }

    private static CapturingAnalytics.Captured only(String event) {
        List<CapturingAnalytics.Captured> hits = CapturingAnalytics.EVENTS.stream()
                .filter(c -> c.event().equals(event)).toList();
        assertThat(hits).as("事件 %s 应恰好一条", event).hasSize(1);
        return hits.get(0);
    }

    private String shareToken(User author) throws Exception {
        ContentPost p = posts.save(ContentPost.publish(author.getId(), ContentType.GROWTH_MOMENT,
                null, "E-14 用的那条", List.of("https://img.example/1.jpg")));
        String body = mvc.perform(post("/api/v1/content-posts/" + p.getId() + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("shareToken").asText();
    }

    // ——————————————————— open_method ———————————————————

    /**
     * 🔴 这是<b>下载二维码唯一的验收依据</b>：它占了卡片页脚近一半版面，
     * {@code qr} 长期极低就该撤掉。分不出来就只能靠感觉吵。
     */
    @Test
    void qrMarkedUrlIsReportedAsQr() throws Exception {
        User author = newUser();
        mvc.perform(get("/c/" + shareToken(author)).param("src", "qr"))
                .andExpect(status().isOk());

        assertThat(only("post_share_link_opened").props())
                .containsEntry("open_method", "qr");
    }

    /** 没有标记就是从文字链接来的 —— 这不是「不知道」，所以默认 link 而非 unknown。 */
    @Test
    void plainUrlIsReportedAsLink() throws Exception {
        User author = newUser();
        mvc.perform(get("/c/" + shareToken(author))).andExpect(status().isOk());

        assertThat(only("post_share_link_opened").props())
                .containsEntry("open_method", "link");
    }

    // ——————————————————— 边界 ———————————————————

    /**
     * 🛡 失效页<b>也报</b>：「打开的是一个已失效的链接」本身就是要看的数。
     * 只报成功的话分母会缺掉这一类，而「分享出去的东西过期得多快」正是要观测的问题。
     */
    @Test
    void goneLinkStillReports() throws Exception {
        mvc.perform(get("/c/NOSUCHTOKEN")).andExpect(status().isNotFound());

        assertThat(only("post_share_link_opened").props())
                .containsEntry("open_method", "link");
    }

    /**
     * 🛡 <b>这两个属性做不出来，就绝不能出现。</b>
     * H5 是无登录态公开页：服务端拿不到「访客是否已有账号」，也判不出「装没装 App」。
     * 埋点清单原话「那个判断做不出来，别写进验收标准」（AD-16 Rule 4）。
     * 一旦有人"顺手补上"（比如拿 cookie 有无去猜新老访客），这条会红 —— 那不是缺陷。
     */
    @Test
    void neverReportsTheTwoPropertiesTheServerCannotKnow() throws Exception {
        User author = newUser();
        mvc.perform(get("/c/" + shareToken(author))).andExpect(status().isOk());

        assertThat(only("post_share_link_opened").props())
                .doesNotContainKeys("viewer_state", "is_app_installed");
    }

    /** 匿名标识必须有 —— 否则 E-14 串不进 E-11→E-12→E-13 那条漏斗，只能看绝对值。 */
    @Test
    void carriesAnAnonymousDistinctId() throws Exception {
        User author = newUser();
        mvc.perform(get("/c/" + shareToken(author))).andExpect(status().isOk());

        assertThat(only("post_share_link_opened").distinctId()).isNotBlank();
    }
}
