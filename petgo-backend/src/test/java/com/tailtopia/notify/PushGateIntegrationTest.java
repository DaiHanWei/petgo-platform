package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.service.SupportTicketService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：V1.4.0 ④ Story 1.2 —— 推送分类开关闸门端到端（需 Docker postgres + redis）。推送用 {@link MockitoBean} 计数。
 *
 * <p>闸门只管系统推送：被拦下的通知照样进通知中心、角标照样 +1。
 */
class PushGateIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private SupportTicketService support;

    @MockitoBean
    private NotificationPusher pusher;

    private void mute(User u, String... categories) throws Exception {
        StringBuilder body = new StringBuilder("{\"categories\":{");
        for (int i = 0; i < categories.length; i++) {
            body.append(i == 0 ? "" : ",").append('"').append(categories[i]).append("\":false");
        }
        body.append("}}");
        mvc.perform(patch("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());
    }

    private void unmute(User u, String category) throws Exception {
        mvc.perform(patch("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":{\"" + category + "\":true}}"))
                .andExpect(status().isOk());
    }

    private long count(User u, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?",
                Long.class, u.getId(), type);
    }

    private long badge(User u) {
        String v = redis.opsForValue().get(NotificationService.UNREAD_KEY_PREFIX + u.getId());
        return v == null ? 0 : Long.parseLong(v);
    }

    private void like(User who, long postId) throws Exception {
        mvc.perform(post("/api/v1/content-posts/" + postId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(who.getId())))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("PRD AC1：关「点赞」→ 通知中心有、角标 +1、不推、不占去抖键；打开后再被赞照推")
    void likeMuted() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        mute(b, "LIKE");
        long postId = posts.save(ContentPost.publish(b.getId(), ContentType.DAILY, null, "正文", List.of())).getId();
        redis.delete(NotificationService.LIKE_PUSH_KEY_PREFIX + postId);
        long before = badge(b);

        like(a, postId);

        assertThat(count(b, "CONTENT_LIKED")).isEqualTo(1);
        assertThat(badge(b) - before).isEqualTo(1);
        assertThat(redis.hasKey(NotificationService.LIKE_PUSH_KEY_PREFIX + postId)).isFalse();
        verify(pusher, never()).pushToUser(eq(b.getId()), any(), any(), any(), any(), any());

        unmute(b, "LIKE");
        like(c, postId);
        verify(pusher, times(1)).pushToUser(eq(b.getId()), any(), any(), eq("CONTENT_LIKED"), any(), any());
    }

    @Test
    @DisplayName("PRD AC2b：关「@ 提及」→ 被 @ 不推，被评论照推")
    void mentionMutedCommentNot() throws Exception {
        User a = newUser();
        User b = newUser();
        mute(b, "MENTION");

        notifications.sendWithCopy(b.getId(), NotificationType.CONTENT_MENTIONED, "CONTENT_MENTIONED", null,
                "t", "b", "1");
        verify(pusher, never()).pushToUser(eq(b.getId()), any(), any(), eq("CONTENT_MENTIONED"), any(), any());

        notifications.sendInteraction(b.getId(), NotificationType.CONTENT_COMMENTED, a.getId(), null, "1", null,
                null, "halo");
        verify(pusher, times(1)).pushToUser(eq(b.getId()), any(), any(), eq("CONTENT_COMMENTED"), any(), any());
        assertThat(count(b, "CONTENT_MENTIONED")).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD AC2a：关「活动与优惠」→ 工单结案两条都进通知中心，只推 TICKET_RESOLVED")
    void ticketResolvedCsatMuted() throws Exception {
        User b = newUser();
        mute(b, "PROMO");
        String token = support.createTicket(b.getId(), "无法登录", "帮我看看", "EMAIL", "a@b.com",
                true, null, List.of(), List.of());
        clearInvocations(pusher);

        support.resolveTicket(token, 700L);

        assertThat(count(b, "TICKET_RESOLVED")).isEqualTo(1);
        assertThat(count(b, "CSAT_SURVEY")).isEqualTo(1);
        verify(pusher, times(1)).pushToUser(eq(b.getId()), any(), any(), any(), any(), any());
        verify(pusher).pushToUser(eq(b.getId()), any(), any(), eq("TICKET_RESOLVED"), any(), any());
    }

    @Test
    @DisplayName("PRD AC3：关掉其余 8 类，订单与账号照推")
    void orderAccountAlwaysPushed() throws Exception {
        User b = newUser();
        mute(b, "LIKE", "COMMENT", "MENTION", "FOLLOWER", "GIFT", "PET_REMINDER", "ACHIEVEMENT", "PROMO");
        for (NotificationType t : List.of(NotificationType.SHOP_ORDER_SHIPPED, NotificationType.PET_SLOT_EXPIRING,
                NotificationType.ACCOUNT_SUSPENDED)) {
            notifications.send(b.getId(), t, "t", "b", t.name(), "r");
        }
        verify(pusher, times(3)).pushToUser(eq(b.getId()), any(), any(), any(), any(), any());
    }
}
