package com.tailtopia.notify.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

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
