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
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.notify.port.NotificationGiftLines;
import com.tailtopia.notify.port.NotificationPetNames;
import com.tailtopia.ops.target.OpsTargetType;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * L0：未读角标 Redis 优先 + 缺值库回算回填；标记已读 token 定位 + 越权 404 + 角标递减。
 *
 * <p>V1.3.4 follow · Story 3.3：互动条目现拼「谁 + 做了什么」—— 模板用真实 {@code i18n/messages*.properties}。
 *
 * <p>运营通知列表项附带 opsTarget（V1.3.4 Story 6.1 · AC6，一页一次批量回查活动）。
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
    @Mock
    OpsNotificationCampaignRepository campaigns;

    /** 收礼宠物名端口（V1.4.0 gift Story 1.2）；null = 缺 bean（切片测试场景）。 */
    NotificationPetNames petNamesPort;

    private ObjectProvider<NotificationPetNames> petNamesProvider() {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (petNamesPort != null) {
            bf.addBean("notificationPetNames", petNamesPort);
        }
        return bf.getBeanProvider(NotificationPetNames.class);
    }

    /** 收礼单人条目「送的是什么」端口（V1.4.0 gift L2 第二轮）；null = 缺 bean。 */
    NotificationGiftLines giftLinesPort;

    private ObjectProvider<NotificationGiftLines> giftLinesProvider() {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (giftLinesPort != null) {
            bf.addBean("notificationGiftLines", giftLinesPort);
        }
        return bf.getBeanProvider(NotificationGiftLines.class);
    }

    private NotificationCenterService service() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        InteractionCopyRenderer copy = new InteractionCopyRenderer(accounts, hideRelations, source);
        InteractionActorResolver resolver = new InteractionActorResolver(repo, actorRepo, accounts, hideRelations);
        return new NotificationCenterService(repo, redis, resolver, accounts, comments, followReader, copy, campaigns,
                petNamesProvider(), giftLinesProvider());
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
        assertThat(items.get(2).bodyDeleted()).as("评论已删除 → App 按稿灰色斜体").isTrue();
        assertThat(items.get(0).bodyDeleted()).as("正常评论不下发该字段").isNull();
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
        when(repo.findUnreadInteractionIdsWithActors(eq(ME), anyCollection())).thenReturn(List.of(1L, 2L));
        when(actorRepo.findByNotificationIds(anyCollection())).thenReturn(List.of(actor(1L, 3L), actor(2L, 4L)));
        when(accounts.findActorViews(anyCollection())).thenReturn(Map.of(3L, view(3L), 4L, view(4L)));
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(Set.of(4L));

        assertThat(service().unreadCount(ME)).isEqualTo(4);
        verify(valueOps).set("notify:unread:7", "4");
    }

    // ===== 3.3 code-review 修复 =====

    /** F6：首页把未读互动条目与本页候选合成一批解析 —— ①②③ 仍各一次；角标校准扣掉不可见的未读条目。 */
    @Test
    void firstPageCalibratesBadgeWithTheSameBatch() {
        Notification visible = row(NotificationType.CONTENT_LIKED, null, null);
        Notification hidden = row(NotificationType.USER_FOLLOWED, null, null);
        when(repo.findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(List.of(visible, hidden));
        when(repo.findUnreadInteractionIdsWithActors(eq(ME), anyCollection()))
                .thenReturn(List.of(visible.getId(), hidden.getId(), 999L));
        when(repo.countByRecipientUserIdAndReadIsFalse(ME)).thenReturn(5L);
        when(actorRepo.findByNotificationIds(anyCollection())).thenReturn(List.of(
                actor(visible.getId(), 2L), actor(hidden.getId(), 3L), actor(999L, 3L)));
        when(accounts.findActorViews(anyCollection())).thenReturn(Map.of(2L, view(2L), 3L, view(3L)));
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(Set.of(3L));
        when(accounts.localeOf(ME)).thenReturn(ID);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(followReader.followStates(eq(ME), anyCollection())).thenReturn(Map.of());

        NotificationPage page = service().list(ME, null, 20);

        assertThat(page.items()).hasSize(1);
        verify(actorRepo, times(1)).findByNotificationIds(anyCollection());
        verify(accounts, times(1)).findActorViews(anyCollection());
        verify(hideRelations, times(1)).hiddenByHolder(eq(ME), anyCollection());
        verify(valueOps).set("notify:unread:7", "3"); // 5 − 2 条不可见（hidden、999）
    }

    /** F2：一整页都被隐藏但后面还有 → 往后补取，不给 App「空页 + hasMore」。 */
    @Test
    void fullyHiddenPageIsRefilledFromTheNextRows() {
        Notification h1 = row(NotificationType.CONTENT_LIKED, null, null);
        Notification h2 = row(NotificationType.CONTENT_LIKED, null, null);
        Notification sys = system();
        when(repo.findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(h1, h2, sys))   // limit 2 → hasMore
                .thenReturn(List.of(sys));
        when(actorRepo.findByNotificationIds(anyCollection()))
                .thenReturn(List.of(actor(h1.getId(), 3L), actor(h2.getId(), 3L)));
        when(accounts.findActorViews(anyCollection())).thenReturn(Map.of(3L, view(3L)));
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(Set.of(3L));

        NotificationPage page = service().list(ME, CURSOR, 2);

        assertThat(page.items()).extracting(NotificationItem::type).containsExactly("VET_REPLY");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(repo, times(2)).findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class));
    }

    /** T-36 隐藏条目不影响游标推进：nextCursor 指向本批最后一条原始行。 */
    @Test
    void hiddenItemsStillAdvanceTheCursor() {
        Notification visible = row(NotificationType.CONTENT_LIKED, null, null);
        Notification hidden = row(NotificationType.CONTENT_LIKED, null, null);
        Notification extra = system();
        when(repo.findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(visible, hidden, extra));
        when(actorRepo.findByNotificationIds(anyCollection()))
                .thenReturn(List.of(actor(visible.getId(), 2L), actor(hidden.getId(), 3L)));
        when(accounts.findActorViews(anyCollection())).thenReturn(Map.of(2L, view(2L), 3L, view(3L)));
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(Set.of(3L));
        when(accounts.localeOf(ME)).thenReturn(ID);

        NotificationPage page = service().list(ME, CURSOR, 2);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(KeysetCursor.decodeOrNull(page.nextCursor()).id()).isEqualTo(hidden.getId());
    }

    // ===== V1.3.4 Story 6.1 · AC6：运营通知附带 opsTarget =====

    /** 运营通知用例的行（合并 follow 后改名，避免与互动用例的 {@code row(type, Long, String)} 重载歧义）。 */
    private Notification opsRow(NotificationType type, String targetRef, String token) {
        Notification n = Notification.of(7L, type, "t-" + token, "b-" + token, type.name(), token, targetRef);
        ReflectionTestUtils.setField(n, "id", seq++);
        ReflectionTestUtils.setField(n, "createdAt", Instant.parse("2026-10-12T00:00:00Z"));
        return n;
    }

    private void pageOf(List<Notification> rows) {
        when(repo.findPageBefore(anyLong(), any(), anyLong(), any(Pageable.class))).thenReturn(rows);
        when(repo.countByRecipientUserIdAndReadIsFalse(7L)).thenReturn(0L);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @SuppressWarnings("unchecked")
    void opsAnnouncementsCarryOpsTargetWithOneBatchLookup() {
        OpsNotificationCampaign product = OpsNotificationCampaign.create("campA", "Judul", "Title", "Isi", "Body",
                OpsTargetType.PRODUCT, "prodTok", "{\"mode\":\"ALL\"}", null, null);
        pageOf(List.of(
                opsRow(NotificationType.OPS_ANNOUNCEMENT, "campA", "n1"),
                opsRow(NotificationType.CONTENT_LIKED, "123", "n2"),
                opsRow(NotificationType.OPS_ANNOUNCEMENT, "campGone", "n3")));
        when(campaigns.findByPublicTokenIn(any())).thenReturn(List.of(product));

        NotificationPage page = service().list(7L, null, 20);

        ArgumentCaptor<Collection<String>> tokens = ArgumentCaptor.forClass(Collection.class);
        verify(campaigns, times(1)).findByPublicTokenIn(tokens.capture());
        assertThat(tokens.getValue()).containsExactlyInAnyOrder("campA", "campGone");

        List<NotificationItem> items = page.items();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).opsTarget().type()).isEqualTo("PRODUCT");
        assertThat(items.get(0).opsTarget().value()).isEqualTo("prodTok");
        assertThat(items.get(0).targetRef()).isEqualTo("campA");
        assertThat(items.get(1).opsTarget()).as("非运营通知不带 opsTarget").isNull();
        assertThat(items.get(2).opsTarget().type()).as("活动查不到 → NONE").isEqualTo("NONE");
        assertThat(items.get(2).opsTarget().value()).isNull();
    }

    @Test
    void pageWithoutOpsAnnouncementsDoesNotQueryCampaigns() {
        pageOf(List.of(opsRow(NotificationType.CONTENT_LIKED, "123", "n1")));

        NotificationPage page = service().list(7L, null, 20);

        verifyNoInteractions(campaigns);
        NotificationItem item = page.items().get(0);
        // 既有字段与值不变（回归）
        assertThat(item.type()).isEqualTo("CONTENT_LIKED");
        assertThat(item.title()).isEqualTo("t-n1");
        assertThat(item.body()).isEqualTo("b-n1");
        assertThat(item.deepLinkType()).isEqualTo("CONTENT_LIKED");
        assertThat(item.deepLinkToken()).isEqualTo("n1");
        assertThat(item.targetRef()).isEqualTo("123");
        assertThat(item.read()).isFalse();
        assertThat(item.opsTarget()).isNull();
    }

    @Test
    void noneTargetCampaignMapsToNoneWithoutValue() {
        OpsNotificationCampaign none = OpsNotificationCampaign.create("campN", "Judul", "Title", "Isi", "Body",
                OpsTargetType.NONE, "ignored", "{\"mode\":\"ALL\"}", null, null);
        pageOf(List.of(opsRow(NotificationType.OPS_ANNOUNCEMENT, "campN", "n1")));
        when(campaigns.findByPublicTokenIn(any())).thenReturn(List.of(none));

        NotificationItem item = service().list(7L, null, 20).items().get(0);

        assertThat(item.opsTarget().type()).isEqualTo("NONE");
        assertThat(item.opsTarget().value()).isNull();
    }

    @Test
    void opsAnnouncementWithNullTargetRefFallsBackToNoneWithoutQuery() {
        pageOf(List.of(opsRow(NotificationType.OPS_ANNOUNCEMENT, null, "n1")));

        NotificationItem item = service().list(7L, null, 20).items().get(0);

        verifyNoInteractions(campaigns);
        assertThat(item.opsTarget().type()).isEqualTo("NONE");
        assertThat(item.opsTarget().value()).isNull();
    }

    // ===== V1.4.0 gift Story 1.2 · AC5.4：收礼条目带宠物名，一页一次批量取 =====

    private Notification giftRow(long petId) {
        Notification n = Notification.interaction(ME, NotificationType.PET_GIFT_RECEIVED, "兜底标题", "点击查看",
                NotificationType.PET_GIFT_RECEIVED.name(), "tok" + seq, String.valueOf(petId), "GIFT:PET:" + petId,
                null, null);
        ReflectionTestUtils.setField(n, "id", seq++);
        ReflectionTestUtils.setField(n, "createdAt", Instant.parse("2026-10-11T00:00:00Z"));
        return n;
    }

    @Test
    void giftRowsFetchPetNamesOnceAndRenderOneManyAndNoPet() {
        petNamesPort = org.mockito.Mockito.mock(NotificationPetNames.class);
        when(petNamesPort.namesOf(anyCollection())).thenReturn(Map.of(70L, "Mochi"));
        Notification one = giftRow(70L);
        Notification many = giftRow(70L);
        Notification gone = giftRow(71L);

        List<NotificationItem> items = page(List.of(one, many, gone),
                Map.of(one.getId(), List.of(2L), many.getId(), List.of(3L, 4L, 5L), gone.getId(), List.of(6L)),
                Set.of(), Set.of());

        assertThat(items).extracting(NotificationItem::title).containsExactly(
                "u2 mengirim hadiah untuk Mochi",
                "u3 dan 2 lainnya mengirim hadiah untuk Mochi",
                "u6 mengirim hadiah untuk hewanmu");
        assertThat(items.get(1).actorCount()).isEqualTo(3);
        assertThat(items.get(0).body()).as("收礼条目 body = null").isNull();
        assertThat(items.get(0).targetRef()).isEqualTo("70");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(petNamesPort, times(1)).namesOf(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(70L, 71L);
        verify(actorRepo, times(1)).findByNotificationIds(anyCollection());
        verify(accounts, times(1)).findActorViews(anyCollection());
    }

    @Test
    void missingPetNamesPortRendersNoPetCopy() {
        petNamesPort = null;
        Notification many = giftRow(70L);

        List<NotificationItem> items = page(List.of(many), Map.of(many.getId(), List.of(3L, 4L)), Set.of(),
                Set.of());

        assertThat(items.get(0).title()).isEqualTo("u3 dan 1 lainnya mengirim hadiah untuk hewanmu");
    }

    @Test
    void pageWithoutGiftRowsNeverCallsPetNamesPort() {
        petNamesPort = org.mockito.Mockito.mock(NotificationPetNames.class);
        Notification like = row(NotificationType.CONTENT_LIKED, null, null);

        page(List.of(like), Map.of(like.getId(), List.of(2L)), Set.of(), Set.of());

        verifyNoInteractions(petNamesPort);
    }

    @Test
    void giftRowWhoseSendersAreAllHiddenIsHiddenAndPetNamesNotFetched() {
        petNamesPort = org.mockito.Mockito.mock(NotificationPetNames.class);
        Notification gift = giftRow(70L);

        List<NotificationItem> items = page(List.of(gift), Map.of(gift.getId(), List.of(2L)), Set.of(2L), Set.of());

        assertThat(items).isEmpty();
        verifyNoInteractions(petNamesPort);
    }

    @Test
    void petNamesPortFailureFallsBackToNoPetCopy() {
        petNamesPort = org.mockito.Mockito.mock(NotificationPetNames.class);
        when(petNamesPort.namesOf(anyCollection())).thenThrow(new IllegalStateException("boom"));
        Notification gift = giftRow(70L);

        List<NotificationItem> items = page(List.of(gift), Map.of(gift.getId(), List.of(2L)), Set.of(), Set.of());

        assertThat(items.get(0).title()).isEqualTo("u2 mengirim hadiah untuk hewanmu");
    }

    // ===== V1.4.0 gift L2 第二轮：单人收礼条目带礼物名与数量（PRD「XX 给你的 Mochi 送了 Tulang ×5」，P-4 用名称）=====

    @Test
    @SuppressWarnings("unchecked")
    void singleSenderGiftRowShowsGiftLabelManyRowStaysGeneric() {
        petNamesPort = org.mockito.Mockito.mock(NotificationPetNames.class);
        when(petNamesPort.namesOf(anyCollection())).thenReturn(Map.of(70L, "Mochi"));
        giftLinesPort = org.mockito.Mockito.mock(NotificationGiftLines.class);
        Notification five = giftRow(70L);
        Notification single = giftRow(70L);
        Notification many = giftRow(70L);
        Notification noPet = giftRow(71L);
        when(giftLinesPort.linesOf(any())).thenReturn(Map.of(
                five.getId(), new NotificationGiftLines.GiftLine("Bone", "Tulang", 5),
                single.getId(), new NotificationGiftLines.GiftLine("Cake", "Kue", 1),
                noPet.getId(), new NotificationGiftLines.GiftLine("Bone", "Tulang", 2)));

        List<NotificationItem> items = page(List.of(five, single, many, noPet),
                Map.of(five.getId(), List.of(2L), single.getId(), List.of(3L), many.getId(), List.of(4L, 5L),
                        noPet.getId(), List.of(6L)),
                Set.of(), Set.of());

        assertThat(items).extracting(NotificationItem::title).containsExactly(
                "u2 mengirim Tulang ×5 untuk Mochi",
                "u3 mengirim Kue untuk Mochi",
                "u4 dan 1 lainnya mengirim hadiah untuk Mochi",
                "u6 mengirim Tulang ×2 untuk hewanmu");
        ArgumentCaptor<Map<Long, NotificationGiftLines.Query>> q = ArgumentCaptor.forClass(Map.class);
        verify(giftLinesPort, times(1)).linesOf(q.capture());
        assertThat(q.getValue()).as("多人合并条目不查礼物").containsOnlyKeys(five.getId(), single.getId(), noPet.getId());
        NotificationGiftLines.Query fq = q.getValue().get(five.getId());
        assertThat(fq.petId()).isEqualTo(70L);
        assertThat(fq.senderId()).isEqualTo(2L);
        assertThat(fq.since()).as("送礼人并入时刻 − 容差")
                .isEqualTo(Instant.parse("2026-10-11T00:00:00Z").minus(NotificationCenterService.GIFT_SINCE_TOLERANCE));
    }

    @Test
    void englishAccountGetsEnglishGiftName() {
        petNamesPort = org.mockito.Mockito.mock(NotificationPetNames.class);
        when(petNamesPort.namesOf(anyCollection())).thenReturn(Map.of(70L, "Mochi"));
        giftLinesPort = org.mockito.Mockito.mock(NotificationGiftLines.class);
        Notification gift = giftRow(70L);
        when(giftLinesPort.linesOf(any())).thenReturn(
                Map.of(gift.getId(), new NotificationGiftLines.GiftLine("Bone", "Tulang", 5)));
        when(repo.findPageBefore(eq(ME), any(), anyLong(), any(Pageable.class))).thenReturn(List.of(gift));
        when(actorRepo.findByNotificationIds(anyCollection())).thenReturn(List.of(actor(gift.getId(), 2L)));
        when(accounts.findActorViews(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id, id -> view(id)));
        });
        when(hideRelations.hiddenByHolder(eq(ME), anyCollection())).thenReturn(Set.of());
        when(accounts.localeOf(ME)).thenReturn(Locale.ENGLISH);

        assertThat(service().list(ME, CURSOR, 20).items().get(0).title()).isEqualTo("u2 sent Bone ×5 to Mochi");
    }

    @Test
    void giftLinesMissingOrFailingFallsBackToGenericCopy() {
        petNamesPort = org.mockito.Mockito.mock(NotificationPetNames.class);
        when(petNamesPort.namesOf(anyCollection())).thenReturn(Map.of(70L, "Mochi"));
        giftLinesPort = org.mockito.Mockito.mock(NotificationGiftLines.class);
        when(giftLinesPort.linesOf(any())).thenThrow(new IllegalStateException("boom"));
        Notification gift = giftRow(70L);

        List<NotificationItem> items = page(List.of(gift), Map.of(gift.getId(), List.of(2L)), Set.of(), Set.of());

        assertThat(items.get(0).title()).isEqualTo("u2 mengirim hadiah untuk Mochi");
    }
}
