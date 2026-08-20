package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1：互动通知抑制（Story 1.4，FR-94）—— 需 Docker postgres+redis。
 *
 * <p>L0 单测验的是「监听器判对了没有」，本层验的是<b>真链路上通知到底有没有落库、角标有没有涨</b>：
 * 事件是 {@code @TransactionalEventListener}（AFTER_COMMIT），角标写在 Redis，
 * 这两样只有真跑一遍才作数。
 *
 * <p><b>抑制的定义是「压根不发」而不是「发了再隐藏」</b>：所以断言盯的是通知<b>行不存在</b>、
 * 未读角标<b>不增加</b>——而不是「列表里过滤掉了」。
 */
class InteractionNotifySuppressionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private CommentRepository comments;

    /**
     * ⚠️ 评论的通知是<b>异步到达</b>的，断言前必须等：评论落库时是 {@code UNDER_REVIEW}，
     * 机器审核在另一个线程跑，通过后才由 {@code CommentService.approveComment} 发
     * {@code ContentCommentedEvent}，通知监听器再在那个事务 AFTER_COMMIT 时触发。
     *
     * <p>点赞没有这一段（同步发事件），所以只有评论/回复需要过这道闸。
     * 不等就断言的话，「抑制成功」与「异步还没跑完」<b>看起来一模一样</b>——测试会假绿。
     */
    private void awaitProcessed(long commentId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var c = comments.findById(commentId).orElseThrow();
            if (c.getModerationStatus() == CommentModerationStatus.VISIBLE) {
                Thread.sleep(120); // 让 AFTER_COMMIT 的通知监听器跑完
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("评论 " + commentId + " 迟迟没走完审核，无法判断通知是否被抑制");
    }

    private ContentPost newPost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "测试正文", List.of()));
    }

    private void like(long userId, long postId) throws Exception {
        mvc.perform(post("/api/v1/content-posts/" + postId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().is2xxSuccessful());
    }

    private long comment(long userId, long postId, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/content-posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        awaitProcessed(id);
        return id;
    }

    private void reply(long userId, long parentId, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/comments/" + parentId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        awaitProcessed(json.readTree(r.getResponse().getContentAsString()).get("id").asLong());
    }

    /** 某人通知中心里的通知类型列表（最新在前）。 */
    private List<String> notificationTypesOf(long userId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn();
        var items = json.readTree(r.getResponse().getContentAsString()).get("items");
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(n -> n.get("type").asText())
                .toList();
    }

    private long unreadCountOf(long userId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("count").asLong();
    }

    // ===== AC1 + AC2 · 不发，而不是发了再隐藏 =====

    @Test
    void ac1_likeFromHiddenUserLeavesNoNotificationAndNoBadge() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        hideService.block(a.getId(), b.getId());
        like(b.getId(), postId);

        assertThat(notificationTypesOf(a.getId())).isEmpty(); // 行不存在
        assertThat(unreadCountOf(a.getId())).isZero();        // 角标不涨
    }

    @Test
    void ac1_commentFromHiddenUserLeavesNoNotification() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        hideService.block(a.getId(), b.getId());
        comment(b.getId(), postId, "B 的评论");

        assertThat(notificationTypesOf(a.getId())).isEmpty();
        assertThat(unreadCountOf(a.getId())).isZero();
    }

    /** 抑制不区分来源：举报产生的隐藏关系同样压掉通知（Story 2.1 靠这条自动复用）。 */
    @Test
    void ac1_reportSourcedHideAlsoSuppresses() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        hideService.hideByReport(a.getId(), b.getId());
        like(b.getId(), postId);

        assertThat(notificationTypesOf(a.getId())).isEmpty();
    }

    /** 没有隐藏关系时一切照旧——这条是上面几条的对照组，防「把所有通知都压掉了」还以为成功。 */
    @Test
    void baseline_likeAndCommentStillNotifyWhenNobodyHidAnybody() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        like(b.getId(), postId);
        comment(b.getId(), postId, "B 的评论");

        assertThat(notificationTypesOf(a.getId()))
                .contains("CONTENT_LIKED", "CONTENT_COMMENTED");
        assertThat(unreadCountOf(a.getId())).isEqualTo(2);
    }

    // ===== AC3 · R3：第三方那一支 =====

    /**
     * ⚠️ 端到端跑一遍 R3：A 拉黑 B；C 在 A 的帖子下发一级评论；B 回复 C。
     *
     * <p>那条回复因 R2 对所有人隐藏（Story 1.3），所以 <b>C 也不该收到「有人回复了你的评论」</b> ——
     * 否则他点进去什么都没有，一对比就能推断出屏蔽机制存在。C 自己没拉黑任何人。
     */
    @Test
    void ac3_thirdPartyGetsNoNotificationForAShadowedReply() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();

        hideService.block(a.getId(), b.getId());
        long parent = comment(c.getId(), postId, "C 的一级评论"); // A 会因此收到一条（C 未被拉黑）
        reply(b.getId(), parent, "B 回复 C");

        assertThat(notificationTypesOf(c.getId())).isEmpty(); // ⚠️ 第三方 C 一条都收不到
        // A 只收到 C 那一条，B 的回复没给他产生第二条。
        assertThat(notificationTypesOf(a.getId())).containsExactly("CONTENT_COMMENTED");
        assertThat(unreadCountOf(a.getId())).isEqualTo(1);
    }

    /** 对照：内容作者没拉黑任何人时，同样的回复链路 C 照常收到通知。 */
    @Test
    void ac3_baseline_thirdPartyIsNotifiedWhenNobodyHidTheCommenter() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();

        long parent = comment(c.getId(), postId, "C 的一级评论");
        reply(b.getId(), parent, "B 回复 C");

        assertThat(notificationTypesOf(c.getId())).containsExactly("CONTENT_COMMENTED");
    }

    // ===== AC5 · 解除与历史 =====

    /** 解除拉黑**不补发**：抑制期间的通知永久丢弃。 */
    @Test
    void ac5_unblockDoesNotBackfillSuppressedNotifications() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        hideService.block(a.getId(), b.getId());
        like(b.getId(), postId);
        comment(b.getId(), postId, "抑制期间的评论");
        assertThat(notificationTypesOf(a.getId())).isEmpty();

        hideService.unblock(a.getId(), b.getId());

        assertThat(notificationTypesOf(a.getId())).isEmpty(); // 不补发
        assertThat(unreadCountOf(a.getId())).isZero();
        // 解除之后的新互动照常通知。
        comment(b.getId(), postId, "解除之后的评论");
        assertThat(notificationTypesOf(a.getId())).containsExactly("CONTENT_COMMENTED");
    }

    /** 拉黑**之前**已产生的历史通知不回溯清理（A-A30：拉黑不回溯清理已产生数据）。 */
    @Test
    void ac5_historyBeforeBlockIsNotRetroactivelyCleaned() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        like(b.getId(), postId);
        assertThat(notificationTypesOf(a.getId())).containsExactly("CONTENT_LIKED");

        hideService.block(a.getId(), b.getId());

        assertThat(notificationTypesOf(a.getId())).containsExactly("CONTENT_LIKED"); // 还在
        assertThat(unreadCountOf(a.getId())).isEqualTo(1);
    }

    // ===== AC6 · 对被拉黑方无影响 =====

    /** B 的点赞/评论照常成功（2xx / 201），他无从知晓对方是否收到通知。 */
    @Test
    void ac6_blockedUserOperationsStillSucceed() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        hideService.block(a.getId(), b.getId());

        like(b.getId(), postId);                    // 断言在 like() 里：2xx
        comment(b.getId(), postId, "照常成功");      // 断言在 comment() 里：201
    }
}
