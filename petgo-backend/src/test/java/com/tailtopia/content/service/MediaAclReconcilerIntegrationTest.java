package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
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
 * L1（需 Postgres + Redis）：专题线 video Story 1.4 —— 发布后即时对账、失败由定时任务兜底、条件回写、
 * 实体整行 save 不覆盖 applied。
 *
 * <p>OSS 用 {@link MockitoSpyBean}：桩 {@code hasCredentials / headObject / setObjectAcl}，封面 URL 反解走真实逻辑。
 * 定时任务在测试基类里已关（{@code media.video.acl-reconcile-cron=-}），这里直接调 {@code run()}。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class MediaAclReconcilerIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private AliyunOssClient oss;

    @Autowired
    private MediaAclReconciler reconciler;
    @Autowired
    private ContentService contentService;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private VipLevelService vipLevels;
    @Autowired
    private VideoTrustedPublisherAdminService trusted;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubOss() {
        Mockito.doReturn(true).when(oss).hasCredentials();
        Mockito.doReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 1000L)))
                .when(oss).headObject(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        // code review（1.3）：封面须为私有对象；测试封面视为 VIDEO_COVER 签出的私有图。
        Mockito.doReturn(Optional.of(true))
                .when(oss).isObjectPrivate(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.doReturn(true).when(oss)
                .setObjectAcl(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
    }

    private User videoUser(boolean inWhitelist) {
        User u = newUser();
        vipLevels.setLevel(u.getId(), 10);
        if (inWhitelist) {
            trusted.add(u.getId(), adminActorId());
        }
        return u;
    }

    private long publish(User u) throws Exception {
        ObjectNode b = json.createObjectNode();
        b.put("type", "DAILY");
        b.put("text", "halo video");
        b.put("mediaType", "VIDEO");
        b.putArray("imageUrls").add("https://cdn.petgo.test/public/" + u.getId() + "/cover.jpg");
        ObjectNode v = b.putObject("video");
        v.put("objectKey", "video/" + u.getId() + "/abcdefghijklmnopqrstuv.mp4");
        v.put("durationMs", 5000);
        v.put("width", 720);
        v.put("height", 1280);
        mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(b)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT max(id) FROM content_posts WHERE author_id = ?", Long.class, u.getId());
    }

    private String applied(long id) {
        return jdbc.queryForObject("SELECT media_acl_applied FROM content_posts WHERE id = ?", String.class, id);
    }

    @Test
    void trustedPublishIsMadePublicImmediately() throws Exception {
        User u = videoUser(true);
        long id = publish(u);

        assertThat(applied(id)).isEqualTo("PUBLIC");
        Mockito.verify(oss).setObjectAcl("petgo-public-test", "video/" + u.getId() + "/abcdefghijklmnopqrstuv.mp4", true);
        Mockito.verify(oss).setObjectAcl("petgo-public-test", "public/" + u.getId() + "/cover.jpg", true);
    }

    @Test
    void inlineFailureStillReturns201AndScheduledRunCatchesUp() throws Exception {
        Mockito.doThrow(new IllegalStateException("oss down")).when(oss)
                .setObjectAcl(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
        User u = videoUser(true);
        long id = publish(u); // 内部断言了 201

        assertThat(applied(id)).isEqualTo("PRIVATE");

        Mockito.doReturn(true).when(oss)
                .setObjectAcl(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
        // 每轮只处理按 id 升序的前 100 条；共享测试库里可能积着别的测试留下的待对账行，多跑几轮直到轮到本条。
        for (int i = 0; i < 50 && !"PUBLIC".equals(applied(id)); i++) {
            reconciler.run();
        }
        assertThat(applied(id)).isEqualTo("PUBLIC");
    }

    @Test
    void untrustedPublishTouchesNoAcl() throws Exception {
        User u = videoUser(false);
        long id = publish(u);

        assertThat(applied(id)).isEqualTo("PRIVATE");
        Mockito.verify(oss, Mockito.never())
                .setObjectAcl(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
    }

    @Test
    void conditionalWritebackRefusesWhenDesiredChangedConcurrently() throws Exception {
        Mockito.doThrow(new IllegalStateException("oss down")).when(oss)
                .setObjectAcl(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
        User u = videoUser(true);
        long id = publish(u);
        jdbc.update("UPDATE content_posts SET media_acl_desired = 'PRIVATE' WHERE id = ?", id);

        assertThat(posts.markMediaAclApplied(id, "PUBLIC")).isZero();
        assertThat(posts.markMediaAclApplied(id, "PRIVATE")).isEqualTo(1);
    }

    @Test
    void entitySaveNeverOverwritesAppliedWrittenByReconciler() throws Exception {
        User u = videoUser(true);
        long id = publish(u);
        assertThat(applied(id)).isEqualTo("PUBLIC");

        // 预处置 / 解除都会整行 save 实体（实体里的 applied 还是加载时的值）。
        contentService.applyReportHoldIfPublished(id);
        assertThat(jdbc.queryForObject("SELECT media_acl_desired FROM content_posts WHERE id = ?", String.class, id))
                .isEqualTo("PRIVATE");
        assertThat(applied(id)).isEqualTo("PUBLIC");
        contentService.releaseReportHold(id);
        assertThat(applied(id)).isEqualTo("PUBLIC");
    }

    /** code review：回写落空且 desired 已改走 → 记漂移，applied=尝试值，findMediaAclPending 能选回这条。 */
    @Test
    void driftMarkMakesPostPendingAgain() throws Exception {
        User u = videoUser(true);
        Mockito.doReturn(false).when(oss).hasCredentials(); // 发布时不即时对账，保持 applied=PRIVATE
        long id = publish(u);
        Mockito.doReturn(true).when(oss).hasCredentials();
        jdbc.update("UPDATE content_posts SET media_acl_desired='PRIVATE' WHERE id=?", id);

        assertThat(posts.markMediaAclDrifted(id, "PUBLIC")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT media_acl_applied FROM content_posts WHERE id=?", String.class, id))
                .isEqualTo("PUBLIC");
        assertThat(posts.findMediaAclPending(org.springframework.data.domain.PageRequest.of(0, 10_000)))
                .extracting(com.tailtopia.content.domain.ContentPost::getId).contains(id);
        // desired 与尝试值相同时不动（原「下一轮重试」路径不变）。
        assertThat(posts.markMediaAclDrifted(id, "PRIVATE")).isZero();
    }
}
