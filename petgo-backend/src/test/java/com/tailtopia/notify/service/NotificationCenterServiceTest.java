package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.CommentQueryService;
import com.tailtopia.content.service.CommentQueryService.NotificationCommentSnapshot;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationActor;
import com.tailtopia.notify.domain.NotificationActorId;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.dto.NotificationItem;
import com.tailtopia.notify.dto.NotificationPage;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * L0：未读角标 Redis 优先 + 缺值库回算回填；标记已读 token 定位 + 越权 404 + 角标递减。
 *
 * <p>V1.3.4 follow · Story 3.3：互动条目现拼「谁 + 做了什么」—— 模板用真实 {@code i18n/messages*.properties}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationCenterServiceTest {

    @Mock
    NotificationRepository repo;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    NotificationActorRepository actorRepo;
    @Mock
    AccountQueryService accounts;
    @Mock
    UserHideRelationReader hideRelations;
    @Mock
    CommentQueryService comments;
    @Mock
    FollowReader followReader;

    private NotificationCenterService service() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        InteractionCopyRenderer copy = new InteractionCopyRenderer(accounts, hideRelations, source);
        return new NotificationCenterService(repo, redis, actorRepo, accounts, hideRelations, comments,
                followReader, copy);
    }

    @Test
    void unreadCountReadsRedisWhenPresent() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("notify:unread:7")).thenReturn("3");
        assertThat(service().unreadCount(7L)).isEqualTo(3);
    }

    @Test
    void unreadCountRecomputesAndBackfillsWhenRedisMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("notify:unread:7")).thenReturn(null);
        when(repo.countByRecipientUserIdAndReadIsFalse(7L)).thenReturn(5L);

        assertThat(service().unreadCount(7L)).isEqualTo(5);
        verify(valueOps).set("notify:unread:7", "5"); // 回填
    }

    @Test
    void markReadSetsReadAndDecrementsBadge() {
        Notification n = Notification.of(7L, NotificationType.VET_REPLY, "t", "b", "VET_REPLY", "tok", "ref");
        when(repo.findByDeepLinkTokenAndRecipientUserId("tok", 7L)).thenReturn(Optional.of(n));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.decrement("notify:unread:7")).thenReturn(2L);

        service().markRead(7L, "tok");

        assertThat(n.isRead()).isTrue();
        verify(repo).save(n);
        verify(valueOps).decrement("notify:unread:7");
    }

    @Test
    void markReadForeignOrMissingIsNotFound() {
        when(repo.findByDeepLinkTokenAndRecipientUserId("ghost", 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().markRead(7L, "ghost")).isInstanceOf(AppException.class);
    }

    @Test
    void decrementBelowZeroClampsToZero() {
        Notification n = Notification.of(7L, NotificationType.CONTENT_LIKED, "t", "b", "X", "tok", "ref");
        when(repo.findByDeepLinkTokenAndRecipientUserId("tok", 7L)).thenReturn(Optional.of(n));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.decrement("notify:unread:7")).thenReturn(-1L);

        service().markRead(7L, "tok");

        verify(valueOps).set(eq("notify:unread:7"), eq("0")); // 夹到 0
    }

    // ===== V1.3.4 follow · Story 3.3：互动条目现拼 =====

    private static final long ME = 7L;
    private static final Locale ID = Locale.forLanguageTag("id");
    private static final String CURSOR = new KeysetCursor(Instant.parse("2026-10-12T00:00:00Z"), 999L).encode();

    private long seq = 100;

    private Notification row(NotificationType type, Long commentId, String variant) {
        Notification n = Notification.interaction(ME, type, "兜底标题", "点击查看", type.name(), "tok" + seq,
                "55", null, commentId, variant);
        ReflectionTestUtils.setField(n, "id", seq++);
        ReflectionTestUtils.setField(n, "createdAt", Instant.parse("2026-10-11T00:00:00Z"));
        return n;
    }

    private Notification system() {
        Notification n = Notification.of(ME, NotificationType.VET_REPLY, "库标题", "库正文", "VET_REPLY", "tok" + seq, "9");
        ReflectionTestUtils.setField(n, "id", seq++);
        ReflectionTestUtils.setField(n, "createdAt", Instant.parse("2026-10-11T00:00:00Z"));
        return n;
    }

    private static NotificationActor actor(long notificationId, long userId) {
        NotificationActor a = BeanUtils.instantiateClass(NotificationActor.class);
        ReflectionTestUtils.setField(a, "id", new NotificationActorId(notificationId, userId));
        ReflectionTestUtils.setField(a, "actedAt", Instant.parse("2026-10-11T00:00:00Z"));
        return a;
    }

    private static ActorView view(long id) {
        return new ActorView(id, "u" + id, "tok" + id, true);
    }

    /** 准备一页：rows + 每条的触发人（最新在前）；所有触发人默认可显示。 */
    private List<NotificationItem> page(List<Notification> rows, Map<Long, List<Long>> actors, Set<Long> hidden,
            Set<Long> inactive) {
        when(repo.findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(rows);
        List<NotificationActor> actorRows = new ArrayList<>();
        actors.forEach((nId, ids) -> ids.forEach(uid -> actorRows.add(actor(nId, uid))));
        when(actorRepo.findByNotificationIds(anyCollection())).thenReturn(actorRows);
        when(accounts.findActorViews(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id,
                    id -> inactive.contains(id) ? new ActorView(id, null, null, false) : view(id)));
        });
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(hidden);
        when(accounts.localeOf(ME)).thenReturn(ID);
        return service().list(ME, CURSOR, 20).items();
    }

    @Test
    void likedSingleAndManyAreRenderedWithActorFields() {
        Notification one = row(NotificationType.CONTENT_LIKED, null, null);
        Notification many = row(NotificationType.CONTENT_LIKED, null, null);

        List<NotificationItem> items = page(List.of(one, many),
                Map.of(one.getId(), List.of(2L), many.getId(), List.of(3L, 4L, 5L)), Set.of(), Set.of());

        assertThat(items.get(0).title()).isEqualTo("u2 menyukai postinganmu");
        assertThat(items.get(0).body()).isNull();
        assertThat(items.get(0).actorName()).isEqualTo("u2");
        assertThat(items.get(0).actorUserId()).isEqualTo(2L);
        assertThat(items.get(0).actorToken()).isEqualTo("tok2");
        assertThat(items.get(0).actorCount()).isEqualTo(1);
        assertThat(items.get(0).actorIFollow()).isNull();
        assertThat(items.get(0).actorFollowsMe()).isNull();
        assertThat(items.get(1).title()).isEqualTo("u3 dan 2 lainnya menyukai postinganmu");
        assertThat(items.get(1).actorCount()).isEqualTo(3);
        // 其余字段照原样
        assertThat(items.get(1).deepLinkToken()).isEqualTo(many.getDeepLinkToken());
        assertThat(items.get(1).targetRef()).isEqualTo("55");
    }

    /** T-38：语言取接收方账号语言。 */
    @Test
    void titlesFollowRecipientAccountLocale() {
        Notification n = row(NotificationType.USER_FOLLOWED, null, null);
        when(accounts.localeOf(ME)).thenReturn(Locale.ENGLISH);
        when(repo.findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(List.of(n));
        when(actorRepo.findByNotificationIds(anyCollection())).thenReturn(List.of(actor(n.getId(), 2L)));
        when(accounts.findActorViews(anyCollection())).thenReturn(Map.of(2L, view(2L)));
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(Set.of());
        when(followReader.followStates(eq(ME), anyCollection())).thenReturn(Map.of());

        NotificationItem item = service().list(ME, CURSOR, 20).items().get(0);

        assertThat(item.title()).isEqualTo("u2 started following you");
        assertThat(item.actorIFollow()).isFalse();
        assertThat(item.actorFollowsMe()).isFalse();
    }

    @Test
    void commentReplyAndDeletedBodies() {
        Notification comment = row(NotificationType.CONTENT_COMMENTED, 70L, null);
        Notification reply = row(NotificationType.CONTENT_COMMENTED, 71L, Notification.VARIANT_REPLY);
        Notification gone = row(NotificationType.CONTENT_COMMENTED, 72L, null);
        Notification noComment = row(NotificationType.CONTENT_COMMENTED, null, null);
        when(comments.notificationSnapshots(anyCollection())).thenReturn(Map.of(
                70L, new NotificationCommentSnapshot("halo", false),
                71L, new NotificationCommentSnapshot("balas", false),
                72L, new NotificationCommentSnapshot(null, true)));

        List<NotificationItem> items = page(List.of(comment, reply, gone, noComment),
                Map.of(comment.getId(), List.of(2L), reply.getId(), List.of(3L), gone.getId(), List.of(4L),
                        noComment.getId(), List.of(5L)),
                Set.of(), Set.of());

        assertThat(items.get(0).title()).isEqualTo("u2 mengomentari postinganmu");
        assertThat(items.get(0).body()).isEqualTo("halo");
        assertThat(items.get(1).title()).isEqualTo("u3 membalas komentarmu");
        assertThat(items.get(1).body()).isEqualTo("balas");
        assertThat(items.get(2).body()).isEqualTo("Komentar telah dihapus");
        assertThat(items.get(3).body()).as("comment_id 为空 → body null").isNull();
    }

    @Test
    void placeCommentedAndFollowedCarryFollowStatesOnlyOnFollowed() {
        Notification place = row(NotificationType.PLACE_COMMENTED, null, null);
        Notification followed = row(NotificationType.USER_FOLLOWED, null, null);
        when(followReader.followStates(eq(ME), anyCollection()))
                .thenReturn(Map.of(4L, new FollowState(true, true)));

        List<NotificationItem> items = page(List.of(place, followed),
                Map.of(place.getId(), List.of(2L, 3L), followed.getId(), List.of(4L)), Set.of(), Set.of());

        assertThat(items.get(0).title()).isEqualTo("u2 dan 1 lainnya mengomentari tempat yang kamu tambahkan");
        assertThat(items.get(0).body()).isNull();
        assertThat(items.get(0).actorIFollow()).isNull();
        assertThat(items.get(1).title()).isEqualTo("u4 mulai mengikutimu");
        assertThat(items.get(1).actorIFollow()).isTrue();
        assertThat(items.get(1).actorFollowsMe()).as("T-41").isTrue();
    }

    /** FR-141.9 / T-30 / D-8：最新触发人被我隐藏 → 顺延；封号 / 注销不计入 N。 */
    @Test
    void hiddenAndInactiveActorsAreSkippedAndNotCounted() {
        Notification n = row(NotificationType.CONTENT_LIKED, null, null);

        NotificationItem item = page(List.of(n), Map.of(n.getId(), List.of(2L, 3L, 4L, 5L)), Set.of(2L),
                Set.of(4L)).get(0);

        assertThat(item.actorName()).isEqualTo("u3");
        assertThat(item.actorCount()).isEqualTo(2);
        assertThat(item.title()).isEqualTo("u3 dan 1 lainnya menyukai postinganmu");
    }

    /** ⚠️ T-36：触发人全被过滤 → 从列表隐藏（不报错）。 */
    @Test
    void itemWhoseActorsAreAllFilteredIsHidden() {
        Notification visible = row(NotificationType.CONTENT_LIKED, null, null);
        Notification invisible = row(NotificationType.USER_FOLLOWED, null, null);

        List<NotificationItem> items = page(List.of(visible, invisible),
                Map.of(visible.getId(), List.of(2L), invisible.getId(), List.of(3L, 4L)), Set.of(3L), Set.of(4L));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).deepLinkToken()).isEqualTo(visible.getDeepLinkToken());
    }

    /** 存量（上线前、无触发人行）与系统类条目原样返回，新字段为空。 */
    @Test
    void legacyAndSystemItemsAreReturnedAsIs() {
        Notification legacy = row(NotificationType.CONTENT_LIKED, null, null);
        Notification sys = system();

        List<NotificationItem> items = page(List.of(legacy, sys), Map.of(), Set.of(), Set.of());

        assertThat(items).extracting(NotificationItem::title).containsExactly("兜底标题", "库标题");
        assertThat(items.get(1).body()).isEqualTo("库正文");
        for (NotificationItem i : items) {
            assertThat(i.actorName()).isNull();
            assertThat(i.actorCount()).isZero();
            assertThat(i.actorUserId()).isNull();
            assertThat(i.actorToken()).isNull();
            assertThat(i.actorIFollow()).isNull();
        }
        verify(accounts, never()).findActorViews(anyCollection());
        verify(accounts, never()).localeOf(anyLong());
    }

    /** AC7.3：20 条混合页 —— 五个外部取数各一次，与条目数无关。 */
    @Test
    void mixedPageQueriesEachDependencyOnce() {
        List<Notification> rows = new ArrayList<>();
        java.util.Map<Long, List<Long>> actors = new java.util.HashMap<>();
        java.util.Map<Long, NotificationCommentSnapshot> snaps = new java.util.HashMap<>();
        for (int i = 0; i < 5; i++) {
            Notification like = row(NotificationType.CONTENT_LIKED, null, null);
            Notification cmt = row(NotificationType.CONTENT_COMMENTED, 700L + i, null);
            Notification fol = row(NotificationType.USER_FOLLOWED, null, null);
            rows.add(like);
            rows.add(cmt);
            rows.add(fol);
            rows.add(system());
            actors.put(like.getId(), List.of(10L + i, 20L + i));
            actors.put(cmt.getId(), List.of(30L + i));
            actors.put(fol.getId(), List.of(40L + i));
            snaps.put(700L + i, new NotificationCommentSnapshot("c", false));
        }
        when(comments.notificationSnapshots(anyCollection())).thenReturn(snaps);
        when(followReader.followStates(eq(ME), anyCollection())).thenReturn(Map.of());

        assertThat(page(rows, actors, Set.of(), Set.of())).hasSize(20);

        verify(actorRepo, times(1)).findByNotificationIds(anyCollection());
        verify(accounts, times(1)).findActorViews(anyCollection());
        verify(hideRelations, times(1)).hiddenByHolder(eq(ME), anyCollection());
        verify(comments, times(1)).notificationSnapshots(anyCollection());
        verify(followReader, times(1)).followStates(eq(ME), anyCollection());
    }

    @Test
    void systemOnlyPageTouchesNoDependency() {
        when(repo.findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(system(), system()));

        NotificationPage page = service().list(ME, CURSOR, 20);

        assertThat(page.items()).hasSize(2);
        org.mockito.Mockito.verifyNoInteractions(actorRepo, accounts, hideRelations, comments, followReader);
    }

    /** ⚠️ T-36：角标回算扣掉「触发人全被过滤」的未读互动条目。 */
    @Test
    void unreadRecomputeExcludesInvisibleInteractionItems() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("notify:unread:7")).thenReturn(null);
        when(repo.countByRecipientUserIdAndReadIsFalse(ME)).thenReturn(5L);
        when(repo.findUnreadInteractionIdsWithActors(ME)).thenReturn(List.of(1L, 2L));
        when(actorRepo.findByNotificationIds(anyCollection())).thenReturn(List.of(actor(1L, 3L), actor(2L, 4L)));
        when(accounts.findActorViews(anyCollection())).thenReturn(Map.of(3L, view(3L), 4L, view(4L)));
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(Set.of(4L));

        assertThat(service().unreadCount(ME)).isEqualTo(4);
        verify(valueOps).set("notify:unread:7", "4");
    }
}
