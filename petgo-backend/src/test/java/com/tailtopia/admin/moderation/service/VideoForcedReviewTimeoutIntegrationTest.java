package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.node.ObjectNode;

/**
 * L1（需 Postgres + Redis）：专题线 video Story 3.2 —— 人工审核总开关关闭时，名单外视频帖
 * （{@code review_reason=VIDEO_FORCED}）超过 3 天照常超时丢弃；其他挂起帖不动；审核通过 → desired=PUBLIC。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class VideoForcedReviewTimeoutIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private AliyunOssClient oss;
    @Autowired
    private VipLevelService vipLevels;
    @Autowired
    private ManualReviewService reviewService;
    @Autowired
    private AdminSettingsService settings;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Mockito.doReturn(false).when(oss).hasCredentials();
        Mockito.doReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 1000L)))
                .when(oss).headObject(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doReturn(Optional.of(true))
                .when(oss).isObjectPrivate(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        settings.setManualReviewEnabled(false, adminActorId());
    }

    /** 名单外、会员等级 10 的作者发视频帖 → UNDER_REVIEW + VIDEO_FORCED + 入队（1.3）。 */
    private long publishForced(User u, String suffix) throws Exception {
        ObjectNode b = json.createObjectNode();
        b.put("type", "DAILY");
        b.put("text", "video");
        b.put("mediaType", "VIDEO");
        b.putArray("imageUrls").add("https://cdn.petgo.test/public/" + u.getId() + "/cover" + suffix + ".jpg");
        ObjectNode v = b.putObject("video");
        v.put("objectKey", "video/" + u.getId() + "/abcdefghijklmnopqrstu" + suffix + ".mp4");
        v.put("durationMs", 5000);
        v.put("width", 720);
        v.put("height", 1280);
        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM content_posts WHERE author_id = ?", Long.class, u.getId());
    }

    private User author() {
        User u = newUser();
        vipLevels.setLevel(u.getId(), 10);
        return u;
    }

    private void ageQueueItem(long postId) {
        jdbc.update("UPDATE manual_review_queue SET submitted_at = now() - interval '4 days' "
                + "WHERE content_id = ? AND content_type = 'CONTENT_POST'", postId);
    }

    private String queueStatus(long postId) {
        return jdbc.queryForObject("SELECT status FROM manual_review_queue WHERE content_id = ? AND content_type = 'CONTENT_POST'",
                String.class, postId);
    }

    @Test
    void videoForcedTimesOutEvenWhenGateDisabledWhileOtherPendingPostStays() throws Exception {
        User a = author();
        long forced = publishForced(a, "a");
        assertThat(jdbc.queryForObject("SELECT review_reason FROM content_posts WHERE id = ?", String.class, forced))
                .isEqualTo("VIDEO_FORCED");
        User b = author();
        long other = publishForced(b, "b");
        // 模拟「其他原因挂起」的帖子：同样在队列里、同样超时，但不是 VIDEO_FORCED。
        jdbc.update("UPDATE content_posts SET review_reason = 'RISK_HIGH' WHERE id = ?", other);
        ageQueueItem(forced);
        ageQueueItem(other);

        reviewService.scanTimeouts(Instant.now());

        assertThat(queueStatus(forced)).isEqualTo("TIMED_OUT");
        assertThat(jdbc.queryForObject("SELECT media_acl_desired FROM content_posts WHERE id = ?", String.class, forced))
                .isEqualTo("PRIVATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'CONTENT_REVIEW_TIMED_OUT'", Integer.class, a.getId())).isEqualTo(1);
        assertThat(queueStatus(other)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM content_posts WHERE id = ?", String.class, other))
                .isEqualTo("UNDER_REVIEW");
    }

    @Test
    void approvingVideoForcedPostMakesItPublic() throws Exception {
        User a = author();
        long forced = publishForced(a, "c");
        long itemId = jdbc.queryForObject("SELECT id FROM manual_review_queue WHERE content_id = ? AND content_type = 'CONTENT_POST'",
                Long.class, forced);

        assertThat(reviewService.approve(itemId, adminActorId())).isEqualTo(forced);

        assertThat(jdbc.queryForObject("SELECT media_acl_desired FROM content_posts WHERE id = ?", String.class, forced))
                .isEqualTo("PUBLIC");
    }

    @Test
    void deletedVideoForcedPostIsNotTimedOutAgain() throws Exception {
        // code review：作者自删不关队列项；开关关着时超时扫描不应再对已删帖发通知 / 记违规。
        User a = author();
        long forced = publishForced(a, "d");
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", forced);
        ageQueueItem(forced);

        reviewService.scanTimeouts(Instant.now());

        assertThat(queueStatus(forced)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'CONTENT_REVIEW_TIMED_OUT'", Integer.class, a.getId())).isZero();
    }

    @Test
    void rejectingVideoForcedPostKeepsItPrivate() throws Exception {
        User a = author();
        long forced = publishForced(a, "e");
        long itemId = jdbc.queryForObject("SELECT id FROM manual_review_queue WHERE content_id = ? AND content_type = 'CONTENT_POST'",
                Long.class, forced);

        assertThat(reviewService.reject(itemId, adminActorId(), null)).isEqualTo(forced);

        assertThat(jdbc.queryForObject("SELECT media_acl_desired FROM content_posts WHERE id = ?", String.class, forced))
                .isEqualTo("PRIVATE");
    }
}
