package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlacePhoto;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.dto.PlacePhotoContributeRequest;
import com.tailtopia.place.event.PlacePhotosSubmittedEvent;
import com.tailtopia.place.repository.PlacePhotoRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * L0（mock 仓储 + mock 审核）：他人为场所补充照片
 * （V1.3.0 batch-b1 Story 1.9 · AC1/AC2/AC3）。
 *
 * <p>守的是三件出错了界面也看不出来的事：
 * ① **谁都能补**（加一条"只有标记人能加"的判断会把场所变成标记人的私产）；
 * ② 补充的照片落**挂起**（直接可见 = 未经审核的图片对全世界公开）；
 * ③ 上传者归属正确（AC2 要标注他，写错了就是标注了别人）。
 */
class PlacePhotoServiceTest {

    private PlaceRepository places;
    private PlacePhotoRepository photos;
    private ApplicationEventPublisher events;
    private PlacePhotoService service;

    @BeforeEach
    void setUp() {
        places = Mockito.mock(PlaceRepository.class);
        photos = Mockito.mock(PlacePhotoRepository.class);
        events = Mockito.mock(ApplicationEventPublisher.class);
        service = new PlacePhotoService(places, photos, events);
        when(photos.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 7L));
        when(places.findByPublicTokenAndStatus("tok", PlaceStatus.ACTIVE))
                .thenReturn(Optional.of(withPlaceId(place(), 42L)));
    }

    // ===== AC1 谁都能补 =====

    /**
     * 🔴 **非标记人也能补**（AC1）。
     *
     * <p>场所是共享的地点条目 —— 标记人连场所本身都改不了。加一条"只有标记人能加照片"的判断，
     * 就等于把场所变成了他的私产，与 2026-09-15 那条决策直接冲突。
     * 这里用「标记人是 1L，补充者是 9L」把这条钉死。
     */
    @Test
    void anyoneCanContributeNotJustThePlaceMarker() {
        List<PlacePhoto> saved = service.contribute("tok", 9L,
                new PlacePhotoContributeRequest(List.of("https://cdn/new.jpg")));

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getUploaderId())
                .as("AC2：标注的是**补充者**，不是标记人")
                .isEqualTo(9L);
    }

    // ===== AC3 先发后审 =====

    @Test
    void contributedPhotosLandPendingNotVisible() {
        ArgumentCaptor<PlacePhoto> saved = ArgumentCaptor.forClass(PlacePhoto.class);

        service.contribute("tok", 9L,
                new PlacePhotoContributeRequest(List.of("https://cdn/new.jpg")));

        verify(photos).save(saved.capture());
        assertThat(saved.getValue().getModerationStatus())
                .as("🔴 直接落 VISIBLE = 未经审核的图片对全世界公开")
                .isEqualTo(CommentModerationStatus.UNDER_REVIEW);
    }

    @Test
    void contributionPublishesOneBatchModerationEvent() {
        ArgumentCaptor<PlacePhotosSubmittedEvent> ev =
                ArgumentCaptor.forClass(PlacePhotosSubmittedEvent.class);

        service.contribute("tok", 9L, new PlacePhotoContributeRequest(
                List.of("https://cdn/a.jpg", "https://cdn/b.jpg", "https://cdn/c.jpg")));

        // 🔴 一次提交一个事件（批量送审），不是每张一个 —— 那是 3 倍配额与 3 倍延迟。
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().urls()).hasSize(3);
        assertThat(ev.getValue().photoIds()).hasSize(3);
    }

    /** 标记时提交的那批**已经过了同步富审核** → 直接可见，不再走一次异步。 */
    @Test
    void photosFromMarkingAreVisibleImmediately() {
        ArgumentCaptor<PlacePhoto> saved = ArgumentCaptor.forClass(PlacePhoto.class);

        service.storeInitialPhotos(42L, 7L, List.of("https://cdn/a.jpg", "https://cdn/b.jpg"));

        verify(photos, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(p ->
                assertThat(p.getModerationStatus()).isEqualTo(CommentModerationStatus.VISIBLE));
        // 顺序 0,1 —— 首图永远是标记人第一张（AD-5 的 OG 预览图也取首图）。
        assertThat(saved.getAllValues().stream().map(PlacePhoto::getSortOrder)).containsExactly(0, 1);
        verify(events, never()).publishEvent(any(PlacePhotosSubmittedEvent.class));
    }

    /** 补充的照片排在现有照片**之后** —— 否则别人一补图就把标记人的首图挤掉了。 */
    @Test
    void contributedPhotosAreOrderedAfterExistingOnes() {
        when(photos.maxSortOrder(42L)).thenReturn(4);
        ArgumentCaptor<PlacePhoto> saved = ArgumentCaptor.forClass(PlacePhoto.class);

        service.contribute("tok", 9L, new PlacePhotoContributeRequest(
                List.of("https://cdn/x.jpg", "https://cdn/y.jpg")));

        verify(photos, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().stream().map(PlacePhoto::getSortOrder))
                .containsExactly(5, 6);
    }

    // ===== 上限 =====

    /** 🔴 上限按**整个场所**算，不是按人算：按人算的话十个人各传 9 张 = 90 张图的横滑流。 */
    @Test
    void theNinePhotoCapIsPerPlaceNotPerUser() {
        when(photos.countOccupyingSlots(42L)).thenReturn(8L);

        assertThatThrownBy(() -> service.contribute("tok", 9L,
                new PlacePhotoContributeRequest(List.of("https://cdn/a.jpg", "https://cdn/b.jpg"))))
                .isInstanceOf(AppException.class);
        verify(photos, never()).save(any());
    }

    @Test
    void contributingUpToTheCapIsAllowed() {
        when(photos.countOccupyingSlots(42L)).thenReturn(8L);

        assertThat(service.contribute("tok", 9L,
                new PlacePhotoContributeRequest(List.of("https://cdn/a.jpg")))).hasSize(1);
    }

    @Test
    void contributingToAMissingOrTakenDownPlaceIsNotFound() {
        when(places.findByPublicTokenAndStatus("gone", PlaceStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.contribute("gone", 9L,
                new PlacePhotoContributeRequest(List.of("https://cdn/a.jpg"))))
                .isInstanceOf(AppException.class);
    }

    // ===== 删除 =====

    @Test
    void uploaderCanDeleteOwnPhoto() {
        PlacePhoto p = withId(PlacePhoto.contributed(42L, 9L, "https://cdn/a.jpg", 1), 7L);
        when(photos.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(p));

        service.deleteOwn(7L, 9L);

        assertThat(p.getDeletedAt()).isNotNull();
    }

    /**
     * 🔴 **不许删到零张**：标记场所时照片是必填的（1–9 张），而场所不可编辑、不可删除 ——
     * 一张张删光就留下一个永远没有照片、谁也补不回原样的条目（列表首图与 OG 预览图一起没了）。
     */
    @Test
    void theLastVisiblePhotoCannotBeDeleted() {
        PlacePhoto only = withId(PlacePhoto.fromMarking(42L, 9L, "https://cdn/a.jpg", 0), 7L);
        when(photos.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(only));
        when(photos.countVisible(42L)).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteOwn(7L, 9L)).isInstanceOf(AppException.class);
        assertThat(only.getDeletedAt()).isNull();
    }

    /** 但删一张**待审**的图不受这条限制 —— 它本来就不在对外的张数里。 */
    @Test
    void deletingAPendingPhotoIsAllowedEvenWhenItIsTheOnlyVisibleOne() {
        PlacePhoto pending = withId(PlacePhoto.contributed(42L, 9L, "https://cdn/a.jpg", 1), 7L);
        when(photos.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(pending));
        when(photos.countVisible(42L)).thenReturn(1L);

        service.deleteOwn(7L, 9L);

        assertThat(pending.getDeletedAt()).isNotNull();
    }

    /**
     * 🔴 上限只数**占着位置**的行：被判死 / 上传者注销的照片谁都看不见，
     * 算进上限会让一个界面上只有几张图的场所永远加不进新图，而用户腾不出位置。
     */
    @Test
    void invisibleRowsDoNotOccupySlots() {
        when(photos.countOccupyingSlots(42L)).thenReturn(4L);

        assertThat(service.contribute("tok", 9L, new PlacePhotoContributeRequest(
                List.of("https://cdn/a.jpg", "https://cdn/b.jpg")))).hasSize(2);
    }

    /** 🔴 **标记人也不能删别人补的照片** —— 同「标记人不能删别人的评论」：场所没有主人。 */
    @Test
    void neitherStrangersNorThePlaceMarkerCanDeleteSomeoneElsesPhoto() {
        PlacePhoto p = withId(PlacePhoto.contributed(42L, 9L, "https://cdn/a.jpg", 1), 7L);
        when(photos.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.deleteOwn(7L, 999L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.deleteOwn(7L, 1L)) // 1L 是 place() 的标记人
                .isInstanceOf(AppException.class);
        assertThat(p.getDeletedAt()).isNull();
    }

    // ===== 审核回调 =====

    @Test
    void approveMakesAPendingPhotoVisible() {
        PlacePhoto p = withId(PlacePhoto.contributed(42L, 9L, "https://cdn/a.jpg", 1), 7L);
        when(photos.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(p));

        service.approve(7L);

        assertThat(p.isVisible()).isTrue();
    }

    @Test
    void rejectIsTerminalAndOnlyAppliesToPendingPhotos() {
        PlacePhoto pending = withId(PlacePhoto.contributed(42L, 9L, "https://cdn/a.jpg", 1), 7L);
        when(photos.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(pending));

        service.reject(7L);

        assertThat(pending.getModerationStatus()).isEqualTo(CommentModerationStatus.REJECTED);

        // 已可见的照片不该被这条路径打成 REJECTED（那是运营下架该做的事）。
        PlacePhoto visible = withId(PlacePhoto.fromMarking(42L, 7L, "https://cdn/b.jpg", 0), 8L);
        when(photos.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(visible));
        service.reject(8L);
        assertThat(visible.isVisible()).isTrue();
    }

    /**
     * 🔴 判定映射：**确定性违规可以判死，"不知道"不行**。
     *
     * <p>这一条是场所评论那边没有的区分 —— 文本审核把"高危"和"降级"混在一个判定里、分不开，
     * 图审这里能分开。
     */
    @Test
    void definitelyBlockedIsTrueOnlyForDeterministicVerdicts() {
        assertThat(PlacePhotoService.isDefinitelyBlocked(ModerationOutcome.imageBlocked("PORN")))
                .isTrue();
        assertThat(PlacePhotoService.isDefinitelyBlocked(ModerationOutcome.textBlocked("PORN")))
                .isTrue();
        assertThat(PlacePhotoService.isDefinitelyBlocked(
                ModerationOutcome.degraded(com.tailtopia.content.moderation.DegradeReason.TIMEOUT)))
                .as("🔴 \"不知道\" 不能判死一张可能完全正常的照片")
                .isFalse();
        assertThat(PlacePhotoService.isDefinitelyBlocked(ModerationOutcome.risky(0.9, "SPAM")))
                .isFalse();
        assertThat(PlacePhotoService.isDefinitelyBlocked(ModerationOutcome.pass(0.1, null)))
                .isFalse();
    }

    // ===== 注销级联 =====

    /**
     * 🔴 注销时**只隐藏他补充的那些**，标记人自己那批不动。
     *
     * <p>那批是场所条目本身的资料（首图 / OG 预览图都取它）——
     * 随人一起隐藏会把整个场所变成无图条目。
     */
    @Test
    void deactivationHidesContributedPhotosButKeepsTheOriginalBatch() {
        PlacePhoto original = withId(PlacePhoto.fromMarking(42L, 1L, "https://cdn/a.jpg", 0), 1L);
        PlacePhoto contributed = withId(PlacePhoto.contributed(42L, 9L, "https://cdn/b.jpg", 5), 2L);
        contributed.approveModeration();
        when(photos.findByUploaderIdAndDeletedAtIsNull(1L)).thenReturn(List.of(original));
        when(photos.findByUploaderIdAndDeletedAtIsNull(9L)).thenReturn(List.of(contributed));

        assertThat(service.deactivateUploaderPhotos(1L))
                .as("标记时提交的那批**不隐藏**（否则场所会变成无图条目）")
                .isZero();
        assertThat(original.isVisible()).isTrue();

        assertThat(service.deactivateUploaderPhotos(9L)).isEqualTo(1);
        assertThat(contributed.getModerationStatus())
                .isEqualTo(CommentModerationStatus.AUTHOR_DEACTIVATED);
    }

    /**
     * 🔴 豁免判据是 `is_original`，**不是"上传者是不是标记人"**（code-review 2026-09-15）。
     *
     * <p>标记人事后给自己标的场所补图 —— 那些是补充照片，注销时该隐藏。
     * 按人判会把它们一起豁免掉。
     */
    @Test
    void thePlaceMarkersLaterContributionsAreStillHiddenOnDeactivation() {
        PlacePhoto later = withId(PlacePhoto.contributed(42L, 1L, "https://cdn/c.jpg", 7), 3L);
        later.approveModeration();
        when(photos.findByUploaderIdAndDeletedAtIsNull(1L)).thenReturn(List.of(later));

        assertThat(service.deactivateUploaderPhotos(1L)).isEqualTo(1);
        assertThat(later.getModerationStatus())
                .isEqualTo(CommentModerationStatus.AUTHOR_DEACTIVATED);
    }

    private static Place place() {
        return Place.mark("tok", "Kopi", PlaceType.CAFE, List.of(PlaceTag.PET_MENU),
                -6.2, 106.8, "Jl. X", null, 1L);
    }

    private static Place withPlaceId(Place p, long id) {
        return setId(p, Place.class, id);
    }

    private static PlacePhoto withId(PlacePhoto p, long id) {
        return setId(p, PlacePhoto.class, id);
    }

    private static <T> T setId(T target, Class<?> type, long id) {
        try {
            var f = type.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
            return target;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + ".id 字段名变了，改这里", e);
        }
    }
}
