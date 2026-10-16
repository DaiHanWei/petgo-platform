package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * L1（需 Postgres + Redis）：专题线 video Story 3.3 —— 后台内容列表 / 内容抽屉 / 审核详情识别并预览视频帖：
 * 封面与视频一律签名（审核中、已下架也能看），图文帖地址原样。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class AdminVideoPreviewIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private SignedUrlService signer;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ManualReviewItemRepository queue;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private com.tailtopia.content.repository.CommentRepository comments;
    @Autowired
    private com.tailtopia.content.service.ContentPinService pinService;
    @Autowired
    private AdminUserDetailsService userDetailsService;

    @BeforeEach
    void stubSigner() {
        Mockito.doAnswer(inv -> "https://signed.test/" + inv.getArgument(1) + "?Signature=x")
                .when(signer).sign(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(Duration.class));
    }

    private AdminUserDetails ops() {
        long seq = SEQ.incrementAndGet();
        String email = "video-preview-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "视频预览 " + seq, AdminRole.CUSTOM,
                List.of(AdminPermissions.CONTENT_VIEW, AdminPermissions.CONTENT_MANUAL_REVIEW,
                        AdminPermissions.CONTENT_PROACTIVE_TAKEDOWN, AdminPermissions.CONTENT_PIN_VIEW), 970000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    private long videoPost(long authorId, boolean underReview, boolean deleted) {
        String cover = "https://cdn.petgo.test/public/" + authorId + "/cover.jpg";
        ContentPost p = underReview
                ? ContentPost.pendingReview(authorId, ContentType.DAILY, null, "v", List.of(cover), null, null,
                        ContentPost.REVIEW_REASON_VIDEO_FORCED)
                : ContentPost.publish(authorId, ContentType.DAILY, null, "v", List.of(cover));
        p.attachVideo("video/" + authorId + "/abcdefghijklmnopqrstu" + SEQ.incrementAndGet() + ".mp4",
                48_000, 720, 1280, 10L);
        if (deleted) {
            p.softDelete();
        }
        return posts.save(p).getId();
    }

    @Test
    void contentListShowsSignedCoverAndVideoBadgeWhileImagePostUnchanged() throws Exception {
        long author = newUser().getId();
        long video = videoPost(author, false, true);
        String imageUrl = "https://cdn.petgo.test/public/" + author + "/img.jpg";
        long image = posts.save(ContentPost.publish(author, ContentType.DAILY, null, "i", List.of(imageUrl))).getId();

        String html = mvc.perform(get("/admin/content").param("authorId", String.valueOf(author))
                        .with(user(ops())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String videoRow = html.substring(html.indexOf("id=\"content-row-" + video + "\""));
        videoRow = videoRow.substring(0, videoRow.indexOf("</tr>"));
        assertThat(videoRow).contains("data-video-badge")
                .contains("https://signed.test/public/" + author + "/cover.jpg?Signature=x");
        String imageRow = html.substring(html.indexOf("id=\"content-row-" + image + "\""));
        imageRow = imageRow.substring(0, imageRow.indexOf("</tr>"));
        assertThat(imageRow).contains(imageUrl).doesNotContain("data-video-badge").doesNotContain("Signature");
    }

    @Test
    void drawerPlaysSignedVideoForTakenDownPost() throws Exception {
        long author = newUser().getId();
        long video = videoPost(author, false, true);

        String html = mvc.perform(get("/admin/content/" + video + "/drawer").with(user(ops()))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<video").contains("src=\"https://signed.test/video/" + author + "/")
                .contains("poster=\"https://signed.test/public/" + author + "/cover.jpg?Signature=x\"");
    }

    @Test
    void reviewDetailPlaysSignedVideoForUnderReviewPost() throws Exception {
        long author = newUser().getId();
        long video = videoPost(author, true, false);
        long itemId = queue.save(ManualReviewItem.pending(video, Instant.now())).getId();

        String html = mvc.perform(get("/admin/manual-review/" + itemId + "/detail").param("tab", "SUBMISSION")
                        .with(user(ops())).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<video").contains("src=\"https://signed.test/video/" + author + "/")
                .contains("poster=\"https://signed.test/public/" + author + "/cover.jpg?Signature=x\"");
    }

    @Test
    void drawerSignsEvenPublicVideo() throws Exception {
        // AD-8：后台一律签名，不管帖子当前公开与否。
        long author = newUser().getId();
        long video = videoPost(author, false, false);

        String html = mvc.perform(get("/admin/content/" + video + "/drawer").with(user(ops()))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("src=\"https://signed.test/video/" + author + "/");
    }

    @Test
    void commentInspectUsesSignedCoverOfVideoPost() throws Exception {
        // AC3：评论巡查里所属帖子是视频帖 → 缩略图用签名封面。
        long author = newUser().getId();
        long video = videoPost(author, true, false);
        comments.save(com.tailtopia.content.domain.Comment.create(video, null, newUser().getId(), "评论"));

        String html = mvc.perform(get("/admin/comments").param("postId", String.valueOf(video))
                        .with(user(ops())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("https://signed.test/public/" + author + "/cover.jpg?Signature=x")
                .doesNotContain("src=\"https://cdn.petgo.test/public/" + author + "/cover.jpg\"");
    }

    @Test
    void pinDrawerShowsSignedCoverAndVideoBadge() throws Exception {
        // AC4：顶置抽屉显示签名封面与视频标识。
        long author = newUser().getId();
        long video = videoPost(author, false, false);
        Instant now = Instant.now();
        long pinId = pinService.schedule(com.tailtopia.content.domain.ContentPin.ofContent(
                com.tailtopia.content.domain.ContentPin.SLOT_HOME_FEED, video,
                now.plusSeconds(3600), now.plusSeconds(7200))).getId();

        String html = mvc.perform(get("/admin/content-pins/" + pinId + "/drawer").param("lang", "zh_CN")
                        .with(user(ops())).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("https://signed.test/public/" + author + "/cover.jpg?Signature=x")
                .contains("视频");
    }
}
