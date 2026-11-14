package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.autocomment.VirtualIdentityPicker;
import com.tailtopia.content.autolike.AutoLikeDispatcher.DueRow;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.LikeService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.content.species.ResolvedSpecies;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** L0 · V1.5.0 likes Story 2.3：认领、六种结果的日志 / 目标集写法、失败小事务与叫停撤回、号池空、开关关。 */
class AutoLikeDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private static final long POST = 500L;
    private static final long AUTHOR = 7L;
    private static final DueRow ROW = new DueRow(11L, 3L, POST);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
    private final AutoLikeProperties props = new AutoLikeProperties();
    private final AutoLikeVirtualPool pool = mock(AutoLikeVirtualPool.class);
    private final ContentPostRepository posts = mock(ContentPostRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ContentLikeRepository likes = mock(ContentLikeRepository.class);
    private final ContentSpeciesResolver species = mock(ContentSpeciesResolver.class);
    private final VirtualIdentityPicker picker = mock(VirtualIdentityPicker.class);
    private final LikeService likeService = mock(LikeService.class);
    private AutoLikeDispatcher dispatcher;

    private final User v1 = user(101L, AccountType.VIRTUAL, Role.USER, UserStatus.ACTIVE);
    private final User v2 = user(102L, AccountType.VIRTUAL, Role.USER, UserStatus.ACTIVE);

    @BeforeEach
    void setUp() {
        when(txm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        dispatcher = new AutoLikeDispatcher(jdbc, txm, mock(StringRedisTemplate.class), props, pool, posts, users,
                likes, species, picker, likeService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(species.resolve(any(ContentPost.class))).thenReturn(ResolvedSpecies.NONE);
        when(jdbc.update(eq(AutoLikeDispatcher.CLAIM_SQL), anyMap())).thenReturn(1);
    }

    private static User user(long id, AccountType type, Role role, UserStatus status) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getAccountType()).thenReturn(type);
        when(u.getRole()).thenReturn(role);
        when(u.getStatus()).thenReturn(status);
        return u;
    }

    private ContentPost post(PostStatus status) {
        ContentPost p = ContentPost.publish(AUTHOR, ContentType.DAILY, null, "x", null);
        try {
            var id = ContentPost.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(p, POST);
            var st = ContentPost.class.getDeclaredField("status");
            st.setAccessible(true);
            st.set(p, status);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        when(posts.findById(POST)).thenReturn(Optional.of(p));
        return p;
    }

    private void realAuthor(UserStatus status) {
        User a = user(AUTHOR, AccountType.REAL, Role.USER, status);
        when(users.findById(AUTHOR)).thenReturn(Optional.of(a));
    }

    private SqlParameterSource finished() {
        ArgumentCaptor<SqlParameterSource> c = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(AutoLikeDispatcher.FINISH_SQL), c.capture());
        return c.getValue();
    }

    private void verifyLeft(AutoLikeLeaveReason reason) {
        ArgumentCaptor<SqlParameterSource> c = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(AutoLikeDispatcher.LEAVE_TARGET_SQL), c.capture());
        assertThat(c.getValue().getValue("reason")).isEqualTo(reason.name());
    }

    private void verifyNotLeft() {
        verify(jdbc, never()).update(eq(AutoLikeDispatcher.LEAVE_TARGET_SQL), any(SqlParameterSource.class));
    }

    @Test
    void claimedByOtherInstanceDoesNothing() {
        when(jdbc.update(eq(AutoLikeDispatcher.CLAIM_SQL), anyMap())).thenReturn(0);
        assertThat(dispatcher.processOne(ROW, List.of(v1), NOW)).isNull();
        verify(posts, never()).findById(anyLong());
        verify(jdbc, never()).update(eq(AutoLikeDispatcher.FINISH_SQL), any(SqlParameterSource.class));
    }

    @Test
    void missingPostIsGone() {
        when(posts.findById(POST)).thenReturn(Optional.empty());
        assertThat(dispatcher.processOne(ROW, List.of(v1), NOW)).isEqualTo(AutoLikeLogStatus.POST_GONE);
        assertThat(finished().getValue("status")).isEqualTo("POST_GONE");
        verifyLeft(AutoLikeLeaveReason.POST_GONE);
        verify(likeService, never()).likeAutomated(anyLong(), anyLong(), anyLong());
    }

    @Test
    void deactivatedAuthorIsGone() {
        post(PostStatus.PUBLISHED);
        realAuthor(UserStatus.DEACTIVATED);
        assertThat(dispatcher.processOne(ROW, List.of(v1), NOW)).isEqualTo(AutoLikeLogStatus.POST_GONE);
        verifyLeft(AutoLikeLeaveReason.POST_GONE);
    }

    @Test
    void underReviewIsSkippedAndStays() {
        post(PostStatus.UNDER_REVIEW);
        realAuthor(UserStatus.ACTIVE);
        assertThat(dispatcher.processOne(ROW, List.of(v1), NOW)).isEqualTo(AutoLikeLogStatus.SKIPPED_REVIEW);
        assertThat(finished().getValue("status")).isEqualTo("SKIPPED_REVIEW");
        verifyNotLeft();
    }

    @Test
    void reachedTargetBeforeLikeLeaves() {
        post(PostStatus.PUBLISHED);
        realAuthor(UserStatus.ACTIVE);
        when(likes.countByPostId(POST)).thenReturn(5L);
        assertThat(dispatcher.processOne(ROW, List.of(v1), NOW)).isEqualTo(AutoLikeLogStatus.REACHED_TARGET);
        SqlParameterSource f = finished();
        assertThat(f.getValue("status")).isEqualTo("REACHED_TARGET");
        assertThat(f.getValue("likeCountBefore")).isEqualTo(5);
        verifyLeft(AutoLikeLeaveReason.REACHED_TARGET);
        verify(likeService, never()).likeAutomated(anyLong(), anyLong(), anyLong());
    }

    @Test
    void noIdentityAfterExcludingPriorLikersLeaves() {
        post(PostStatus.PUBLISHED);
        realAuthor(UserStatus.ACTIVE);
        when(likes.countByPostId(POST)).thenReturn(1L);
        when(likes.findLikerIdsAmong(eq(POST), anyCollection())).thenReturn(List.of(101L));
        when(picker.pick(any(), anyLong(), anyString())).thenReturn(null);

        assertThat(dispatcher.processOne(ROW, List.of(v1, v2), NOW)).isEqualTo(AutoLikeLogStatus.NO_IDENTITY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> c = ArgumentCaptor.forClass(List.class);
        verify(picker).pick(c.capture(), eq(AUTHOR), eq("GENERAL"));
        assertThat(c.getValue()).containsExactly(v2);
        verifyLeft(AutoLikeLeaveReason.NO_IDENTITY);
    }

    @Test
    void likedWritesLogAndTouchesTarget() {
        post(PostStatus.PUBLISHED);
        realAuthor(UserStatus.ACTIVE);
        when(likes.countByPostId(POST)).thenReturn(2L);
        when(picker.pick(any(), anyLong(), anyString())).thenReturn(v2);
        when(likeService.likeAutomated(POST, 102L, AUTHOR)).thenReturn(new LikeService.AutomatedLikeResult(true, 3L));

        assertThat(dispatcher.processOne(ROW, List.of(v1, v2), NOW)).isEqualTo(AutoLikeLogStatus.LIKED);

        SqlParameterSource f = finished();
        assertThat(f.getValue("status")).isEqualTo("LIKED");
        assertThat(f.getValue("virtualUserId")).isEqualTo(102L);
        assertThat(f.getValue("likeCountBefore")).isEqualTo(2);
        verify(jdbc).update(eq(AutoLikeDispatcher.TOUCH_LIKED_SQL), anyMap());
        verify(jdbc).update(eq(AutoLikeDispatcher.RESET_CONSECUTIVE_SQL), anyMap());
        verifyNotLeft();
    }

    @Test
    void likeThatReachesTargetLeaves() {
        post(PostStatus.PUBLISHED);
        realAuthor(UserStatus.ACTIVE);
        when(likes.countByPostId(POST)).thenReturn(4L);
        when(picker.pick(any(), anyLong(), anyString())).thenReturn(v1);
        when(likeService.likeAutomated(POST, 101L, AUTHOR)).thenReturn(new LikeService.AutomatedLikeResult(true, 5L));

        assertThat(dispatcher.processOne(ROW, List.of(v1), NOW)).isEqualTo(AutoLikeLogStatus.LIKED);
        verifyLeft(AutoLikeLeaveReason.REACHED_TARGET);
    }

    /** Q-2：竞态下该号已赞 → FAILED/ALREADY_LIKED，不计帖子失败数，帖子留在集合。 */
    @Test
    void alreadyLikedIsRecordedButNotCounted() {
        post(PostStatus.PUBLISHED);
        realAuthor(UserStatus.ACTIVE);
        when(likes.countByPostId(POST)).thenReturn(2L);
        when(picker.pick(any(), anyLong(), anyString())).thenReturn(v1);
        when(likeService.likeAutomated(POST, 101L, AUTHOR)).thenReturn(new LikeService.AutomatedLikeResult(false, 2L));

        assertThat(dispatcher.processOne(ROW, List.of(v1), NOW)).isEqualTo(AutoLikeLogStatus.FAILED);

        SqlParameterSource f = finished();
        assertThat(f.getValue("status")).isEqualTo("FAILED");
        assertThat(f.getValue("errorCode")).isEqualTo("ALREADY_LIKED");
        verify(jdbc, never()).update(eq(AutoLikeDispatcher.FAIL_TARGET_SQL), anyMap());
        verify(jdbc).update(eq(AutoLikeDispatcher.RESET_CONSECUTIVE_SQL), anyMap());
        verifyNotLeft();
    }

    @SuppressWarnings("unchecked")
    private void dueRows(List<DueRow> rows) {
        when(jdbc.query(eq(AutoLikeDispatcher.DUE_SQL), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn((List) rows);
    }

    @Test
    void exceptionGoesToFailureTransactionAndContinues() {
        dueRows(List.of(ROW, new DueRow(12L, 3L, 501L)));
        when(pool.load()).thenReturn(List.of(v1));
        when(posts.findById(anyLong())).thenThrow(new IllegalStateException("boom"));
        when(jdbc.update(eq(AutoLikeDispatcher.FAIL_LOG_SQL), any(SqlParameterSource.class))).thenReturn(1);
        when(jdbc.queryForList(eq(AutoLikeDispatcher.FAIL_RUN_SQL), anyMap(), eq(Integer.class)))
                .thenReturn(List.of(1), List.of(2));

        AutoLikeDispatcher.TickStats s = dispatcher.dispatchOnce();

        assertThat(s.failed()).isEqualTo(2);
        ArgumentCaptor<SqlParameterSource> c = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(eq(AutoLikeDispatcher.FAIL_LOG_SQL), c.capture());
        assertThat(c.getAllValues()).allSatisfy(p -> assertThat(p.getValue("errorCode"))
                .isEqualTo("IllegalStateException"));
        verify(jdbc, org.mockito.Mockito.times(2)).update(eq(AutoLikeDispatcher.FAIL_TARGET_SQL), anyMap());
        verify(jdbc, never()).update(eq(AutoLikeDispatcher.STOP_RUN_FAILURES_SQL), anyMap());
    }

    @Test
    void tenthConsecutiveFailureStopsRunAndUndoesCounts() {
        when(jdbc.update(eq(AutoLikeDispatcher.FAIL_LOG_SQL), any(SqlParameterSource.class))).thenReturn(1);
        when(jdbc.queryForList(eq(AutoLikeDispatcher.FAIL_RUN_SQL), anyMap(), eq(Integer.class)))
                .thenReturn(List.of(10));

        dispatcher.recordFailure(ROW, new IllegalStateException("x"), NOW);

        verify(jdbc).update(eq(AutoLikeDispatcher.STOP_RUN_FAILURES_SQL), anyMap());
        verify(jdbc).update(eq(AutoLikeDispatcher.UNDO_RUN_FAILURES_SQL), anyMap());
        verify(jdbc).update(eq(AutoLikeDispatcher.MISS_RUN_REMAINING_SQL), anyMap());
    }

    /** 所属 run 已非 ACTIVE：日志记 FAILED，但不计帖子失败数。 */
    @Test
    void failureOnStoppedRunDoesNotCountAgainstPost() {
        when(jdbc.update(eq(AutoLikeDispatcher.FAIL_LOG_SQL), any(SqlParameterSource.class))).thenReturn(1);
        when(jdbc.queryForList(eq(AutoLikeDispatcher.FAIL_RUN_SQL), anyMap(), eq(Integer.class))).thenReturn(List.of());
        dispatcher.recordFailure(ROW, new IllegalStateException("x"), NOW);
        verify(jdbc, never()).update(eq(AutoLikeDispatcher.FAIL_TARGET_SQL), anyMap());
    }

    @Test
    void claimRequiresActiveRunAndOpenWindow() {
        assertThat(AutoLikeDispatcher.CLAIM_SQL).contains("l.status = 'PLANNED'").contains("l.window_end > :now")
                .contains("r.status = 'ACTIVE'");
    }

    @Test
    void failureOnAlreadyHandledRowIsNoOp() {
        when(jdbc.update(eq(AutoLikeDispatcher.FAIL_LOG_SQL), any(SqlParameterSource.class))).thenReturn(0);
        dispatcher.recordFailure(ROW, new IllegalStateException("x"), NOW);
        verify(jdbc, never()).update(eq(AutoLikeDispatcher.FAIL_TARGET_SQL), anyMap());
    }

    @Test
    void emptyPoolStopsActiveRunsWithoutTouchingRows() {
        dueRows(List.of(ROW));
        when(pool.load()).thenReturn(List.of());

        AutoLikeDispatcher.TickStats s = dispatcher.dispatchOnce();

        assertThat(s.stoppedNoPool()).isTrue();
        verify(jdbc).update(eq(AutoLikeDispatcher.STOP_ACTIVE_NO_POOL_SQL), anyMap());
        verify(jdbc, never()).update(eq(AutoLikeDispatcher.CLAIM_SQL), anyMap());
    }

    @Test
    void disabledOnlyClosesExpired() {
        props.setEnabled(false);
        dispatcher.dispatchOnce();
        verify(jdbc).update(eq(AutoLikeDispatcher.MISS_EXPIRED_SQL), anyMap());
        verify(jdbc).update(eq(AutoLikeDispatcher.CLOSE_RUNS_SQL), anyMap());
        verify(jdbc, never()).query(eq(AutoLikeDispatcher.DUE_SQL), any(SqlParameterSource.class),
                any(RowMapper.class));
        verify(pool, never()).load();
    }
}
