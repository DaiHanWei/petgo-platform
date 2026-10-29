package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0：三类发布 + 成长日历绑宠物校验 + 幂等重放（AC1/AC2/AC4 逻辑面）。 */
class ContentServiceTest {

    private ContentPostRepository posts;
    private final PostMediaResolver postMedia = Mockito.mock(PostMediaResolver.class);
    private com.tailtopia.content.repository.CommentRepository comments;
    private com.tailtopia.content.repository.ContentLikeRepository likes;
    /** V1.4.0 multi-pet Story 3.1：归属校验改经 PetResolver（原 ProfileService.ownsPet）；默认「不属于本人」→ 404。 */
    private PetResolver petResolver;
    private com.tailtopia.content.repository.ContentPetRepository contentPets;
    private ContentPetQuery contentPetQuery;
    private IdempotencyService idempotency;
    private org.springframework.context.ApplicationEventPublisher events;
    private ManualReviewGate manualReviewGate;
    private VideoTrustedPublisherService videoTrusted;
    private com.tailtopia.auth.service.VipLevelService vipLevels;
    private ContentService service;

    @BeforeEach
    void setUp() {
        posts = Mockito.mock(ContentPostRepository.class);
        comments = Mockito.mock(com.tailtopia.content.repository.CommentRepository.class);
        likes = Mockito.mock(com.tailtopia.content.repository.ContentLikeRepository.class);
        petResolver = Mockito.mock(PetResolver.class);
        // 与原 ownsPet 默认 false 等价：未显式声明归属的宠物一律 404。
        when(petResolver.resolve(anyLong(), any())).thenThrow(AppException.notFound("宠物档案不存在"));
        contentPets = Mockito.mock(com.tailtopia.content.repository.ContentPetRepository.class);
        contentPetQuery = Mockito.mock(ContentPetQuery.class);
        idempotency = Mockito.mock(IdempotencyService.class);
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(posts.save(any(ContentPost.class))).thenAnswer(inv -> {
            ContentPost p = inv.getArgument(0);
            if (p.getId() == null) {
                setId(p, 100L); // 模拟 DB IDENTITY 回填（仅新建）
            }
            return p;
        });
        events = Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        manualReviewGate = Mockito.mock(ManualReviewGate.class);
        // 默认开关关（现网行为）；按需在用例内 stub enabled()=true。
        when(manualReviewGate.enabled()).thenReturn(false);
        // 审核用真实 stub：既有用例文本均良性 → PASS；R2 用例显式触发拦截标记。
        // V1.1.6 Story 3.1：尺寸归一用真实实现（纯函数、无 IO）；兜底测量用 mock ——
        // 它是 @Async 且会发网络请求，单测里不该真跑。
        service = new ContentService(posts, comments, likes, idempotency,
                new ContentModerationService(), events, manualReviewGate,
                new ImageSizeResolver(), Mockito.mock(ImageSizeBackfillService.class),
                // V1.1.6 Story 4.1：顶置联动。本类不验联动，给 mock；联动本身由 L1 覆盖。
                Mockito.mock(ContentPinService.class),
                // V1.3.0 batch-b1 Story 3.2：@ 名单过滤器用真实实现 —— 本类用例都不带 @ 名单，
                // sanitize(null) 在碰任何依赖之前就返回空表，所以三个 mock 依赖都无需 stub。
                new com.tailtopia.mention.service.MentionSanitizer(
                        Mockito.mock(com.tailtopia.auth.service.AccountQueryService.class),
                        Mockito.mock(com.tailtopia.social.read.UserHideRelationReader.class),
                        Mockito.mock(com.tailtopia.mention.repository.MentionCandidateRepository.class)),
                // V1.3.2 Story 1.5：打卡关联只读口（本类按需 stub）。
                placeCheckins,
                // V1.3.4 Story 2.1：露出位置校验用真实实现（纯函数、无 IO）。
                new ImageCropOffsetValidator(),
                // V1.4.0 multi-pet Story 2.1：可写校验（默认 mock 放行；只读用例显式 doThrow）。
                petWriteGuard,
                // V1.4.0 multi-pet Story 3.1：归属 / 关联写入 / 关联只读口。
                petResolver, contentPets, contentPetQuery,
                // 专题线 video Story 1.3：名单 / 等级按需 stub；validateShape 是纯函数，用真实实现
                // （它不碰构造器依赖 —— HEAD 只在事务外的 verifyObjects 里，本类不测）。
                videoTrusted = Mockito.mock(VideoTrustedPublisherService.class),
                vipLevels = Mockito.mock(com.tailtopia.auth.service.VipLevelService.class),
                new VideoPostValidator(null, null, null),
                postMedia);
    }

    private final com.tailtopia.profile.service.PetWriteGuard petWriteGuard =
            Mockito.mock(com.tailtopia.profile.service.PetWriteGuard.class);

    private final com.tailtopia.place.service.PlaceCheckinQueryService placeCheckins =
            Mockito.mock(com.tailtopia.place.service.PlaceCheckinQueryService.class);

    /** 声明 {@code ids} 属于 {@code owner}（建档时刻 = EPOCH + id 秒，即 id 小者先建档）。 */
    private void ownPets(long owner, long... ids) {
        for (long id : ids) {
            ownPetCreatedAt(owner, id, java.time.Instant.EPOCH.plusSeconds(id));
        }
    }

    private void ownPetCreatedAt(long owner, long id, java.time.Instant createdAt) {
        Mockito.doReturn(PetFixtures.pet(owner, id, createdAt)).when(petResolver).resolve(owner, id);
    }

    private static void setId(ContentPost p, long id) {
        try {
            var f = ContentPost.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void dailyPublishIgnoresPetId() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, 99L, "hi", List.of("u1")), null);
        assertThat(resp.type()).isEqualTo(ContentType.DAILY);
        assertThat(resp.petId()).isNull(); // 普通类型不绑宠物
        assertThat(resp.imageUrls()).containsExactly("u1");
    }

    @Test
    void growthMomentRequiresOwnedPet() {
        ownPets(1L, 5L);
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "moment", null), null);
        assertThat(resp.type()).isEqualTo(ContentType.GROWTH_MOMENT);
        assertThat(resp.petId()).isEqualTo(5L);
    }

    /** V1.4.0 multi-pet Story 2.1（AC7 #1）：Diary 关联只读宠物 → 409 pet-read-only，不落库、不发事件。 */
    @Test
    void growthMomentWithReadOnlyPetRejected409() {
        ownPets(1L, 5L);
        Mockito.doThrow(AppException.petReadOnly(5L)).when(petWriteGuard).requireWritable(5L);
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "x", null), null))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getType().toString()).isEqualTo("https://petgo/errors/pet-read-only");
                });
        verify(posts, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** Story 2.1：归属先于可写——非本人宠物被拒（Story 3.1 起按 NFR5 为 404），不走 guard。 */
    @Test
    void unownedPetIsCheckedBeforeWritability() {
        // 7L 未声明归属 → PetResolver 404（setUp 默认）。
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 7L, "x", null), null))
                .isInstanceOf(AppException.class);
        verify(petWriteGuard, never()).requireWritable(Mockito.anyLong());
    }

    @Test
    void growthMomentWithoutPetIdRejected() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, null, "x", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void growthMomentWithUnownedPetRejected() {
        // 7L 未声明归属 → PetResolver 404（setUp 默认）。
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 7L, "x", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void idempotentReplayReturnsExistingWithoutCreating() {
        ContentPost existing = ContentPost.publish(1L, ContentType.DAILY, null, "old", null);
        when(idempotency.findResourceId("KEY1")).thenReturn(Optional.of(42L));
        when(posts.findById(42L)).thenReturn(Optional.of(existing));

        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "new", null), "KEY1");

        assertThat(resp.text()).isEqualTo("old"); // 取回既有，不创建
        verify(posts, never()).save(any());
    }

    @Test
    void storesIdempotencyKeyAfterCreate() {
        service.publish(1L, new ContentPostCreateRequest(ContentType.DAILY, null, "x", null), "KEY2");
        verify(idempotency).store(org.mockito.ArgumentMatchers.eq("KEY2"), anyLong());
    }

    // ===== R2 · AC6 最低内容 =====

    @Test
    void bothEmptyContentRejected() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "  ", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void imageOnlyContentAllowed() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, null, List.of("u1")), null);
        assertThat(resp.imageUrls()).containsExactly("u1");
        assertThat(resp.text()).isNull();
    }

    // ===== R2 · AC5 成长日历事件日期（F9） =====

    @Test
    void growthMomentStoresGivenEventDate() {
        ownPets(1L, 5L);
        LocalDate past = LocalDate.of(2020, 1, 2);
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "m", null, past), null);
        assertThat(resp.eventDate()).isEqualTo(past);
    }

    @Test
    void growthMomentDefaultsEventDateToToday() {
        ownPets(1L, 5L);
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "m", null, null), null);
        // 缺省事件日期按印尼业务时区 WIB 判定（Asia/Jakarta），勿用 UTC（prod 事故 2026-07-20）。
        assertThat(resp.eventDate()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")));
    }

    @Test
    void growthMomentFutureEventDateRejected() {
        ownPets(1L, 5L);
        // WIB 今天 +1 天：无论服务器 UTC 处于一天中哪个时刻，都严格晚于 WIB 今天。
        LocalDate future = LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).plusDays(1);
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "m", null, future), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void nonGrowthIgnoresEventDate() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "hi", null,
                        LocalDate.of(2020, 1, 2)), null);
        assertThat(resp.eventDate()).isNull();
    }

    // ===== R2 · AC8 发布时三方自动审核（F10） =====

    @Test
    void textKeywordBlockedNotSaved() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void imageViolationBlockedNotSaved() {
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ok",
                        List.of("https://cdn.petgo.test/moderation-blocked-1.jpg")), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void cleanContentPasses() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "cuaca cerah hari ini", List.of("u1")), null);
        assertThat(resp.id()).isNotNull();
    }

    // ===== 内容审核 story 2：分级路由 + 开关分支（AC-B2/B3/B4/G1） =====

    /** AC-B3：开关打开 + 风险 ≥0.8（RISKY，stub-high）→ 落 UNDER_REVIEW + RISK_HIGH + 风险分 + 入队 + 不发事件。 */
    @Test
    void riskyContentEnqueuedAsUnderReviewWhenManualReviewEnabled() {
        when(manualReviewGate.enabled()).thenReturn(true);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "stub-high borderline text", null), null);

        verify(posts).save(captor.capture()); // 落库（RISKY → 挂起，不 throw）
        ContentPost pending = captor.getValue();
        assertThat(pending.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(pending.getReviewReason()).isEqualTo("RISK_HIGH");
        assertThat(pending.getModerationRiskScore()).isNotNull(); // 风险分落库
        verify(manualReviewGate).enqueue(anyLong()); // 入队挂起
        verify(events, never()).publishEvent(any( // 不进 Feed / 不触发里程碑
                com.tailtopia.content.event.ContentPublishedEvent.class));
        assertThat(resp.id()).isNotNull();
    }

    /**
     * 🔴 V1.1.6 Story 3.1：<b>审核挂起的帖同样要写图片尺寸</b>。
     *
     * <p>发布入口里有<b>两条落库分支</b>（正常发布 / 审核挂起）。只在正常那条写尺寸的话，
     * 挂起帖审核通过后进 Feed 时<b>永远没有尺寸</b> —— 而且不会有任何东西报错，
     * 只是那批内容的卡片高度一直在跳。
     */
    @Test
    void pendingReviewPostAlsoCarriesImageSizes() {
        when(manualReviewGate.enabled()).thenReturn(true);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        service.publish(1L, new ContentPostCreateRequest(
                ContentType.DAILY, null, "stub-high borderline text",
                java.util.List.of("https://cdn/a.jpg"), null, null,
                java.util.List.of(new com.tailtopia.content.domain.ImageSize(1200, 1600))), null);

        verify(posts).save(captor.capture());
        ContentPost pending = captor.getValue();
        assertThat(pending.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(pending.getImageSizes())
                .as("挂起分支漏写尺寸 —— 审核通过后进 Feed 会永远没有尺寸，且不报任何错")
                .containsExactly(new com.tailtopia.content.domain.ImageSize(1200, 1600));
    }

    // ===== V1.3.2 Story 1.5：打卡后顺手发帖（AD-10）=====

    private static ContentPostCreateRequest withCheckin(String token) {
        return new ContentPostCreateRequest(ContentType.DAILY, null, "Ngopi bareng anabul", null, null, null,
                null, null, token);
    }

    @Test
    void ownCheckinTokenIsLinkedOnPublish() {
        when(placeCheckins.findOwnedCheckinId(1L, "t".repeat(32))).thenReturn(java.util.Optional.of(501L));
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        service.publish(1L, withCheckin("t".repeat(32)), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getPlaceCheckinId()).isEqualTo(501L);
    }

    @Test
    void foreignOrUnknownCheckinTokenIs422AndNothingIsSaved() {
        when(placeCheckins.findOwnedCheckinId(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.publish(1L, withCheckin("someone-elses-token"), null))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class)
                .satisfies(e -> assertThat(((com.tailtopia.shared.error.AppException) e).getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.POST_CHECKIN_INVALID));
        verify(posts, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noCheckinTokenLeavesTheLinkEmptyAndNeverQueries() {
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);
        service.publish(1L, withCheckin(null), null);
        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getPlaceCheckinId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(placeCheckins);
    }

    /** 🔴 挂起分支同样要写打卡关联（与尺寸 / @ 名单同一个坑）。 */
    @Test
    void pendingReviewPostAlsoCarriesCheckinLink() {
        when(manualReviewGate.enabled()).thenReturn(true);
        when(placeCheckins.findOwnedCheckinId(1L, "t".repeat(32))).thenReturn(java.util.Optional.of(502L));
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        service.publish(1L, new ContentPostCreateRequest(ContentType.DAILY, null, "stub-high borderline text",
                null, null, null, null, null, "t".repeat(32)), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(captor.getValue().getPlaceCheckinId()).isEqualTo(502L);
    }

    /** PR#34 finding #2：PRIVATE 日记进人工审核挂起时必须保留可见范围——否则审核通过后以实体默认 PUBLIC 进 Feed。 */
    @Test
    void privateContentKeepsVisibilityWhenEnqueuedForManualReview() {
        when(manualReviewGate.enabled()).thenReturn(true);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        service.publish(1L, new ContentPostCreateRequest(ContentType.DAILY, null,
                "stub-high borderline text", null, null,
                com.tailtopia.content.domain.ContentVisibility.PRIVATE), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(captor.getValue().getVisibility())
                .isEqualTo(com.tailtopia.content.domain.ContentVisibility.PRIVATE);
    }

    /** AC-B3（降级）：开关打开 + 三方降级（stub-timeout）→ 落 UNDER_REVIEW + DEGRADED_FAILCLOSED + 入队。 */
    @Test
    void degradedContentEnqueuedAsUnderReviewWhenManualReviewEnabled() {
        when(manualReviewGate.enabled()).thenReturn(true);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "stub-timeout content", null), null);

        verify(posts).save(captor.capture());
        ContentPost pending = captor.getValue();
        assertThat(pending.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(pending.getReviewReason()).isEqualTo("DEGRADED_FAILCLOSED");
        assertThat(pending.getModerationRiskScore()).isNull(); // 降级评分未知 → 不落
        verify(manualReviewGate).enqueue(anyLong());
        verify(events, never()).publishEvent(any(
                com.tailtopia.content.event.ContentPublishedEvent.class));
        assertThat(resp.id()).isNotNull();
    }

    /** AC-B2：开关打开时命中 L1 硬拦截仍即时 throw、不落挂起态、不入队（硬拦截不进中间态）。 */
    @Test
    void hardBlockedStillThrowsEvenWhenManualReviewEnabled() {
        when(manualReviewGate.enabled()).thenReturn(true);
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
        verify(manualReviewGate, never()).enqueue(anyLong());
    }

    /** G1：开关关闭时命中 L1 硬拦截即 throw、不落库、不入队（现网 FR-12 行为逐字节不变）。 */
    @Test
    void hardBlockedThrowsWhenManualReviewDisabled() {
        // 默认 enabled()=false（现网行为）。
        assertThatThrownBy(() -> service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null), null))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
        verify(manualReviewGate, never()).enqueue(anyLong());
    }

    /** G1：开关关闭时风险 ≥0.8（RISKY）按放行发布（PUBLISHED + 发事件），不入队——关态只有硬拦截失败。 */
    @Test
    void riskyContentPublishedWhenManualReviewDisabled() {
        // 默认 enabled()=false。
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "stub-high borderline text", null), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(com.tailtopia.content.domain.PostStatus.PUBLISHED); // 放行发布
        verify(manualReviewGate, never()).enqueue(anyLong()); // 不入队
        verify(events).publishEvent(any( // 正常进 Feed / 触发里程碑
                com.tailtopia.content.event.ContentPublishedEvent.class));
        assertThat(resp.id()).isNotNull();
    }

    // ===== 内容审核 story 2：版本键 + 守卫（AC-B8 · D-CM3） =====

    /** AC-B8：新发布内容 content_version 默认 1；审核期版本守卫恒等（当前 dormant no-op 安全）。 */
    @Test
    void contentVersionDefaultsToOneAndGuardMatches() {
        ContentPostResponse resp = service.publish(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "cuaca cerah hari ini", List.of("u1")), null);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);
        verify(posts).save(captor.capture());
        ContentPost saved = captor.getValue();
        assertThat(saved.getContentVersion()).isEqualTo(1);
        assertThat(saved.matchesContentVersion(1)).isTrue();  // 入队时刻版本一致 → 处置放行
        assertThat(saved.matchesContentVersion(2)).isFalse(); // 内容已改（未来编辑 +1）→ 旧结果作废
    }

    // ===== Story 3.6 删除 =====

    private ContentPost ownedPost(long id, long authorId) {
        ContentPost p = ContentPost.publish(authorId, ContentType.DAILY, null, "x", null);
        setId(p, id);
        return p;
    }

    @Test
    void authorCanSoftDeleteOwnPostAndCascade() {
        ContentPost p = ownedPost(5L, 1L);
        when(posts.findById(5L)).thenReturn(Optional.of(p));
        when(comments.findByPostIdAndDeletedAtIsNull(5L)).thenReturn(List.of());

        service.deleteByAuthor(5L, 1L);

        assertThat(p.getDeletedAt()).isNotNull(); // 软删置位
        verify(posts).save(p);
        verify(likes).deleteByPostId(5L); // 点赞物理清
    }

    @Test
    void isVideoForcedReviewChecksReviewReason() {
        // 专题线 video Story 3.2：只有 review_reason=VIDEO_FORCED 才算；其他原因 / 不存在 → false。
        when(posts.findById(71L)).thenReturn(Optional.of(ContentPost.pendingReview(1L, ContentType.DAILY, null, "v",
                java.util.List.of(), null, null, ContentPost.REVIEW_REASON_VIDEO_FORCED)));
        when(posts.findById(72L)).thenReturn(Optional.of(ContentPost.pendingReview(1L, ContentType.DAILY, null, "v",
                java.util.List.of(), null, null, "RISK_HIGH")));
        when(posts.findById(73L)).thenReturn(Optional.empty());
        // 已删（作者自删 / 运营下架，队列项仍 PENDING）→ 不算，免得超时扫描误发通知、重复记违规
        ContentPost deleted = ContentPost.pendingReview(1L, ContentType.DAILY, null, "v",
                java.util.List.of(), null, null, ContentPost.REVIEW_REASON_VIDEO_FORCED);
        deleted.softDelete();
        when(posts.findById(74L)).thenReturn(Optional.of(deleted));
        // 已审核通过（reason 不清）→ 不算
        ContentPost approved = ContentPost.pendingReview(1L, ContentType.DAILY, null, "v",
                java.util.List.of(), null, null, ContentPost.REVIEW_REASON_VIDEO_FORCED);
        approved.approveReview();
        when(posts.findById(75L)).thenReturn(Optional.of(approved));

        assertThat(service.isVideoForcedReview(71L)).isTrue();
        assertThat(service.isVideoForcedReview(72L)).isFalse();
        assertThat(service.isVideoForcedReview(73L)).isFalse();
        assertThat(service.isVideoForcedReview(74L)).isFalse();
        assertThat(service.isVideoForcedReview(75L)).isFalse();
    }

    @Test
    void adminMediaSignsOnlyVideoRows() {
        // 专题线 video Story 3.3：图文行原样且不查库；视频行一次 IN 查回实体，换成后台签名投影。
        var image = new com.tailtopia.content.dto.AdminContentRow(1L, ContentType.DAILY, 9L, "i", false,
                java.time.Instant.now(), java.util.List.of("https://cdn/a.jpg"), null, 0L);
        assertThat(service.adminMedia(java.util.List.of(image))).containsExactly(image);
        verify(posts, never()).findAllById(any());

        ContentPost vp = ContentPost.publish(9L, ContentType.DAILY, null, "v", java.util.List.of("https://cdn/c.jpg"));
        org.springframework.test.util.ReflectionTestUtils.setField(vp, "id", 2L);
        var video = new com.tailtopia.content.dto.AdminContentRow(2L, ContentType.DAILY, 9L, "v", true,
                java.time.Instant.now(), java.util.List.of("https://cdn/c.jpg"), null, 3L,
                com.tailtopia.content.domain.MediaType.VIDEO, null);
        when(posts.findAllById(java.util.List.of(2L))).thenReturn(java.util.List.of(vp));
        when(postMedia.resolveForAdmin(vp)).thenReturn(new com.tailtopia.content.dto.PostMedia(
                com.tailtopia.content.domain.MediaType.VIDEO,
                new com.tailtopia.content.dto.VideoView("https://signed/v.mp4", 1000, 720, 1280),
                java.util.List.of("https://signed/c.jpg")));

        var out = service.adminMedia(java.util.List.of(image, video));
        assertThat(out.get(0)).isSameAs(image);
        assertThat(out.get(1).videoUrl()).isEqualTo("https://signed/v.mp4");
        assertThat(out.get(1).imageUrls()).containsExactly("https://signed/c.jpg");
        assertThat(out.get(1).deleted()).isTrue();
        assertThat(out.get(1).commentCount()).isEqualTo(3L);
    }

    @Test
    void nonAuthorCannotDelete() {
        when(posts.findById(5L)).thenReturn(Optional.of(ownedPost(5L, 1L)));
        assertThatThrownBy(() -> service.deleteByAuthor(5L, 999L)).isInstanceOf(AppException.class);
        verify(likes, never()).deleteByPostId(anyLong());
    }

    @Test
    void deleteMissingOrAlreadyDeletedIsIdempotent() {
        when(posts.findById(404L)).thenReturn(Optional.empty());
        service.deleteByAuthor(404L, 1L); // 不抛异常
        verify(posts, never()).save(any());
    }

    @Test
    void softDeleteReusableForTakedownCascadesComments() {
        ContentPost p = ownedPost(6L, 1L);
        when(posts.findById(6L)).thenReturn(Optional.of(p));
        com.tailtopia.content.domain.Comment c1 = newComment(70L);
        com.tailtopia.content.domain.Comment c2 = newComment(71L);
        when(comments.findByPostIdAndDeletedAtIsNull(6L)).thenReturn(List.of(c1, c2));

        service.softDelete(6L, com.tailtopia.content.domain.DeleteReason.ADMIN_TAKEDOWN);

        assertThat(p.getDeletedAt()).isNotNull();
        assertThat(c1.getDeletedAt()).isNotNull();
        assertThat(c2.getDeletedAt()).isNotNull();
        verify(likes).deleteByPostId(6L);
    }

    // ===== R2 · AC3 运营下架 → 通知作者领域事件 =====

    @Test
    void adminTakedownPublishesContentRemovedEvent() {
        ContentPost p = ownedPost(8L, 42L);
        when(posts.findById(8L)).thenReturn(Optional.of(p));
        when(comments.findByPostIdAndDeletedAtIsNull(8L)).thenReturn(List.of());

        service.softDelete(8L, com.tailtopia.content.domain.DeleteReason.ADMIN_TAKEDOWN);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.tailtopia.content.event.ContentRemovedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().postId()).isEqualTo(8L);
        assertThat(captor.getValue().authorId()).isEqualTo(42L); // 推送目标=作者
    }

    @Test
    void authorDeleteDoesNotPublishRemovedEvent() {
        ContentPost p = ownedPost(9L, 1L);
        when(posts.findById(9L)).thenReturn(Optional.of(p));
        when(comments.findByPostIdAndDeletedAtIsNull(9L)).thenReturn(List.of());

        service.deleteByAuthor(9L, 1L); // 作者自删不自通知

        verify(events, never()).publishEvent(any(
                com.tailtopia.content.event.ContentRemovedEvent.class));
    }

    // ===== Story 4.2：运营恢复已下架内容 =====

    @Test
    void restoreClearsDeletedAtAndSaves() {
        ContentPost p = ownedPost(11L, 1L);
        p.softDelete(); // 先置为已下架
        assertThat(p.getDeletedAt()).isNotNull();
        when(posts.findById(11L)).thenReturn(Optional.of(p));

        service.restore(11L);

        assertThat(p.getDeletedAt()).isNull(); // 重回公开口径
        verify(posts).save(p);
    }

    @Test
    void restoreIsNoOpWhenNotDeleted() {
        ContentPost p = ownedPost(12L, 1L); // 未删
        when(posts.findById(12L)).thenReturn(Optional.of(p));

        service.restore(12L); // 幂等：未删不写库

        assertThat(p.getDeletedAt()).isNull();
        verify(posts, never()).save(any(ContentPost.class));
    }

    // ---- 内容审核 cm-6：P0 举报预处置 hold / 误报恢复（关键：恢复不得双 fire ContentPublishedEvent） ----

    @Test
    void applyReportHoldFlipsPublishedToUnderReviewAndFiresNoEvent() {
        ContentPost p = ContentPost.publish(1L, ContentType.DAILY, null, "x", List.of());
        setId(p, 55L);
        when(posts.findById(55L)).thenReturn(Optional.of(p));

        service.applyReportHoldIfPublished(55L);

        assertThat(p.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(p.getReportHiddenAt()).isNotNull();
        assertThat(p.getReviewReason()).isEqualTo("REPORT_P0");
        verify(posts).save(p);
        verify(events, never()).publishEvent(any()); // 预处置中间态：不发任何事件、不通知
    }

    @Test
    void applyReportHoldIsIdempotentWhenAlreadyHeld() {
        ContentPost p = ContentPost.publish(1L, ContentType.DAILY, null, "x", List.of());
        setId(p, 56L);
        p.applyReportHold(); // 已预处置（reportHiddenAt 非空）
        when(posts.findById(56L)).thenReturn(Optional.of(p));

        service.applyReportHoldIfPublished(56L);

        verify(posts, never()).save(any(ContentPost.class)); // 幂等：不重复处置/入队
    }

    @Test
    void releaseReportHoldRestoresPublishedWithoutRepublishEvent() {
        ContentPost p = ContentPost.publish(1L, ContentType.DAILY, null, "x", List.of());
        setId(p, 57L);
        p.applyReportHold(); // UNDER_REVIEW + reportHiddenAt + REPORT_P0
        when(posts.findById(57L)).thenReturn(Optional.of(p));

        service.releaseReportHold(57L);

        assertThat(p.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.PUBLISHED);
        assertThat(p.getReportHiddenAt()).isNull();
        assertThat(p.getReviewReason()).isNull();
        verify(posts).save(p);
        // 关键（D-CM6）：误报恢复绝不重复 fire ContentPublishedEvent → 里程碑不双触发、作者不通知。
        verify(events, never()).publishEvent(any());
    }

    @Test
    void releaseReportHoldNoOpWhenNotUnderReportHold() {
        ContentPost p = ContentPost.publish(1L, ContentType.DAILY, null, "x", List.of()); // 普通 PUBLISHED，未 hold
        setId(p, 58L);
        when(posts.findById(58L)).thenReturn(Optional.of(p));

        service.releaseReportHold(58L);

        verify(posts, never()).save(any(ContentPost.class));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void countGrowthMomentsIncludesUnderReview() {
        // bug 20260728-379：统计口径含审核中（timeline 不过滤 status，统计卡只数 PUBLISHED 会 5 发 3 计）。
        when(posts.countByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndStatusIn(1L, 9L,
                ContentType.GROWTH_MOMENT,
                List.of(com.tailtopia.content.domain.PostStatus.PUBLISHED,
                        com.tailtopia.content.domain.PostStatus.UNDER_REVIEW)))
                .thenReturn(5L);

        assertThat(service.countGrowthMoments(1L, 9L)).isEqualTo(5L);
    }

    private static com.tailtopia.content.domain.Comment newComment(long id) {
        try {
            var ctor = com.tailtopia.content.domain.Comment.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            com.tailtopia.content.domain.Comment c = ctor.newInstance();
            var f = com.tailtopia.content.domain.Comment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
            return c;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ===== publishTrusted（Lark 运营内容源，免审——spec-lark-scheduled-posts）=====

    /** 会被硬拦截的文本经 publishTrusted 正常发布 = 审核确实被跳过；发布事件保留。 */
    @Test
    void publishTrustedSkipsModerationButKeepsEvent() {
        ContentPostResponse resp = service.publishTrusted(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "ayo main judi online", null),
                "lark-content:T1");
        assertThat(resp.id()).isEqualTo(100L);
        verify(events).publishEvent(any(com.tailtopia.content.event.ContentPublishedEvent.class));
        verify(idempotency).store("lark-content:T1", 100L);
    }

    /** 幂等重放：同 key 已有资源 → 取回不重建。 */
    @Test
    void publishTrustedIdempotentReplay() {
        when(idempotency.findResourceId("lark-content:T1")).thenReturn(Optional.of(77L));
        ContentPost existing = ContentPost.publish(1L, ContentType.DAILY, null, "old", null);
        setId(existing, 77L);
        when(posts.findById(77L)).thenReturn(Optional.of(existing));

        ContentPostResponse resp = service.publishTrusted(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "whatever", null),
                "lark-content:T1");

        assertThat(resp.id()).isEqualTo(77L);
        verify(posts, never()).save(any());
    }

    /** 幂等键残留（外层事务回滚后 Redis 键指向幽灵 id）：不抛 notFound，按未发布继续创建。 */
    @Test
    void publishTrustedGhostIdempotencyKeyFallsThroughToCreate() {
        when(idempotency.findResourceId("lark-content:T1")).thenReturn(Optional.of(77L));
        when(posts.findById(77L)).thenReturn(Optional.empty());

        ContentPostResponse resp = service.publishTrusted(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "retry", null),
                "lark-content:T1");

        assertThat(resp.id()).isEqualTo(100L);
        verify(posts).save(any());
        verify(idempotency).store("lark-content:T1", 100L);
    }

    @Test
    void publishTrustedRejectsGrowthMoment() {
        assertThatThrownBy(() -> service.publishTrusted(1L,
                new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, null, "x", null), "k"))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void publishTrustedRejectsNullType() {
        assertThatThrownBy(() -> service.publishTrusted(1L,
                new ContentPostCreateRequest(null, null, "x", null), "k"))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void publishTrustedRejectsOverlongText() {
        assertThatThrownBy(() -> service.publishTrusted(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "x".repeat(1001), null), "k"))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void publishTrustedRejectsTooManyImages() {
        List<String> ten = List.of("u1", "u2", "u3", "u4", "u5", "u6", "u7", "u8", "u9", "u10");
        assertThatThrownBy(() -> service.publishTrusted(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, null, ten), "k"))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void publishTrustedRejectsBothEmpty() {
        assertThatThrownBy(() -> service.publishTrusted(1L,
                new ContentPostCreateRequest(ContentType.DAILY, null, "  ", null), "k"))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    // ===== V1.3.4 Story 2.1：每张图的露出位置（AD-5）=====

    private static ContentPostCreateRequest withCropOffsets(List<String> imageUrls, List<Double> offsets) {
        return new ContentPostCreateRequest(ContentType.DAILY, null, "Anabul lagi santai", imageUrls, null, null,
                null, null, null, offsets);
    }

    /** ① 等长合法 → 原样落库（含 null 占位、顺序与数值不变）。 */
    @Test
    void validCropOffsetsArePersistedAsIs() {
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);
        List<Double> offsets = java.util.Arrays.asList(0.0, null, 1.0);

        service.publish(1L, withCropOffsets(List.of("u1", "u2", "u3"), offsets), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getImageCropOffsets()).isEqualTo(java.util.Arrays.asList(0.0, null, 1.0));
    }

    /** ② 长度不符 → 422，不落库（刻意不像尺寸那样整组作废）。 */
    @Test
    void cropOffsetsLengthMismatchRejectedWith422() {
        assertThatThrownBy(() -> service.publish(1L,
                withCropOffsets(List.of("u1", "u2"), List.of(0.5)), null))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("图片露出位置与图片数量不符");
                });
        verify(posts, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** ③ 上越界 → 422。 */
    @Test
    void cropOffsetAboveOneRejectedWith422() {
        assertThatThrownBy(() -> service.publish(1L,
                withCropOffsets(List.of("u1"), List.of(1.0001)), null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(422));
        verify(posts, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** ④ 下越界 → 422。 */
    @Test
    void cropOffsetBelowZeroRejectedWith422() {
        assertThatThrownBy(() -> service.publish(1L,
                withCropOffsets(List.of("u1"), List.of(-0.0001)), null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(422));
        verify(posts, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** ⑤ 不带字段 → 列为 null（老客户端行为不变）。 */
    @Test
    void absentCropOffsetsPersistNull() {
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        service.publish(1L, new ContentPostCreateRequest(ContentType.DAILY, null, "hi", List.of("u1")), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getImageCropOffsets()).isNull();
    }

    /** ⑥ 🔴 审核挂起分支同样要写露出位置（与尺寸 / @ 名单 / 打卡关联同一个坑）。 */
    @Test
    void pendingReviewPostAlsoCarriesCropOffsets() {
        when(manualReviewGate.enabled()).thenReturn(true);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        service.publish(1L, new ContentPostCreateRequest(
                ContentType.DAILY, null, "stub-high borderline text",
                List.of("https://cdn/a.jpg", "https://cdn/b.jpg"), null, null, null, null, null,
                java.util.Arrays.asList(0.25, null)), null);

        verify(posts).save(captor.capture());
        ContentPost pending = captor.getValue();
        assertThat(pending.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(pending.getImageCropOffsets())
                .as("挂起分支漏写露出位置 —— 审核通过后进 Feed 会无声退化为居中")
                .isEqualTo(java.util.Arrays.asList(0.25, null));
    }

    // ===== V1.4.0 multi-pet Story 3.1：Diary 关联多只宠物（AC3 / AC4）=====

    private static final long PET_A = 21L; // 先建档
    private static final long PET_B = 22L;

    private static ContentPostCreateRequest diary(Long petId, List<Long> petIds) {
        return diary(petId, petIds, "Main bareng");
    }

    private static ContentPostCreateRequest diary(Long petId, List<Long> petIds, String text) {
        return new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, petId, text, null, null, null,
                null, null, null, null, petIds);
    }

    @SuppressWarnings("unchecked")
    private List<Long> savedLinkPetIds() {
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(contentPets).saveAll(captor.capture());
        List<Long> out = new java.util.ArrayList<>();
        for (Object o : (Iterable<Object>) captor.getValue()) {
            var link = (com.tailtopia.content.domain.ContentPet) o;
            assertThat(link.getPostId()).isEqualTo(100L);
            out.add(link.getPetProfileId());
        }
        return out;
    }

    private com.tailtopia.content.event.ContentPublishedEvent publishedEvent() {
        var captor = org.mockito.ArgumentCaptor.forClass(com.tailtopia.content.event.ContentPublishedEvent.class);
        verify(events).publishEvent(captor.capture());
        return captor.getValue();
    }

    /** AC3 / AC4.3：选 B、A（A 先建档）→ pet_id = A、关联两行、事件 petIds = [A, B] / petId = A。 */
    @Test
    void diaryWithTwoPetsStoresFirstPetAndLinksBoth() {
        ownPetCreatedAt(1L, PET_A, java.time.Instant.parse("2026-01-01T00:00:00Z"));
        ownPetCreatedAt(1L, PET_B, java.time.Instant.parse("2026-02-01T00:00:00Z"));
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        ContentPostResponse resp = service.publish(1L, diary(null, List.of(PET_B, PET_A)), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getPetId()).isEqualTo(PET_A);
        assertThat(resp.petId()).isEqualTo(PET_A);
        assertThat(savedLinkPetIds()).containsExactly(PET_A, PET_B);
        var e = publishedEvent();
        assertThat(e.petIds()).containsExactly(PET_A, PET_B);
        assertThat(e.petId()).isEqualTo(PET_A);
    }

    /** 「第一只」按建档时刻而非 id：id 大的先建档时排第一。 */
    @Test
    void firstPetFollowsCreationOrderNotId() {
        ownPetCreatedAt(1L, 30L, java.time.Instant.parse("2026-03-01T00:00:00Z"));
        ownPetCreatedAt(1L, 31L, java.time.Instant.parse("2025-03-01T00:00:00Z"));

        service.publish(1L, diary(null, List.of(30L, 31L)), null);

        assertThat(savedLinkPetIds()).containsExactly(31L, 30L);
        assertThat(publishedEvent().petId()).isEqualTo(31L);
    }

    /** 旧版 App / 后台种子：只传 petId → 视为 [petId]。 */
    @Test
    void legacyPetIdOnlyIsTreatedAsSinglePet() {
        ownPets(1L, 5L);

        service.publish(1L, new ContentPostCreateRequest(ContentType.GROWTH_MOMENT, 5L, "m", null), null);

        assertThat(savedLinkPetIds()).containsExactly(5L);
        var e = publishedEvent();
        assertThat(e.petIds()).containsExactly(5L);
        assertThat(e.petId()).isEqualTo(5L);
    }

    /** petIds 非空时以 petIds 为准（petId 不再并入）。 */
    @Test
    void petIdsTakePrecedenceOverLegacyPetId() {
        ownPets(1L, PET_A, PET_B);

        service.publish(1L, diary(PET_A, List.of(PET_B)), null);

        assertThat(savedLinkPetIds()).containsExactly(PET_B);
    }

    /** petIds 与 petId 都没有 → 422 validation（沿用 growthNeedsPet 码），不落库。 */
    @Test
    void diaryWithoutAnyPetIs422() {
        assertThatThrownBy(() -> service.publish(1L, diary(null, List.of()), null))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.seed.growthNeedsPet");
                });
        verify(posts, never()).save(any());
        verify(contentPets, never()).saveAll(any());
    }

    /** 含非本人 / 不存在的宠物 → 404（NFR5），保留后台回显码，不落库、不建关联。 */
    @Test
    void diaryWithForeignPetIs404AndNothingSaved() {
        ownPets(1L, PET_A);

        assertThatThrownBy(() -> service.publish(1L, diary(null, List.of(PET_A, 999L)), null))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(404);
                    assertThat(e.getMessageCode()).isEqualTo("admin.err.seed.petNotOwned");
                });
        verify(posts, never()).save(any());
        verify(contentPets, never()).saveAll(any());
        verify(petWriteGuard, never()).requireWritable(anyLong());
    }

    /** 含只读宠物 → 409 pet-read-only（带该 petId），不落库、不发事件。 */
    @Test
    void diaryWithReadOnlyPetIs409AndNothingSaved() {
        ownPets(1L, PET_A, PET_B);
        Mockito.doThrow(AppException.petReadOnly(PET_B)).when(petWriteGuard).requireWritable(PET_B);

        assertThatThrownBy(() -> service.publish(1L, diary(null, List.of(PET_A, PET_B)), null))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getType().toString()).isEqualTo("https://petgo/errors/pet-read-only");
                    assertThat(((com.tailtopia.shared.error.PetReadOnlyException) e).getPetId()).isEqualTo(PET_B);
                });
        verify(posts, never()).save(any());
        verify(contentPets, never()).saveAll(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** 重复 id 去重：[A, A, B] → 两行。 */
    @Test
    void duplicatePetIdsAreDeduplicated() {
        ownPets(1L, PET_A, PET_B);

        service.publish(1L, diary(null, List.of(PET_A, PET_A, PET_B)), null);

        assertThat(savedLinkPetIds()).containsExactly(PET_A, PET_B);
    }

    /** AC15：Moment / Tips 带 petIds / petId 一律忽略：pet_id 为空、不建关联、事件 petIds 为空表。 */
    @Test
    void momentAndTipsIgnorePetIds() {
        for (ContentType t : List.of(ContentType.DAILY, ContentType.KNOWLEDGE)) {
            Mockito.clearInvocations(posts, contentPets, events);
            var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

            service.publish(1L, new ContentPostCreateRequest(t, PET_A, "hi", null, null, null,
                    null, null, null, null, List.of(PET_A, PET_B)), null);

            verify(posts).save(captor.capture());
            assertThat(captor.getValue().getPetId()).isNull();
            verify(contentPets, never()).saveAll(any());
            assertThat(publishedEvent().petIds()).isEmpty();
        }
        verify(petResolver, never()).resolve(anyLong(), any());
    }

    /** 🔴 AC3.3：挂起分支（UNDER_REVIEW）同样要写 content_pets。 */
    @Test
    void pendingReviewDiaryAlsoLinksAllPets() {
        when(manualReviewGate.enabled()).thenReturn(true);
        ownPets(1L, PET_A, PET_B);
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);

        service.publish(1L, diary(null, List.of(PET_A, PET_B), "stub-high borderline text"), null);

        verify(posts).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(captor.getValue().getPetId()).isEqualTo(PET_A);
        assertThat(savedLinkPetIds()).containsExactly(PET_A, PET_B);
        verify(events, never()).publishEvent(any(com.tailtopia.content.event.ContentPublishedEvent.class));
    }

    /** AC3.5：幂等重放不重复建关联。 */
    @Test
    void idempotentReplayDoesNotRelink() {
        ContentPost existing = ContentPost.publish(1L, ContentType.GROWTH_MOMENT, PET_A, "old", null);
        when(idempotency.findResourceId("KEY3")).thenReturn(Optional.of(42L));
        when(posts.findById(42L)).thenReturn(Optional.of(existing));

        service.publish(1L, diary(null, List.of(PET_A, PET_B)), "KEY3");

        verify(contentPets, never()).saveAll(any());
        verify(petResolver, never()).resolve(anyLong(), any());
    }

    /** AC4.2：挂起帖过审 → 事件 petIds 读回关联表（全部关联宠物），petId = 第一只。 */
    @Test
    void approveReviewEventCarriesLinkedPets() {
        ContentPost p = ContentPost.pendingReview(1L, ContentType.GROWTH_MOMENT, PET_A, "x", null,
                LocalDate.of(2026, 1, 1), null, "RISK_HIGH");
        setId(p, 60L);
        when(posts.findById(60L)).thenReturn(Optional.of(p));
        when(contentPetQuery.petsOf(60L)).thenReturn(List.of(PET_A, PET_B));

        service.approveReview(60L);

        var e = publishedEvent();
        assertThat(e.petIds()).containsExactly(PET_A, PET_B);
        assertThat(e.petId()).isEqualTo(PET_A);
    }

    /** publishTrusted 不支持 Diary，事件 petIds 恒为空表。 */
    @Test
    void publishTrustedEventHasNoPets() {
        service.publishTrusted(1L, new ContentPostCreateRequest(ContentType.DAILY, null, "hi", null), "k9");
        assertThat(publishedEvent().petIds()).isEmpty();
    }

    /** AC7.2：里程碑打卡「帖子关联该宠物」读 content_pets。 */
    @Test
    void isOwnGrowthMomentOfPetReadsContentPets() {
        ContentPost p = ContentPost.publish(1L, ContentType.GROWTH_MOMENT, PET_A, "x", null);
        setId(p, 61L);
        when(posts.findById(61L)).thenReturn(Optional.of(p));
        when(contentPets.existsById(new com.tailtopia.content.domain.ContentPet.Key(61L, PET_B))).thenReturn(true);

        assertThat(service.isOwnGrowthMomentOfPet(1L, PET_B, 61L)).isTrue(); // pet_id = A，但关联了 B
        assertThat(service.isOwnGrowthMomentOfPet(1L, 23L, 61L)).isFalse(); // 未关联
        assertThat(service.isOwnGrowthMomentOfPet(2L, PET_B, 61L)).isFalse(); // 非本人
    }

    /** P-7：被赞 / 被评论归帖子关联的每一只宠物；Moment → 空表（调用方回落默认宠物）。 */
    @Test
    void growthMomentPetIdsOfReturnsAllLinkedPets() {
        ContentPost diaryPost = ContentPost.publish(1L, ContentType.GROWTH_MOMENT, PET_A, "x", null);
        setId(diaryPost, 62L);
        ContentPost moment = ContentPost.publish(1L, ContentType.DAILY, null, "x", null);
        setId(moment, 63L);
        when(posts.findById(62L)).thenReturn(Optional.of(diaryPost));
        when(posts.findById(63L)).thenReturn(Optional.of(moment));
        when(contentPetQuery.petsOf(62L)).thenReturn(List.of(PET_A, PET_B));

        assertThat(service.growthMomentPetIdsOf(62L)).containsExactly(PET_A, PET_B);
        assertThat(service.growthMomentPetIdsOf(63L)).isEmpty();
        assertThat(service.growthMomentPetIdsOf(64L)).isEmpty();
    }

    /** AC7.1：按宠物的已发布 Diary 数走 content_pets 的计数查询。 */
    @Test
    void countPublishedGrowthMomentsOfPetUsesLinkTable() {
        when(posts.countPublishedGrowthMomentsOfPet(1L, PET_B, ContentType.GROWTH_MOMENT,
                com.tailtopia.content.domain.PostStatus.PUBLISHED)).thenReturn(3L);

        assertThat(service.countPublishedGrowthMomentsOfPet(1L, PET_B)).isEqualTo(3L);
    }

    /** AC3.1：DTO 层 @Size(max = 5)：6 只被拦。 */
    @Test
    void moreThanFivePetIdsRejectedByDto() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(
                    diary(null, List.of(1L, 2L, 3L, 4L, 5L, 6L)));
            assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("petIds");
            assertThat(factory.getValidator().validate(diary(null, List.of(1L, 2L, 3L, 4L, 5L)))).isEmpty();
        }
    }

    // ---------- 专题线 video Story 1.3：视频帖发布分支 ----------

    private static final String COVER = "https://cdn.example/public/1/cover.jpg";
    private static final String VKEY = "video/1/abcdefghijklmnopqrstuv.mp4";

    private static ContentPostCreateRequest videoReq(String text) {
        return new ContentPostCreateRequest(ContentType.DAILY, null, text, List.of(COVER), null,
                com.tailtopia.content.domain.ContentVisibility.PRIVATE, // 请求私密也要被强制公开
                List.of(new com.tailtopia.content.domain.ImageSize(1, 1)), // 客户端尺寸不采信
                null, null, null, com.tailtopia.content.domain.MediaType.VIDEO,
                new com.tailtopia.content.dto.VideoPayload(VKEY, 75_000, 720, 1280));
    }

    private static final VideoPostValidator.VerifiedVideo VERIFIED = new VideoPostValidator.VerifiedVideo(VKEY, 25_000_000L);

    private ContentPost savedPost() {
        var captor = org.mockito.ArgumentCaptor.forClass(ContentPost.class);
        verify(posts).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void trustedVideoPublishesDirectlyWithoutModerationOrBackfill() {
        when(vipLevels.canPublishVideo(1L)).thenReturn(true);
        when(videoTrusted.isTrusted(1L)).thenReturn(true);

        // 正文含 L1 违禁词也直接发布：名单内跳过全部审核（AD-5）。
        service.publish(1L, videoReq("ayo main judi online"), "K", VERIFIED);

        ContentPost p = savedPost();
        assertThat(p.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.PUBLISHED);
        assertThat(p.getMediaType()).isEqualTo(com.tailtopia.content.domain.MediaType.VIDEO);
        assertThat(p.getVisibility()).isEqualTo(com.tailtopia.content.domain.ContentVisibility.PUBLIC);
        assertThat(p.getImageUrls()).containsExactly(COVER);
        assertThat(p.getImageSizes()).containsExactly(new com.tailtopia.content.domain.ImageSize(720, 1280));
        assertThat(p.getImageCropOffsets()).isNull();
        assertThat(p.getVideoObjectKey()).isEqualTo(VKEY);
        assertThat(p.getVideoDurationMs()).isEqualTo(75_000);
        assertThat(p.getVideoSizeBytes()).isEqualTo(25_000_000L);
        assertThat(p.getMediaAclDesired()).isEqualTo(com.tailtopia.content.domain.MediaAcl.PUBLIC);
        assertThat(p.getMediaAclApplied()).isEqualTo(com.tailtopia.content.domain.MediaAcl.PRIVATE);
        assertThat(p.getMediaAclDesiredAt()).isNotNull();
        verify(events).publishEvent(any(com.tailtopia.content.event.ContentPublishedEvent.class));
        verify(idempotency).store("K", 100L);
        verify(manualReviewGate, never()).enqueue(anyLong());
    }

    @Test
    void untrustedVideoIsForcedIntoReviewEvenWhenGateDisabled() {
        when(vipLevels.canPublishVideo(1L)).thenReturn(true);
        when(videoTrusted.isTrusted(1L)).thenReturn(false);
        // 默认 manualReviewGate.enabled()=false。

        service.publish(1L, videoReq("halo"), null, VERIFIED);

        ContentPost p = savedPost();
        assertThat(p.getStatus()).isEqualTo(com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        assertThat(p.getReviewReason()).isEqualTo(ContentPost.REVIEW_REASON_VIDEO_FORCED);
        assertThat(p.getMediaAclDesired()).isEqualTo(com.tailtopia.content.domain.MediaAcl.PRIVATE);
        assertThat(p.getMediaAclApplied()).isEqualTo(com.tailtopia.content.domain.MediaAcl.PRIVATE);
        assertThat(p.getImageSizes()).containsExactly(new com.tailtopia.content.domain.ImageSize(720, 1280));
        verify(manualReviewGate).enqueue(100L);
        verify(manualReviewGate, never()).enabled();
        verify(events, never()).publishEvent(any(com.tailtopia.content.event.ContentPublishedEvent.class));
    }

    @Test
    void untrustedVideoTextHardBlockedIsRejected() {
        when(vipLevels.canPublishVideo(1L)).thenReturn(true);
        assertThatThrownBy(() -> service.publish(1L, videoReq("ayo main judi online"), null, VERIFIED))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.CONTENT_TEXT_BLOCKED));
        verify(posts, never()).save(any());
        verify(manualReviewGate, never()).enqueue(anyLong());
    }

    /** code review：同一个视频对象已被别的帖子使用 → 422，不落库（否则删一条会把另一条的视频改私有）。 */
    @Test
    void videoObjectAlreadyUsedIsRejected() {
        when(vipLevels.canPublishVideo(1L)).thenReturn(true);
        when(videoTrusted.isTrusted(1L)).thenReturn(true);
        when(posts.existsByVideoObjectKey(VKEY)).thenReturn(true);
        assertThatThrownBy(() -> service.publish(1L, videoReq("x"), null, VERIFIED))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.VIDEO_OBJECT_INVALID));
        verify(posts, never()).save(any());
    }

    @Test
    void videoWithoutOutOfTransactionVerificationIsRejected() {
        when(vipLevels.canPublishVideo(1L)).thenReturn(true);
        // 3 参 publish（后台调用方）传 VIDEO：没经过 HEAD / 归属校验，不许落库。
        assertThatThrownBy(() -> service.publish(1L, videoReq("x"), null))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.VIDEO_OBJECT_INVALID));
        // 校验过的 key 与请求不一致同样拒。
        assertThatThrownBy(() -> service.publish(1L, videoReq("x"), null,
                new VideoPostValidator.VerifiedVideo("video/1/other.mp4", 1L)))
                .isInstanceOf(AppException.class);
        verify(posts, never()).save(any());
    }

    @Test
    void levelIsRecheckedInsideTransaction() {
        when(vipLevels.canPublishVideo(1L)).thenReturn(false); // 事务外校验之后被后台降级
        assertThatThrownBy(() -> service.publish(1L, videoReq("x"), null, VERIFIED))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.VIDEO_PERMISSION_DENIED));
        verify(posts, never()).save(any());
    }

    @Test
    void imagePostCarryingVideoIsMixedMedia() {
        var req = new ContentPostCreateRequest(ContentType.DAILY, null, "x", List.of(COVER), null, null, null,
                null, null, null, null, new com.tailtopia.content.dto.VideoPayload(VKEY, 5000, 720, 1280));
        assertThatThrownBy(() -> service.publish(1L, req, null))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType())
                        .isEqualTo(com.tailtopia.shared.error.ErrorTypes.VIDEO_MIXED_MEDIA));
        verify(posts, never()).save(any());
    }
}
