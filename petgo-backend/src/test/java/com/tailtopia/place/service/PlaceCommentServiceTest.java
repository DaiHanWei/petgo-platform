package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceComment;
import com.tailtopia.place.domain.PlaceCommentAttitude;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.dto.PlaceCommentCreateRequest;
import com.tailtopia.place.event.PlaceCommentSubmittedEvent;
import com.tailtopia.place.repository.PlaceCommentRepository;
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
 * L0（mock 仓储，无 DB / 无三方）：场所评论的写入与删除
 * （V1.3.0 batch-b1 Story 1.7 · AC2/AC3/AC5/AC7）。
 *
 * <p>这组守的是三件**出错了界面也看不出来**的事：
 * ① 先发后审的初始态必须是 UNDER_REVIEW（落 VISIBLE = 未审核内容直接公开）；
 * ② 删除必须校验本人（少一行 = 传个 id 就能删别人的评论）；
 * ③ 态度可空、且不能"只表态不写评论"。
 */
class PlaceCommentServiceTest {

    private PlaceCommentRepository comments;
    private PlaceRepository places;
    private ContentModerationService moderation;
    private ApplicationEventPublisher events;
    private PlaceAttitudeCounters counters;
    private PlaceCommentService service;

    @BeforeEach
    void setUp() {
        comments = Mockito.mock(PlaceCommentRepository.class);
        places = Mockito.mock(PlaceRepository.class);
        moderation = Mockito.mock(ContentModerationService.class);
        events = Mockito.mock(ApplicationEventPublisher.class);
        counters = Mockito.mock(PlaceAttitudeCounters.class);
        service = new PlaceCommentService(comments, places, moderation, events, counters);
        // save 之后 id 一定不为空（JPA @GeneratedValue）—— 审核事件要用它。
        when(comments.save(any())).thenAnswer(inv ->
                withCommentId(inv.getArgument(0), 7L));
        when(places.findByPublicTokenAndStatus("tok", PlaceStatus.ACTIVE))
                .thenReturn(Optional.of(withId(place(), 42L)));
    }

    // ===== AC5 先发后审 =====

    @Test
    void newCommentLandsUnderReviewAndPublishesModerationEvent() {
        when(moderation.isL1Blocked(anyString())).thenReturn(false);

        service.create("tok", 9L, new PlaceCommentCreateRequest("Enak buat kerja", null));

        ArgumentCaptor<PlaceComment> saved = ArgumentCaptor.forClass(PlaceComment.class);
        verify(comments).save(saved.capture());
        assertThat(saved.getValue().getModerationStatus())
                .as("🔴 落 VISIBLE = 未经三方审核的文本直接对全世界可见（先发后审的前半截没了）")
                .isEqualTo(CommentModerationStatus.UNDER_REVIEW);
        assertThat(saved.getValue().getPlaceId()).isEqualTo(42L);
        assertThat(saved.getValue().getAuthorId()).isEqualTo(9L);
        verify(events).publishEvent(any(PlaceCommentSubmittedEvent.class));
    }

    @Test
    void l1BlockedIsRejectedSynchronouslyAndNeverStored() {
        when(moderation.isL1Blocked(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.create("tok", 9L,
                new PlaceCommentCreateRequest("...", null)))
                .isInstanceOf(AppException.class);

        verify(comments, never()).save(any());
        verify(events, never()).publishEvent(any(PlaceCommentSubmittedEvent.class));
    }

    // ===== AC3 可选态度 =====

    @Test
    void attitudeIsOptional() {
        when(moderation.isL1Blocked(anyString())).thenReturn(false);

        service.create("tok", 9L, new PlaceCommentCreateRequest("biasa aja", null));

        ArgumentCaptor<PlaceComment> saved = ArgumentCaptor.forClass(PlaceComment.class);
        verify(comments).save(saved.capture());
        assertThat(saved.getValue().getAttitude())
                .as("不表态就是 null —— 不要兜底成某一边")
                .isNull();
    }

    @Test
    void attitudeIsStoredWhenGiven() {
        when(moderation.isL1Blocked(anyString())).thenReturn(false);

        service.create("tok", 9L,
                new PlaceCommentCreateRequest("mantap", PlaceCommentAttitude.NOT_RECOMMEND));

        ArgumentCaptor<PlaceComment> saved = ArgumentCaptor.forClass(PlaceComment.class);
        verify(comments).save(saved.capture());
        assertThat(saved.getValue().getAttitude()).isEqualTo(PlaceCommentAttitude.NOT_RECOMMEND);
    }

    /** 🔴 B1-D3：不写评论就不能表态 —— 空正文即使带着态度也必须被拒。 */
    @Test
    void attitudeAloneWithoutBodyIsRejected() {
        assertThatThrownBy(() -> service.create("tok", 9L,
                new PlaceCommentCreateRequest("   ", PlaceCommentAttitude.RECOMMEND)))
                .isInstanceOf(AppException.class);
        verify(comments, never()).save(any());
    }

    @Test
    void commentingOnAMissingOrTakenDownPlaceIsNotFound() {
        when(places.findByPublicTokenAndStatus("gone", PlaceStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("gone", 9L,
                new PlaceCommentCreateRequest("halo", null)))
                .isInstanceOf(AppException.class);
        verify(comments, never()).save(any());
    }

    // ===== AC7 用户自删 =====

    @Test
    void authorCanDeleteOwnComment() {
        PlaceComment c = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo", null), 7L);
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        service.deleteOwn(7L, 9L);

        assertThat(c.isDeleted()).isTrue();
        verify(comments).save(c);
    }

    /**
     * 🔴 AC7 的那半句「不得传个评论 id 就能删」。
     *
     * <p>少这一行校验，界面上什么都看不出来 —— 删除按钮本来就只画给自己看，
     * 但接口是公开的，谁都能直接调。
     */
    @Test
    void otherUsersCannotDeleteSomeoneElsesComment() {
        PlaceComment c = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo", null), 7L);
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.deleteOwn(7L, 999L)).isInstanceOf(AppException.class);

        assertThat(c.isDeleted()).isFalse();
        verify(comments, never()).save(any());
    }

    /**
     * 🔴 **场所标记人也不能删别人的评论** —— 与内容评论有意不同。
     *
     * <p>内容评论允许「内容作者」删（他的地盘），而场所没有主人：标记人连场所本身都改不了。
     * 这里用「标记人 = 1L」的场所 + 「标记人来删 9L 的评论」把这条钉死。
     */
    @Test
    void placeMarkerCannotDeleteOthersComments() {
        PlaceComment c = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo", null), 7L);
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        // 1L 是 place() 的 createdBy（标记人）。
        assertThatThrownBy(() -> service.deleteOwn(7L, 1L)).isInstanceOf(AppException.class);
        assertThat(c.isDeleted()).isFalse();
    }

    @Test
    void deletingAMissingCommentIsNotFound() {
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteOwn(7L, 9L)).isInstanceOf(AppException.class);
    }

    // ===== Story 1.8 计数触发点（AC5）=====

    /**
     * 🔴 **创建那一刻不计数**：先发后审之下这条还是 UNDER_REVIEW、对他人不可见。
     * 创建即 +1 会让一条尚未过审、甚至最终被拒的评论立刻出现在公开计数里。
     */
    @Test
    void creatingACommentDoesNotBumpTheCounterYet() {
        when(moderation.isL1Blocked(anyString())).thenReturn(false);

        service.create("tok", 9L,
                new PlaceCommentCreateRequest("mantap", PlaceCommentAttitude.RECOMMEND));

        verify(counters, never()).onCommentBecameVisible(anyLong(), any());
    }

    /** 触发点 ①：评论**转为可见**那一刻才 +1。 */
    @Test
    void approvingACommentBumpsTheCounter() {
        PlaceComment c = withCommentId(PlaceComment.createUnderReview(
                42L, 9L, "mantap", PlaceCommentAttitude.RECOMMEND), 7L);
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        service.approve(7L);

        verify(counters).onCommentBecameVisible(42L, PlaceCommentAttitude.RECOMMEND);
    }

    /** 幂等：重复 approve 不重复加。 */
    @Test
    void approvingTwiceBumpsOnlyOnce() {
        PlaceComment c = withCommentId(PlaceComment.createUnderReview(
                42L, 9L, "mantap", PlaceCommentAttitude.RECOMMEND), 7L);
        c.approveModeration();
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        service.approve(7L);

        verify(counters, never()).onCommentBecameVisible(anyLong(), any());
    }

    /** 触发点 ②：用户自删一条**已可见**的评论 → −1。 */
    @Test
    void deletingAVisibleCommentDecrementsTheCounter() {
        PlaceComment c = withCommentId(PlaceComment.createUnderReview(
                42L, 9L, "mantap", PlaceCommentAttitude.NOT_RECOMMEND), 7L);
        c.approveModeration();
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        service.deleteOwn(7L, 9L);

        verify(counters).onVisibleCommentRemoved(42L, PlaceCommentAttitude.NOT_RECOMMEND);
    }

    /**
     * 🔴 删一条**还没过审**的评论**不减** —— 它从来没被加进去过。
     * 减了会把这个场所的数字一直压低，直到下一次自愈。
     */
    @Test
    void deletingAPendingCommentDoesNotDecrement() {
        PlaceComment c = withCommentId(PlaceComment.createUnderReview(
                42L, 9L, "mantap", PlaceCommentAttitude.RECOMMEND), 7L);
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        service.deleteOwn(7L, 9L);

        verify(counters, never()).onVisibleCommentRemoved(anyLong(), any());
    }

    /** 触发点 ③：运营下架 → −1；幂等（非 VISIBLE 不动）。 */
    @Test
    void takedownDecrementsOnceAndIsIdempotent() {
        PlaceComment c = withCommentId(PlaceComment.createUnderReview(
                42L, 9L, "mantap", PlaceCommentAttitude.RECOMMEND), 7L);
        c.approveModeration();
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        assertThat(service.takedown(7L)).isTrue();
        assertThat(service.takedown(7L)).as("已下架 → no-op").isFalse();

        verify(counters).onVisibleCommentRemoved(42L, PlaceCommentAttitude.RECOMMEND);
    }

    /** 注销一条已可见的评论 → 它对他人不可见了，计数也要跟着减。 */
    @Test
    void deactivatingAuthorAlsoRemovesVisibleCommentsFromTheCounters() {
        PlaceComment c = withCommentId(PlaceComment.createUnderReview(
                42L, 9L, "mantap", PlaceCommentAttitude.RECOMMEND), 7L);
        c.approveModeration();
        when(comments.findByAuthorIdAndDeletedAtIsNull(9L)).thenReturn(List.of(c));

        service.deactivateAuthorComments(9L);

        verify(counters).onVisibleCommentRemoved(42L, PlaceCommentAttitude.RECOMMEND);
    }

    // ===== 注销级联（NFR-8 / D1/D2，安全攸关）=====

    /**
     * 🔴 注销后场所评论必须对**他人**不可见。
     *
     * <p>场所评论是独立表（AD-8），content 那条级联碰不到它 —— 漏掉的结果是
     * 一个已注销用户的评论继续挂着他的身份对所有人公开（code-review 2026-09-15）。
     */
    @Test
    void deactivatingAnAccountHidesItsPlaceCommentsFromOthers() {
        PlaceComment a = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo", null), 1L);
        a.approveModeration(); // 已过审、正对外可见的那种
        PlaceComment b = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo lagi", null), 2L);
        when(comments.findByAuthorIdAndDeletedAtIsNull(9L)).thenReturn(List.of(a, b));

        assertThat(service.deactivateAuthorComments(9L)).isEqualTo(2);

        assertThat(a.getModerationStatus())
                .isEqualTo(CommentModerationStatus.AUTHOR_DEACTIVATED);
        assertThat(b.getModerationStatus())
                .isEqualTo(CommentModerationStatus.AUTHOR_DEACTIVATED);
        assertThat(a.isDeleted())
                .as("⚠️ 是隐藏身份，不是删内容：评论本身是场所攻略的一部分")
                .isFalse();
    }

    /** 幂等可重跑（注销作业失败会重扫续跑）。 */
    @Test
    void deactivatingTwiceChangesNothingTheSecondTime() {
        PlaceComment a = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo", null), 1L);
        a.deactivateAuthor();
        when(comments.findByAuthorIdAndDeletedAtIsNull(9L)).thenReturn(List.of(a));

        assertThat(service.deactivateAuthorComments(9L)).isZero();
        verify(comments, never()).save(any());
    }

    // ===== 审核回调 =====

    @Test
    void approveFlipsUnderReviewToVisible() {
        PlaceComment c = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo", null), 7L);
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        service.approve(7L);

        assertThat(c.getModerationStatus()).isEqualTo(CommentModerationStatus.VISIBLE);
        verify(comments).save(c);
    }

    /** 幂等：已经 VISIBLE 再 approve 一次不写库（重复事件/重放都可能发生）。 */
    @Test
    void approveIsIdempotent() {
        PlaceComment c = withCommentId(
                PlaceComment.createUnderReview(42L, 9L, "halo", null), 7L);
        c.approveModeration();
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(c));

        service.approve(7L);

        verify(comments, never()).save(any());
    }

    /** 已删的评论再收到审核回调 → 什么都不做（不能把一条已删评论"审核通过"回来）。 */
    @Test
    void approveOnDeletedCommentDoesNothing() {
        when(comments.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.empty());
        service.approve(7L);
        verify(comments, never()).save(any());
    }

    private static Place place() {
        return Place.mark("tok", "Kopi", PlaceType.CAFE, List.of(PlaceTag.PET_MENU),
                -6.2, 106.8, "Jl. X", null, List.of("https://cdn/a.jpg"), 1L);
    }

    /** 未持久化实体塞 id（id 由 JPA 赋值、无 setter —— 不为测试在生产代码里开口子）。 */
    private static Place withId(Place p, long id) {
        return setField(p, Place.class, id);
    }

    private static PlaceComment withCommentId(PlaceComment c, long id) {
        return setField(c, PlaceComment.class, id);
    }

    private static <T> T setField(T target, Class<?> type, long id) {
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
