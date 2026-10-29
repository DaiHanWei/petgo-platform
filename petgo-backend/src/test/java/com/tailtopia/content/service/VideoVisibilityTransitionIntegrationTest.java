package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.util.Map;
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
 * L1（需 Postgres + Redis）：专题线 video Story 3.1 —— AD-6 入口表逐个触发，视频帖的 desired 都等于
 * {@code desiredOf(post)} 并刷新 desired_at；注销批量 SQL 显式写 PRIVATE；单纯停用账号不改帖子。
 *
 * <p>OSS 用 {@link MockitoSpyBean}：{@code hasCredentials=false} 让发布时不即时对账（只验 desired，不验 ACL）。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class VideoVisibilityTransitionIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private AliyunOssClient oss;
    @Autowired
    private ContentService contentService;
    @Autowired
    private VipLevelService vipLevels;
    @Autowired
    private VideoTrustedPublisherAdminService trusted;
    @Autowired
    private AdminUserService adminUsers;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubOss() {
        Mockito.doReturn(false).when(oss).hasCredentials();
        Mockito.doReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 1000L)))
                .when(oss).headObject(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doReturn(Optional.of(true))
                .when(oss).isObjectPrivate(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    private User author(boolean inWhitelist) {
        User u = newUser();
        vipLevels.setLevel(u.getId(), 10);
        if (inWhitelist) {
            trusted.add(u.getId(), adminActorId());
        }
        return u;
    }

    private long publish(User u, String suffix) throws Exception {
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

    private Map<String, Object> acl(long id) {
        return jdbc.queryForMap("SELECT status, deleted_at, media_acl_desired, media_acl_desired_at "
                + "FROM content_posts WHERE id = ?", id);
    }

    private String desired(long id) {
        return (String) acl(id).get("media_acl_desired");
    }

    /** 把 desired_at 拨到过去，便于断言「这次迁移刷新了它」。 */
    private void ageDesiredAt(long id) {
        jdbc.update("UPDATE content_posts SET media_acl_desired_at = now() - interval '1 hour' WHERE id = ?", id);
    }

    private boolean desiredAtRefreshed(long id) {
        Timestamp t = (Timestamp) acl(id).get("media_acl_desired_at");
        return t.toInstant().isAfter(java.time.Instant.now().minusSeconds(600));
    }

    @Test
    void authorDeleteAndAdminTakedownAndRestoreFollowDesiredOf() throws Exception {
        User u = author(true);
        long id = publish(u, "a");
        assertThat(desired(id)).isEqualTo("PUBLIC");

        ageDesiredAt(id);
        contentService.softDelete(id, DeleteReason.ADMIN_TAKEDOWN); // 运营下架
        assertThat(desired(id)).isEqualTo("PRIVATE");
        assertThat(desiredAtRefreshed(id)).isTrue();

        ageDesiredAt(id);
        contentService.restore(id); // 恢复
        assertThat(desired(id)).isEqualTo("PUBLIC");
        assertThat(desiredAtRefreshed(id)).isTrue();

        contentService.deleteByAuthor(id, u.getId()); // 作者删除
        assertThat(desired(id)).isEqualTo("PRIVATE");
    }

    @Test
    void reportHoldAndReleaseFollowDesiredOf() throws Exception {
        User u = author(true);
        long id = publish(u, "b");
        contentService.applyReportHoldIfPublished(id); // 举报预处置
        assertThat(desired(id)).isEqualTo("PRIVATE");
        contentService.releaseReportHold(id); // 解除
        assertThat(desired(id)).isEqualTo("PUBLIC");
    }

    @Test
    void forcedReviewApproveAndDiscardFollowDesiredOf() throws Exception {
        User u = author(false); // 名单外 → UNDER_REVIEW、PRIVATE
        long approved = publish(u, "c");
        assertThat(desired(approved)).isEqualTo("PRIVATE");
        contentService.approveReview(approved);
        assertThat(desired(approved)).isEqualTo("PUBLIC");

        User u2 = author(false);
        long discarded = publish(u2, "d");
        contentService.discardReview(discarded);
        assertThat(desired(discarded)).isEqualTo("PRIVATE");
        // 审核中被丢弃后又被恢复：仍是 UNDER_REVIEW → 仍 PRIVATE（按状态推导）。
        contentService.restore(discarded);
        assertThat(acl(discarded).get("status")).isEqualTo("UNDER_REVIEW");
        assertThat(desired(discarded)).isEqualTo("PRIVATE");
    }

    @Test
    void deleteUserForViolationTakesDownVideo() throws Exception {
        User u = author(true);
        long id = publish(u, "e");
        contentService.takedownAllByAuthor(u.getId()); // 删除用户（违规）的内容侧
        assertThat(desired(id)).isEqualTo("PRIVATE");
    }

    @Test
    void accountDeletionBatchSqlWritesPrivateExplicitly() throws Exception {
        User u = author(true);
        long video = publish(u, "f");
        ObjectNode img = json.createObjectNode();
        img.put("type", "DAILY");
        img.put("text", "foto");
        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(img)))
                .andExpect(status().isCreated());
        long image = jdbc.queryForObject("SELECT max(id) FROM content_posts WHERE author_id = ?", Long.class, u.getId());
        ageDesiredAt(video);

        contentService.deactivateAuthorContent(u.getId()); // 注销批量 JPQL UPDATE

        assertThat(acl(video).get("status")).isEqualTo("AUTHOR_DEACTIVATED");
        assertThat(desired(video)).isEqualTo("PRIVATE");
        assertThat(desiredAtRefreshed(video)).isTrue();
        assertThat(acl(image).get("media_acl_desired")).isNull();
        assertThat(acl(image).get("media_acl_desired_at")).isNull();
    }

    @Test
    void plainAccountDeactivationLeavesPostsVisible() throws Exception {
        User u = author(true);
        long id = publish(u, "g");
        adminUsers.deactivate(u.getId(), "test", adminActorId()); // 单纯停用（V-39）
        assertThat(acl(id).get("status")).isEqualTo("PUBLISHED");
        assertThat(desired(id)).isEqualTo("PUBLIC");
    }

    @Test
    void takedownEndpointReconcilesImmediately() throws Exception {
        User u = author(true);
        long id = publish(u, "h");
        jdbc.update("UPDATE content_posts SET media_acl_applied = 'PUBLIC' WHERE id = ?", id); // 已公开
        Mockito.doReturn(true).when(oss).hasCredentials();
        Mockito.doReturn(true).when(oss)
                .setObjectAcl(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
        // 作者删除接口：事务提交后 Controller 即时对账 → 视频改私有。
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/content-posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isNoContent());
        Mockito.verify(oss).setObjectAcl("petgo-public-test",
                "video/" + u.getId() + "/abcdefghijklmnopqrstuh.mp4", false);
        assertThat(jdbc.queryForObject("SELECT media_acl_applied FROM content_posts WHERE id = ?", String.class, id))
                .isEqualTo("PRIVATE");
    }
}
