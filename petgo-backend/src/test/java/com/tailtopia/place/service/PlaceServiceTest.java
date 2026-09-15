package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.moderation.DegradeReason;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.dto.PlaceCreateRequest;
import com.tailtopia.place.repository.PlaceReportRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * L0（mock 仓储 + mock 审核，无 DB / 无三方）：标记场所的落库与审核口径
 * （V1.3.0 batch-b1 Story 1.3 · AC1/AC8）。
 */
class PlaceServiceTest {

    private PlaceRepository places;
    private ContentModerationService moderation;
    private IdempotencyService idempotency;
    private PlaceReportRepository reports;
    private PlacePhotoService photoService;
    private PlaceService service;

    @BeforeEach
    void setUp() {
        places = Mockito.mock(PlaceRepository.class);
        moderation = Mockito.mock(ContentModerationService.class);
        idempotency = Mockito.mock(IdempotencyService.class);
        when(idempotency.findResourceId(any())).thenReturn(java.util.Optional.empty());
        reports = Mockito.mock(PlaceReportRepository.class);
        // Story 1.9：照片落 place_photos，由它负责。
        photoService = Mockito.mock(PlacePhotoService.class);
        service = new PlaceService(places, new PlaceTokenGenerator(), moderation, idempotency,
                reports, photoService);
        // save 之后 id 一定不为空（JPA @GeneratedValue）—— Story 1.9 起照片要挂到它上面。
        when(places.save(any(Place.class))).thenAnswer(inv -> withId(inv.getArgument(0), 42L));
    }

    private static PlaceCreateRequest request() {
        return new PlaceCreateRequest("Kopi Kayu Manis", PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE, PlaceTag.OUTDOOR_SEATING),
                -6.235, 106.81, "Jl. Senopati No.75", "Ada area outdoor",
                List.of("https://cdn/a.jpg", "https://cdn/b.jpg"));
    }

    private void verdict(ModerationOutcome outcome) {
        when(moderation.evaluate(anyString(), anyList())).thenReturn(outcome);
    }

    // ===== AC8 审核口径：先发后审 =====

    @Test
    void passPublishesTheePlace() {
        verdict(ModerationOutcome.pass(0.1, null));

        Place saved = service.mark(7L, request(), null);

        assertThat(saved.getName()).isEqualTo("Kopi Kayu Manis");
        assertThat(saved.getCreatedBy()).isEqualTo(7L);
        assertThat(saved.getStatus()).isEqualTo(PlaceStatus.ACTIVE);
        assertThat(saved.getPublicToken()).hasSize(32);
    }

    @Test
    void textBlockedIsRejectedAndNothingIsSaved() {
        verdict(ModerationOutcome.textBlocked("PORN"));

        assertThatThrownBy(() -> service.mark(7L, request(), null))
                .isInstanceOf(AppException.class);
        verify(places, never()).save(any());
    }

    @Test
    void imageBlockedIsRejectedAndNothingIsSaved() {
        verdict(ModerationOutcome.imageBlocked("PORN"));

        assertThatThrownBy(() -> service.mark(7L, request(), null))
                .isInstanceOf(AppException.class);
        verify(places, never()).save(any());
    }

    /**
     * 🔴 RISKY 仍然落库 —— **先发后审**（PRD ①）。场所没有内容帖那种「挂起待人工」队列，
     * 运营入口是后台 AB-17A。这不是漏写。
     */
    @Test
    void riskyStillPublishesBecauseReviewHappensAfterwards() {
        verdict(ModerationOutcome.risky(0.9, "SPAM"));

        Place saved = service.mark(7L, request(), null);

        assertThat(saved.getStatus()).isEqualTo(PlaceStatus.ACTIVE);
        verify(places).save(any(Place.class));
    }

    /**
     * ⚠️ DEGRADED（三方挂了）在内容帖那边是 fail-closed 挂起，这里是**放行** ——
     * 「先发后审」这条产品口径的直接后果。改成 fail-closed 属产品决策，回决策日志谈。
     */
    @Test
    void degradedStillPublishesUnderPostModerationPolicy() {
        verdict(ModerationOutcome.degraded(DegradeReason.TIMEOUT));

        assertThat(service.mark(7L, request(), null).getStatus()).isEqualTo(PlaceStatus.ACTIVE);
    }

    /** 🔴 **文字地址也要过审** —— 漏掉它等于留了个「把违规内容写在地址栏里」的口子。 */
    @Test
    void moderationInputCoversNameAddressAndDescription() {
        verdict(ModerationOutcome.pass(0.1, null));

        service.mark(7L, request(), null);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(moderation).evaluate(text.capture(), anyList());
        assertThat(text.getValue())
                .contains("Kopi Kayu Manis")
                .contains("Jl. Senopati No.75")
                .contains("Ada area outdoor");
    }

    /** 照片也一并送审（图片违规靠三方识别）。 */
    @Test
    void moderationInputCoversPhotos() {
        verdict(ModerationOutcome.pass(0.1, null));

        service.mark(7L, request(), null);

        verify(moderation).evaluate(anyString(),
                org.mockito.ArgumentMatchers.eq(List.of("https://cdn/a.jpg", "https://cdn/b.jpg")));
    }

    // ===== AC1 落库细节 =====

    @Test
    void duplicateTagsAreCollapsed() {
        verdict(ModerationOutcome.pass(0.1, null));
        PlaceCreateRequest req = new PlaceCreateRequest("A", PlaceType.PARK,
                List.of(PlaceTag.LEASH_REQUIRED, PlaceTag.LEASH_REQUIRED, PlaceTag.PET_MENU),
                -6.2, 106.8, "Jl. A", null, List.of("https://cdn/a.jpg"));

        Place saved = service.mark(1L, req, null);

        assertThat(saved.getTags()).containsExactly(PlaceTag.LEASH_REQUIRED, PlaceTag.PET_MENU);
    }

    @Test
    void blankDescriptionBecomesNullAndTextIsTrimmed() {
        verdict(ModerationOutcome.pass(0.1, null));
        PlaceCreateRequest req = new PlaceCreateRequest("  Taman  ", PlaceType.PARK,
                List.of(PlaceTag.LEASH_REQUIRED), -6.2, 106.8, "  Jl. A  ", "   ",
                List.of("https://cdn/a.jpg"));

        Place saved = service.mark(1L, req, null);

        assertThat(saved.getName()).isEqualTo("Taman");
        assertThat(saved.getAddressText()).isEqualTo("Jl. A");
        assertThat(saved.getDescription()).isNull();
    }

    /**
     * Story 1.9：标记时提交的照片落 {@code place_photos}（上传者 = 标记人）。
     *
     * <p>这批**已经在上面过了同步富审核**（连同名称/地址/描述一起送审，含图审），
     * 所以直接可见，不再走一次异步 —— 同一批图审两遍是白花配额。
     */
    @Test
    void initialPhotosAreStoredInThePhotoTableWithTheMarkerAsUploader() {
        verdict(ModerationOutcome.pass(0.1, null));

        service.mark(7L, request(), null);

        verify(photoService).storeInitialPhotos(42L, 7L,
                List.of("https://cdn/a.jpg", "https://cdn/b.jpg"), true);
    }

    // ===== 幂等（code-review 2026-09-15 追加）=====

    /**
     * 🔴 同一个 Idempotency-Key 重放 → **取回原来那条，不再建一个**。
     *
     * <p>场所既不能编辑也不能删除，丢一个 201 + 客户端重试 = 一个永久重复的条目，
     * 只能等运营去后台合并。
     */
    @Test
    void replayWithSameKeyReturnsTheExistingPlaceAndCreatesNothing() {
        Place existing = place();
        when(idempotency.findResourceId("k1")).thenReturn(java.util.Optional.of(42L));
        when(places.findById(42L)).thenReturn(java.util.Optional.of(existing));

        Place returned = service.mark(7L, request(), "k1");

        assertThat(returned).isSameAs(existing);
        verify(places, never()).save(any());
        // 重放不该再过一次审核（那是一次三方调用 + 一次计费）。
        verify(moderation, never()).evaluate(anyString(), anyList());
    }

    /** 幂等键指向的资源不在了（TTL 内被运营下架并物删）→ 404 而不是静默新建一条。 */
    @Test
    void replayPointingAtAMissingPlaceIsNotFound() {
        when(idempotency.findResourceId("k1")).thenReturn(java.util.Optional.of(42L));
        when(places.findById(42L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.mark(7L, request(), "k1"))
                .isInstanceOf(AppException.class);
        verify(places, never()).save(any());
    }

    // ===== Story 1.5 举报（AC5）=====

    @Test
    void reportWritesAPendingTicket() {
        Place p = place();
        when(places.findByPublicTokenAndStatus("tok", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(p));
        when(reports.existsByPlaceIdAndReporterId(anyLong(), anyLong())).thenReturn(false);

        service.report("tok", 9L, com.tailtopia.moderation.domain.ReportReason.INAPPROPRIATE);

        ArgumentCaptor<com.tailtopia.place.domain.PlaceReport> saved =
                ArgumentCaptor.forClass(com.tailtopia.place.domain.PlaceReport.class);
        verify(reports).save(saved.capture());
        assertThat(saved.getValue().getReporterId()).isEqualTo(9L);
        assertThat(saved.getValue().getReasonType())
                .isEqualTo(com.tailtopia.moderation.domain.ReportReason.INAPPROPRIATE);
        assertThat(saved.getValue().getStatus())
                .as("写工单 PENDING 进运营队列，**不自动下架**")
                .isEqualTo(com.tailtopia.moderation.domain.ReportStatus.PENDING);
    }

    /**
     * 🔴 重复举报**幂等**：连点五次 → 队列里只有一条。
     *
     * <p>报错也不行 —— 用户会以为"没举报成功"再点一次。
     */
    @Test
    void reportingTwiceIsIdempotentAndDoesNotThrow() {
        Place p = place();
        when(places.findByPublicTokenAndStatus("tok", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(p));
        when(reports.existsByPlaceIdAndReporterId(anyLong(), anyLong())).thenReturn(true);

        service.report("tok", 9L, com.tailtopia.moderation.domain.ReportReason.OTHER);

        verify(reports, never()).save(any());
    }

    /**
     * 🔴 **并发撞唯一约束也必须幂等**（code-review 2026-09-15）。
     *
     * <p>`existsBy` 预查挡不住并发：两次点击同时过了预查，后到的那条撞
     * `uq_place_reports_reporter_place`。让 `DataIntegrityViolationException` 冒出去
     * = 500 =「举报失败」，正好是上一条用例要避免的那个结果。
     */
    @Test
    void concurrentDuplicateReportIsSwallowedInsteadOf500() {
        Place p = place();
        when(places.findByPublicTokenAndStatus("tok", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(p));
        when(reports.existsByPlaceIdAndReporterId(anyLong(), anyLong())).thenReturn(false);
        when(reports.save(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("uq"));

        // 不抛 = 用户看到的是成功（队列里已经有那条工单了）。
        service.report("tok", 9L, com.tailtopia.moderation.domain.ReportReason.OTHER);
    }

    /** 举报一个已下架 / 不存在的场所 → 404（与详情同口径，不泄漏 token 曾存在）。 */
    @Test
    void reportingAMissingPlaceIsNotFound() {
        when(places.findByPublicTokenAndStatus("gone", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.report("gone", 9L,
                com.tailtopia.moderation.domain.ReportReason.OTHER))
                .isInstanceOf(AppException.class);
        verify(reports, never()).save(any());
    }

    private static Place place() {
        return withId(Place.mark("t".repeat(32), "X", PlaceType.CAFE, List.of(PlaceTag.PET_MENU),
                -6.2, 106.8, "Jl. X", null, 7L), 42L);
    }

    /**
     * 给一个未持久化的实体塞上 id。
     *
     * <p>`Place` 的 id 由 JPA 在 save 时赋值、没有 setter（刻意的：它不是业务可写的字段）。
     * 而举报路径拿的是**从库里取出来的**实体，那时 id 必然非空 —— 所以这里用反射补上，
     * 而不是为了测试在生产代码里开一个 setter。
     */
    private static Place withId(Place p, long id) {
        try {
            var f = Place.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
            return p;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Place.id 字段名变了，改这里", e);
        }
    }

    /** 每次标记都生成一个新 token（不可枚举、不由 id 派生）。 */
    @Test
    void eachPlaceGetsItsOwnUnpredictableToken() {
        verdict(ModerationOutcome.pass(0.1, null));

        String a = service.mark(1L, request(), null).getPublicToken();
        String b = service.mark(1L, request(), null).getPublicToken();

        assertThat(a).isNotEqualTo(b).hasSize(32).matches("[0-9a-zA-Z]{32}");
    }
}
