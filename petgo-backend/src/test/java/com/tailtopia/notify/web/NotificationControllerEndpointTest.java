package com.tailtopia.notify.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link NotificationController} 端点集成测试（{@code /api/v1/notifications}，4 端点，均需 USER JWT）：
 * 列表（仅本人 + 空态）、未读数、标记单条已读、全部已读、防越权、缺 token → 401。
 *
 * <p>直接用 {@link NotificationRepository} 给独立用户造通知行（每个测试用 {@code newUser()} 唯一 actor，
 * 互不串扰）。未读数读 Redis 角标键；因这些行经 repo 直写未触发 6.1 的角标自增，故角标键缺失 →
 * 服务按库回算并回填，结果仍确定（= 该用户未读行数）。
 */
class NotificationControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private JdbcTemplate jdbc;

    /** 造一条已落库通知（token 由调用方给定，便于断言/标记）。 */
    private Notification persist(long recipientUserId, NotificationType type, String token, boolean read) {
        Notification n = Notification.of(recipientUserId, type, "标题-" + token, "正文-" + token,
                type.name(), token, "ref-" + token);
        if (read) {
            n.markRead();
        }
        return notifications.save(n);
    }

    private String tok() {
        return "tok" + SEQ.incrementAndGet();
    }

    // ---------- 列表 ----------

    /**
     * 列表正常路径：只返回本人通知，不含他人；结构含 token/deepLinkType；不外泄顺序主键 id。
     * targetRef 有意下发（客户端用 deepLinkType + targetRef 算跳转 location，见 NotificationItem javadoc；
     * 仅下发给通知本人）——2026-07-08 修正过时断言（原断言 targetRef 不存在，与已上线的「跳转改用 targetRef」不符）。
     */
    @Test
    void list_returnsOnlyOwnNotifications() throws Exception {
        User me = newUser();
        User other = newUser();
        String mine = tok();
        persist(me.getId(), NotificationType.VET_REPLY, mine, false);
        String hers = tok();
        persist(other.getId(), NotificationType.CONTENT_LIKED, hers, false);

        mvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].deepLinkToken").value(mine))
                .andExpect(jsonPath("$.items[0].type").value("VET_REPLY"))
                .andExpect(jsonPath("$.items[0].read").value(false))
                // 不外泄顺序主键 id
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                // targetRef 有意下发（deep-link 目标，仅本人），值为持久化的 "ref-" + token
                .andExpect(jsonPath("$.items[0].targetRef").value("ref-" + mine));
    }

    /** 空态：无通知用户拿到空 items + hasMore=false + nextCursor 为 null。 */
    @Test
    void list_emptyForUserWithNoNotifications() throws Exception {
        User me = newUser();

        mvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    /** 游标分页：造 3 条、limit=2 → 首页 2 条 + hasMore=true + nextCursor；用 nextCursor 取下一页拿剩 1 条。 */
    @Test
    void list_paginatesWithCursor() throws Exception {
        User me = newUser();
        for (int i = 0; i < 3; i++) {
            persist(me.getId(), NotificationType.CONTENT_COMMENTED, tok(), false);
        }

        String body = mvc.perform(get("/api/v1/notifications")
                        .param("limit", "2")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String nextCursor = json.readTree(body).get("nextCursor").asString();

        mvc.perform(get("/api/v1/notifications")
                        .param("limit", "2")
                        .param("cursor", nextCursor)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    /**
     * 🔴🔴 同刻通知不得在翻页时丢失（2026-08-18 修，`action_items: NOTIFY-CURSOR-TIE`）。
     *
     * <p><b>原缺陷</b>：游标只有 {@code created_at} 且截断到毫秒，查询是严格 {@code <} ——
     * 同一毫秒里有 ≥2 条通知、分页边界又正好落在中间时，<b>那一毫秒里的记录被整批跳过</b>，
     * 用户永久看不到那几条。一毫秒内写入多条通知在生产上完全正常（一次批量触达就是）。
     *
     * <p><b>为什么这条测试要直接改 {@code created_at}</b>：靠 {@code now()} 撞同一微秒是运气 ——
     * 原有的 {@code list_paginatesWithCursor} 就是这么偶发红的（全量跑红、单跑绿，
     * 于是很容易被当成抖动放过）。这里把 5 条的时间戳<b>写死成同一个值</b>，
     * 让缺陷从「偶发」变成「必现」。
     */
    @Test
    @DisplayName("🔴🔴 5 条通知时间戳完全相同 → 逐页翻完，一条不多一条不少")
    void list_doesNotSkipRowsSharingTheSameInstant() throws Exception {
        User me = newUser();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String t = tok();
            expected.add(t);
            persist(me.getId(), NotificationType.CONTENT_COMMENTED, t, false);
        }
        // 🔴 五条同刻（精确到微秒都一样）—— 只有 created_at 的游标在这里必然丢数据。
        //    统一取这批里最早的那个【真实】时间戳，而不是写死一个日期：写死会跟
        //    「首页取 now+60s 之前」的哨兵打架（跑测试那天在 UTC 上过没过那个点纯看运气）。
        jdbc.update("UPDATE notifications SET created_at = "
                + "(SELECT MIN(created_at) FROM notifications WHERE recipient_user_id = ?) "
                + "WHERE recipient_user_id = ?", me.getId(), me.getId());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            var req = get("/api/v1/notifications").param("limit", "2")
                    .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()));
            if (cursor != null) {
                req = req.param("cursor", cursor);
            }
            String body = mvc.perform(req).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var node = json.readTree(body);
            for (var item : node.get("items")) {
                seen.add(item.get("deepLinkToken").asString());
            }
            if (!node.get("hasMore").asBoolean()) {
                break;
            }
            cursor = node.get("nextCursor").asString();
        }

        // 一条不少
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
        // 🔴 一条不多 —— 同刻记录若没有确定顺序，翻页时同一条会重复出现
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("坏游标不 500，退化成首页（游标是客户端传来的，不能信）")
    void list_toleratesGarbageCursor() throws Exception {
        User me = newUser();
        persist(me.getId(), NotificationType.VET_REPLY, tok(), false);

        mvc.perform(get("/api/v1/notifications").param("cursor", "not-a-cursor")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // ---------- 未读数 ----------

    /** 未读数：2 未读 + 1 已读 → count=2（角标键缺失走库回算）。 */
    @Test
    void unreadCount_reflectsUnreadRows() throws Exception {
        User me = newUser();
        persist(me.getId(), NotificationType.VET_REPLY, tok(), false);
        persist(me.getId(), NotificationType.CONTENT_LIKED, tok(), false);
        persist(me.getId(), NotificationType.CONSULT_CLOSED, tok(), true);

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    /** 未读数空态：无通知 → count=0（红点消失语义）。 */
    @Test
    void unreadCount_zeroWhenNone() throws Exception {
        User me = newUser();

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    // ---------- 标记单条已读 ----------

    /** 标记单条已读：200 → 列表中该条 read=true，未读数随之减少。 */
    @Test
    void markRead_marksOneAndDecrementsUnread() throws Exception {
        User me = newUser();
        String token = tok();
        persist(me.getId(), NotificationType.VET_REPLY, token, false);
        persist(me.getId(), NotificationType.CONTENT_LIKED, tok(), false);

        // 先触发一次回算，把角标键写为 2
        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(jsonPath("$.count").value(2));

        mvc.perform(post("/api/v1/notifications/{token}/read", token)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    /** 标记未知 token → 404 ProblemDetail（防枚举）。 */
    @Test
    void markRead_unknownToken_is404() throws Exception {
        User me = newUser();

        mvc.perform(post("/api/v1/notifications/{token}/read", "no-such-token")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    /** 防越权：A 标记不了 B 的通知 token → 对 A 视作不存在 → 404，且 B 那条仍未读。 */
    @Test
    void markRead_otherUsersToken_is404AndUntouched() throws Exception {
        User owner = newUser();
        User attacker = newUser();
        String token = tok();
        persist(owner.getId(), NotificationType.VET_REPLY, token, false);

        // attacker 用 owner 的 token 标记 → 404
        mvc.perform(post("/api/v1/notifications/{token}/read", token)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(attacker.getId())))
                .andExpect(status().isNotFound());

        // owner 那条仍未读
        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    // ---------- 全部已读 ----------

    /** 全部已读：200 → 未读数清零、列表各条 read=true。 */
    @Test
    void readAll_clearsAllUnread() throws Exception {
        User me = newUser();
        persist(me.getId(), NotificationType.VET_REPLY, tok(), false);
        persist(me.getId(), NotificationType.CONTENT_LIKED, tok(), false);

        mvc.perform(post("/api/v1/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        mvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.read == false)]").isEmpty());
    }

    /** 全部已读防越权：A 调 read-all 不影响 B 的未读通知。 */
    @Test
    void readAll_doesNotAffectOtherUser() throws Exception {
        User me = newUser();
        User other = newUser();
        persist(me.getId(), NotificationType.VET_REPLY, tok(), false);
        persist(other.getId(), NotificationType.CONTENT_LIKED, tok(), false);

        mvc.perform(post("/api/v1/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(other.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    // ---------- 鉴权 ----------

    /** 缺 token：列表 → 401。 */
    @Test
    void list_missingToken_is401() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    /** 缺 token：未读数 → 401。 */
    @Test
    void unreadCount_missingToken_is401() throws Exception {
        mvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
    }

    /** 缺 token：标记已读 → 401。 */
    @Test
    void markRead_missingToken_is401() throws Exception {
        mvc.perform(post("/api/v1/notifications/{token}/read", "whatever"))
                .andExpect(status().isUnauthorized());
    }

    /** 缺 token：全部已读 → 401。 */
    @Test
    void readAll_missingToken_is401() throws Exception {
        mvc.perform(post("/api/v1/notifications/read-all"))
                .andExpect(status().isUnauthorized());
    }
}
