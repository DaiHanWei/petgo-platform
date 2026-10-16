package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.node.ObjectNode;
import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.admin.warmreply.dto.DistributionFilter;
import com.tailtopia.admin.warmreply.repository.PostDistributionQuery;
import com.tailtopia.admin.warmreply.service.VirtualCommentService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.content.autocomment.AutoCommentLogRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.ImageSizeBackfillService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * L1（需 Postgres + Redis）：专题线 video Story 1.3 —— 视频帖发布（迁移 / 名单内外路由 / 校验表 / HEAD 在事务外 /
 * 不触发 AI 自动评论与暖评）。
 *
 * <p>OSS 用 {@link MockitoSpyBean}：封面 URL 反解走真实逻辑，只桩 {@code headObject}（无真实凭证）。
 * 等级、免审名单、审核队列、事件全走真实链路。
 */
@TestPropertySource(properties = {
        "media.oss.public-bucket=petgo-public-test",
        "media.oss.cdn-base-url=https://cdn.petgo.test"
})
class VideoPostPublishIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private AliyunOssClient oss;
    @MockitoSpyBean
    private ImageSizeBackfillService sizeBackfill;
    @MockitoSpyBean
    private ContentService contentService;

    @Autowired
    private VipLevelService vipLevels;
    @Autowired
    private VideoTrustedPublisherAdminService trusted;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AutoCommentLogRepository autoCommentLogs;
    @Autowired
    private PostDistributionQuery distribution;
    @Autowired
    private VirtualCommentService virtualComments;

    /** HEAD 被调用时是否处于事务中（AC6）。 */
    private final List<Boolean> headInTx = new java.util.ArrayList<>();

    @BeforeEach
    void stubHead() {
        headInTx.clear();
        Mockito.doAnswer(inv -> {
            headInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            String key = inv.getArgument(1);
            if (key.contains("missing")) {
                return Optional.empty();
            }
            if (key.contains("mov")) {
                return Optional.of(new AliyunOssClient.ObjectHead("video/quicktime", 10L));
            }
            if (key.contains("huge")) {
                return Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 104_857_601L));
            }
            return Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 25_000_000L));
        }).when(oss).headObject(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    private User videoUser(boolean inWhitelist) {
        User u = newUser();
        vipLevels.setLevel(u.getId(), 10);
        if (inWhitelist) {
            trusted.add(u.getId(), adminActorId());
        }
        return u;
    }

    private ObjectNode body(long uid) {
        ObjectNode b = json.createObjectNode();
        b.put("type", "DAILY");
        b.put("text", "halo video");
        b.put("mediaType", "VIDEO");
        b.putArray("imageUrls").add("https://cdn.petgo.test/public/" + uid + "/cover.jpg");
        ObjectNode v = b.putObject("video");
        v.put("objectKey", "video/" + uid + "/abcdefghijklmnopqrstuv.mp4");
        v.put("durationMs", 75_000);
        v.put("width", 720);
        v.put("height", 1280);
        return b;
    }

    private ResultActions publish(long uid, ObjectNode b) throws Exception {
        return mvc.perform(post("/api/v1/content-posts")
                .header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(b)));
    }

    private long postCount(long uid) {
        return jdbc.queryForObject("SELECT count(*) FROM content_posts WHERE author_id = ?", Long.class, uid);
    }

    private Map<String, Object> lastPost(long uid) {
        return jdbc.queryForMap("SELECT * FROM content_posts WHERE author_id = ? ORDER BY id DESC LIMIT 1", uid);
    }

    // ---------- AC1 迁移 ----------

    @Test
    void migrationAddsColumnsAndReviewReasonAcceptsVideoForced() {
        Integer cols = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_name = 'content_posts'
                AND column_name IN ('media_type','video_object_key','video_duration_ms','video_width','video_height',
                    'video_size_bytes','media_acl_desired','media_acl_applied','media_acl_desired_at')""", Integer.class);
        assertThat(cols).isEqualTo(9);
        String def = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_content_posts_review_reason'""",
                String.class);
        assertThat(def).contains("RISK_HIGH", "DEGRADED_FAILCLOSED", "REPORT_P0", "VIDEO_FORCED");
        Integer idx = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_content_posts_media_acl_pending'", Integer.class);
        assertThat(idx).isEqualTo(1);
        // 存量（及新建的图文帖）一律 IMAGE、ACL 列为空。
        Integer badImage = jdbc.queryForObject("""
                SELECT count(*) FROM content_posts WHERE media_type = 'IMAGE'
                AND (media_acl_desired IS NOT NULL OR media_acl_applied IS NOT NULL)""", Integer.class);
        assertThat(badImage).isZero();
    }

    // ---------- AC3 名单内 ----------

    @Test
    void trustedPublisherVideoGoesLiveWithoutModeration() throws Exception {
        User u = videoUser(true);
        User mentioned = newUser();
        // @ 只能 @ 到打过交道的人（AD-10 候选集）：直接落一条候选关系。
        jdbc.update("""
                INSERT INTO mention_candidates (owner_id, candidate_id, last_interacted_at, created_at, updated_at)
                VALUES (?, ?, now(), now(), now())""", u.getId(), mentioned.getId());
        ObjectNode b = body(u.getId());
        b.put("visibility", "PRIVATE"); // 强制改公开
        b.putArray("imageSizes").addObject().put("w", 1).put("h", 1); // 客户端尺寸不采信
        b.putArray("mentionedUserIds").add(mentioned.getId());

        publish(u.getId(), b).andExpect(status().isCreated());

        Map<String, Object> row = lastPost(u.getId());
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("media_type")).isEqualTo("VIDEO");
        assertThat(row.get("visibility")).isEqualTo("PUBLIC");
        assertThat(row.get("video_object_key")).isEqualTo("video/" + u.getId() + "/abcdefghijklmnopqrstuv.mp4");
        assertThat(row.get("video_size_bytes")).isEqualTo(25_000_000L);
        assertThat(row.get("media_acl_desired")).isEqualTo("PUBLIC");
        assertThat(row.get("media_acl_applied")).isEqualTo("PRIVATE");
        assertThat(row.get("media_acl_desired_at")).isNotNull();
        assertThat(row.get("image_crop_offsets")).isNull();
        assertThat(String.valueOf(row.get("image_sizes"))).contains("720").contains("1280").doesNotContain("\"w\": 1,");
        assertThat(String.valueOf(row.get("mentioned_user_ids"))).contains(String.valueOf(mentioned.getId()));
        Mockito.verify(contentService, Mockito.never()).publishTrusted(
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(sizeBackfill, Mockito.never()).backfill(ArgumentMatchers.anyLong());
        // AC6：HEAD 发生在事务外。
        assertThat(headInTx).containsExactly(false);
    }

    // ---------- AC4 名单外 ----------

    @Test
    void untrustedVideoIsForcedIntoReviewWithGateOff() throws Exception {
        User u = videoUser(false);

        publish(u.getId(), body(u.getId())).andExpect(status().isCreated());

        Map<String, Object> row = lastPost(u.getId());
        assertThat(row.get("status")).isEqualTo("UNDER_REVIEW");
        assertThat(row.get("review_reason")).isEqualTo("VIDEO_FORCED");
        assertThat(row.get("media_acl_desired")).isEqualTo("PRIVATE");
        assertThat(row.get("media_acl_applied")).isEqualTo("PRIVATE");
        Integer queued = jdbc.queryForObject("""
                SELECT count(*) FROM manual_review_queue
                WHERE content_id = ? AND content_type = 'CONTENT_POST' AND status = 'PENDING'""",
                Integer.class, ((Number) row.get("id")).longValue());
        assertThat(queued).isEqualTo(1);
    }

    @Test
    void untrustedVideoTextHardBlockIs422AndNotPersisted() throws Exception {
        User u = videoUser(false);
        ObjectNode b = body(u.getId());
        b.put("text", "ayo main judi online");
        publish(u.getId(), b)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type", endsWith("content-text-blocked")));
        assertThat(postCount(u.getId())).isZero();
    }

    // ---------- AC5 校验表 ----------

    /** 每次用一个新的名单内等级 10 用户（发帖限流 20 次/分钟/人，同一人连发会撞 429）。 */
    private void rejects(java.util.function.Function<Long, ObjectNode> make, int status, String type)
            throws Exception {
        User u = videoUser(true);
        publish(u.getId(), make.apply(u.getId()))
                .andExpect(status().is(status)).andExpect(jsonPath("$.type", endsWith(type)));
        assertThat(postCount(u.getId())).as(type).isZero();
    }

    private java.util.function.Function<Long, ObjectNode> with(java.util.function.BiConsumer<Long, ObjectNode> edit) {
        return id -> {
            ObjectNode b = body(id);
            edit.accept(id, b);
            return b;
        };
    }

    @Test
    void everyValidationFailureMapsToItsErrorAndCreatesNothing() throws Exception {
        User low = newUser();
        publish(low.getId(), body(low.getId()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type", endsWith("video-permission-denied")));
        assertThat(postCount(low.getId())).isZero();

        rejects(with((id, b) -> b.putArray("imageUrls").add("https://cdn.petgo.test/public/" + id + "/a.jpg")
                .add("https://cdn.petgo.test/public/" + id + "/b.jpg")), 422, "video-mixed-media");
        rejects(with((id, b) -> b.remove("video")), 422, "video-mixed-media");
        rejects(with((id, b) -> b.remove("mediaType")), 422, "video-mixed-media");

        rejects(with((id, b) -> b.put("type", "GROWTH_MOMENT")), 422, "validation");
        rejects(with((id, b) -> b.put("petId", 1)), 422, "validation");
        rejects(with((id, b) -> b.put("eventDate", "2026-01-01")), 422, "validation");
        rejects(with((id, b) -> b.put("placeCheckinToken", "tok")), 422, "validation");
        rejects(with((id, b) -> b.putArray("imageCropOffsets").add(0.5)), 422, "validation");

        for (int[] bad : new int[][] {{2999, 720, 1280}, {90_001, 720, 1280}, {5000, 0, 1280}, {5000, 721, 1280}}) {
            rejects(with((id, b) -> ((ObjectNode) b.get("video"))
                    .put("durationMs", bad[0]).put("width", bad[1]).put("height", bad[2])), 422, "video-object-invalid");
        }
        for (java.util.function.Function<Long, String> key : List.<java.util.function.Function<Long, String>>of(
                id -> "video/" + (id + 1) + "/abcdefghijklmnopqrstuv.mp4", id -> "public/" + id + "/x.mp4",
                id -> "video/" + id + "/x.mov", id -> "video/" + id + "/../x.mp4")) {
            rejects(with((id, b) -> ((ObjectNode) b.get("video")).put("objectKey", key.apply(id))),
                    422, "video-object-invalid");
        }
        for (java.util.function.Function<Long, String> cover : List.<java.util.function.Function<Long, String>>of(
                id -> "https://evil.example/public/" + id + "/c.jpg",
                id -> "https://cdn.petgo.test/public/" + (id + 1) + "/c.jpg")) {
            rejects(with((id, b) -> b.putArray("imageUrls").add(cover.apply(id))), 422, "video-object-invalid");
        }
        for (String head : List.of("missing", "mov", "huge")) {
            rejects(with((id, b) -> ((ObjectNode) b.get("video"))
                    .put("objectKey", "video/" + id + "/" + head + "abcdefghijklmn.mp4")), 422, "video-object-invalid");
        }
    }

    @Test
    void levelDroppedBackTo1CannotPublishAgain() throws Exception {
        User u = videoUser(true);
        publish(u.getId(), body(u.getId())).andExpect(status().isCreated());
        vipLevels.setLevel(u.getId(), 1);
        publish(u.getId(), body(u.getId())).andExpect(status().isForbidden());
        assertThat(postCount(u.getId())).isEqualTo(1);
    }

    // ---------- AC8 不触发 AI 自动评论 / 暖评 ----------

    @Test
    void videoPostsAreExcludedFromAutoCommentAndWarmReply() throws Exception {
        User u = videoUser(true);
        publish(u.getId(), body(u.getId())).andExpect(status().isCreated());
        long videoId = ((Number) lastPost(u.getId()).get("id")).longValue();

        // 对照组：同一作者的图文帖会被选中，证明查询条件本身能命中这个作者。
        ObjectNode img = json.createObjectNode();
        img.put("type", "DAILY");
        img.put("text", "halo foto");
        publish(u.getId(), img).andExpect(status().isCreated());
        long imageId = ((Number) lastPost(u.getId()).get("id")).longValue();

        Instant now = Instant.now();
        List<Long> candidates = autoCommentLogs.findCandidatePostIds(
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.MINUTES), 3, 100_000);
        assertThat(candidates).contains(imageId).doesNotContain(videoId);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        Set<Long> listed = new HashSet<>();
        for (int page = 0; page < 500; page++) {
            var rows = distribution.list(DistributionFilter.of("all", null, today.minusDays(1), today.plusDays(1),
                    null, "all", false, page));
            if (rows.isEmpty()) {
                break;
            }
            rows.forEach(r -> listed.add(r.postId()));
        }
        assertThat(listed).contains(imageId).doesNotContain(videoId);

        User virtual = users.save(User.newVirtual("virtual:" + java.util.UUID.randomUUID(), "暖评号", null, adminActorId()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        virtualComments.postAsVirtual(videoId, virtual.getId(), "halo", null, adminActorId()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.virtualComment.videoPost"));
    }
}
