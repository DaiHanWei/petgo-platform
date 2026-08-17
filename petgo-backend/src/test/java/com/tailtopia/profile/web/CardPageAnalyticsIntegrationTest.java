package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnonymousVisitorId;
import com.tailtopia.support.ApiIntegrationTest;
import jakarta.servlet.http.Cookie;
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
import org.springframework.http.MediaType;

/**
 * L1：H5 分享页埋点（V1.1.6 Story 1.4 · E-24~E-26）。
 *
 * <p>这一页此前<b>一个埋点都没有</b>。本类钉住四件事：
 * ① 三种页面状态都报得对（{@code page_state} 是本组最有价值的属性）；
 * ② 匿名标识能把同一个人的多次访问串起来（否则漏斗只能看绝对值）；
 * ③ 🛡 上报端点不能被拿来灌任意事件；
 * ④ 🛡 页面里没有任何第三方统计脚本。
 */
@Import(CardPageAnalyticsIntegrationTest.CapturingAnalytics.class)
class CardPageAnalyticsIntegrationTest extends ApiIntegrationTest {

    /** 用一个会记账的假客户端替掉真的 PostHog 实现，好断言「报了什么」。 */
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
    private PetProfileRepository profiles;

    @BeforeEach
    void clearEvents() {
        CapturingAnalytics.EVENTS.clear();
    }

    private String createProfileAndGetToken(User owner) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petType":"CAT","name":"Momo","birthday":"2024-03-10"}
                                """))
                .andExpect(status().isCreated());
        return profiles.findByOwnerId(owner.getId()).orElseThrow().getCardToken();
    }

    private static CapturingAnalytics.Captured only(String event) {
        List<CapturingAnalytics.Captured> hits = CapturingAnalytics.EVENTS.stream()
                .filter(c -> c.event().equals(event)).toList();
        assertThat(hits).as("期望恰好上报一次 %s，实际 %s", event, CapturingAnalytics.EVENTS).hasSize(1);
        return hits.get(0);
    }

    // ===== AC4：page_state 三态 =====

    /**
     * 🔴 <b>{@code empty} 的判据是「没有快乐时刻」，不是「没有里程碑」。</b>
     *
     * <p>Story 1.2 实测：建档动作本身就会自动完成一条里程碑，所以任何档案的里程碑数都 ≥ 1 ——
     * 若按里程碑判空态，这个属性会<b>恒为 full</b>，AC4 那句「唯一依据」直接落空。
     * 这条用例正是钉住这一点：刚建的档案（有 1 个里程碑、0 条快乐时刻）必须报 {@code empty}。
     */
    @Test
    void freshProfileWithNoMomentsReportsEmptyNotFull() throws Exception {
        String token = createProfileAndGetToken(newUser());
        mvc.perform(get("/p/" + token)).andExpect(status().isOk());

        assertThat(only("pet_card_link_opened").props())
                .containsEntry("page_state", "empty");
    }

    @Test
    void expiredLinkReportsGone() throws Exception {
        mvc.perform(get("/p/no-such-token")).andExpect(status().isNotFound());
        assertThat(only("pet_card_link_opened").props()).containsEntry("page_state", "gone");
    }

    /** 🛡 里程碑分享那条链路的失效同样要上报（AC4 要求两条链路都报）。 */
    @Test
    void milestoneShareExpiredAlsoReportsGone() throws Exception {
        mvc.perform(get("/m/no-such-token")).andExpect(status().isNotFound());
        assertThat(only("pet_card_link_opened").props()).containsEntry("page_state", "gone");
    }

    /** 平台与来源域名：来源只取 host，绝不取整条 URL（可能带查询串）。 */
    @Test
    void reportsPlatformAndReferrerHostOnly() throws Exception {
        String token = createProfileAndGetToken(newUser());
        mvc.perform(get("/p/" + token)
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)")
                        .header(HttpHeaders.REFERER, "https://wa.me/chat?token=secret-session-value"))
                .andExpect(status().isOk());

        Map<String, Object> props = only("pet_card_link_opened").props();
        assertThat(props).containsEntry("ua_platform", "ios");
        assertThat(props).containsEntry("referrer_host", "wa.me");
        // 🛡 查询串不得进埋点
        assertThat(props.toString()).doesNotContain("secret-session-value");
    }

    /** 🛡 不承诺 viewer_state（H5 无登录态，服务端做不出这个判断）。 */
    @Test
    void neverClaimsViewerState() throws Exception {
        String token = createProfileAndGetToken(newUser());
        mvc.perform(get("/p/" + token)).andExpect(status().isOk());
        assertThat(only("pet_card_link_opened").props())
                .as("服务端判断不出访客有没有账号，不得凭空给这个属性")
                .doesNotContainKey("viewer_state");
    }

    // ===== AC2：匿名标识串漏斗 =====

    @Test
    void firstVisitIssuesCookieAndSubsequentVisitsReuseIt() throws Exception {
        String token = createProfileAndGetToken(newUser());

        Cookie issued = mvc.perform(get("/p/" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(AnonymousVisitorId.COOKIE_NAME);
        assertThat(issued).as("首访应种下匿名标识 cookie").isNotNull();
        assertThat(issued.getValue()).hasSize(32);
        assertThat(issued.isHttpOnly()).isTrue();

        String firstId = only("pet_card_link_opened").distinctId();
        assertThat(firstId).isEqualTo(issued.getValue());

        // 带着 cookie 再来一次 → 必须是同一个 id，否则漏斗串不起来
        CapturingAnalytics.EVENTS.clear();
        mvc.perform(get("/p/" + token).cookie(issued)).andExpect(status().isOk());
        assertThat(only("pet_card_link_opened").distinctId()).isEqualTo(firstId);
    }

    /** 🛡 失效页那条路径也要种 cookie，否则「打开了失效链接」这批人串不进漏斗。 */
    @Test
    void gonePageAlsoIssuesCookie() throws Exception {
        assertThat(mvc.perform(get("/p/no-such-token"))
                        .andReturn().getResponse().getCookie(AnonymousVisitorId.COOKIE_NAME))
                .isNotNull();
    }

    /** 🛡 匿名 id 不得由任何业务 id 推导：同一个分享页的不同访客必须是不同的人。 */
    @Test
    void differentVisitorsOfTheSamePageGetDifferentIds() throws Exception {
        String token = createProfileAndGetToken(newUser());

        mvc.perform(get("/p/" + token)).andExpect(status().isOk());
        String a = only("pet_card_link_opened").distinctId();
        CapturingAnalytics.EVENTS.clear();
        mvc.perform(get("/p/" + token)).andExpect(status().isOk()); // 另一个访客（无 cookie）
        String b = only("pet_card_link_opened").distinctId();

        assertThat(a)
                .as("同一分享页的不同访客被算成了同一个人 —— 漏斗会失真且历史数据回不来")
                .isNotEqualTo(b);
    }

    // ===== AC3 / AC5：上报端点 =====

    @Test
    void browserCanReportTapAndOutcomeAsTwoSeparateEvents() throws Exception {
        Cookie vid = mvc.perform(get("/p/no-such-token"))
                .andReturn().getResponse().getCookie(AnonymousVisitorId.COOKIE_NAME);
        CapturingAnalytics.EVENTS.clear();

        mvc.perform(post("/p/track").cookie(vid).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"pet_card_cta_tapped","props":{"page_state":"full","ua_platform":"ios"}}
                                """))
                .andExpect(status().isNoContent());
        mvc.perform(post("/p/track").cookie(vid).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"pet_card_cta_outcome","props":{"outcome":"app_opened"}}
                                """))
                .andExpect(status().isNoContent());

        assertThat(CapturingAnalytics.EVENTS).hasSize(2);
        assertThat(only("pet_card_cta_tapped").props()).containsEntry("page_state", "full");
        assertThat(only("pet_card_cta_outcome").props()).containsEntry("outcome", "app_opened");
        // 两个事件属于同一个访客 —— 漏斗才成立
        assertThat(only("pet_card_cta_tapped").distinctId())
                .isEqualTo(only("pet_card_cta_outcome").distinctId());
    }

    /** 🛡 事件名白名单外的一律丢弃，且**仍回 204**（回 4xx 等于帮探测者摸白名单）。 */
    @Test
    void unknownEventsAreDroppedSilently() throws Exception {
        Cookie vid = mvc.perform(get("/p/no-such-token"))
                .andReturn().getResponse().getCookie(AnonymousVisitorId.COOKIE_NAME);
        CapturingAnalytics.EVENTS.clear();

        for (String evil : new String[] {"signup_succeeded", "order_paid", "pet_card_link_opened"}) {
            mvc.perform(post("/p/track").cookie(vid).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"event\":\"" + evil + "\"}"))
                    .andExpect(status().isNoContent());
        }
        assertThat(CapturingAnalytics.EVENTS)
                .as("白名单外的事件被转发了 —— 任何人都能往看板里灌数据")
                .isEmpty();
    }

    /** 🛡 属性白名单：不在表里的键、以及取值不合法的，都不转发。 */
    @Test
    void unknownOrInvalidPropsAreStripped() throws Exception {
        Cookie vid = mvc.perform(get("/p/no-such-token"))
                .andReturn().getResponse().getCookie(AnonymousVisitorId.COOKIE_NAME);
        CapturingAnalytics.EVENTS.clear();

        mvc.perform(post("/p/track").cookie(vid).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"pet_card_cta_outcome",
                                 "props":{"outcome":"made-up-value","email":"a@b.c","page_state":"full"}}
                                """))
                .andExpect(status().isNoContent());

        Map<String, Object> props = only("pet_card_cta_outcome").props();
        assertThat(props).doesNotContainKey("email");      // 白名单外的键
        assertThat(props).doesNotContainKey("outcome");    // 取值不在受控枚举内
        assertThat(props).containsEntry("page_state", "full");
    }

    /** 🛡 请求体里塞 distinctId 无效 —— 服务端只认 cookie。 */
    @Test
    void distinctIdFromRequestBodyIsIgnored() throws Exception {
        Cookie vid = mvc.perform(get("/p/no-such-token"))
                .andReturn().getResponse().getCookie(AnonymousVisitorId.COOKIE_NAME);
        CapturingAnalytics.EVENTS.clear();

        mvc.perform(post("/p/track").cookie(vid).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"pet_card_cta_tapped","distinctId":"someone-elses-id",
                                 "props":{"page_state":"full"}}
                                """))
                .andExpect(status().isNoContent());

        assertThat(only("pet_card_cta_tapped").distinctId())
                .as("事件被挂到了请求体伪造的 id 上")
                .isEqualTo(vid.getValue());
    }

    /** 没有 cookie 的请求（直接 curl 打端点）不报 —— 报了也串不进漏斗，只会污染绝对值。 */
    @Test
    void reportsWithoutCookieAreDropped() throws Exception {
        mvc.perform(post("/p/track").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"pet_card_cta_tapped"}
                                """))
                .andExpect(status().isNoContent());
        assertThat(CapturingAnalytics.EVENTS).isEmpty();
    }

    // ===== AC1：不引第三方 SDK =====

    /** 🛡 页面里不得出现任何第三方统计域名。 */
    @Test
    void pageLoadsNoThirdPartyAnalyticsScript() throws Exception {
        String token = createProfileAndGetToken(newUser());
        String html = mvc.perform(get("/p/" + token)).andReturn().getResponse().getContentAsString();

        for (String vendor : new String[] {"posthog", "google-analytics", "googletagmanager",
                "segment.com", "mixpanel", "amplitude"}) {
            assertThat(html.toLowerCase())
                    .as("页面加载了第三方统计脚本：%s", vendor)
                    .doesNotContain(vendor);
        }
        // 上报只打自家端点
        assertThat(html).contains("/p/track");
    }
}
