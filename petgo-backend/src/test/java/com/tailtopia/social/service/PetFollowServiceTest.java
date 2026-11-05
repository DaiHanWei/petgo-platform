package com.tailtopia.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.social.domain.PetFollow;
import com.tailtopia.social.dto.FollowedPetItem;
import com.tailtopia.social.dto.FollowedPetPage;
import com.tailtopia.social.dto.PetFollowResultResponse;
import com.tailtopia.social.event.PetFollowedEvent;
import com.tailtopia.social.event.UserFollowedEvent;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.PetFollowRepository;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：追更宠物服务（V1.4.2 social-topic · Story 1.1 · AC3 / AC4 / AC6 / AC7 / AC8 / AC10）——
 * 拒绝顺序、幂等、条件计数、事件只在真正新建时发、拉黑 / 注销清理、列表组装（空集 / 翻页 / 坏游标 / 游客）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PetFollowServiceTest {

    private static final long ME = 1L;
    private static final long OWNER = 2L;
    private static final long PET = 10L;
    private static final Instant NOW = Instant.parse("2026-11-04T05:00:00Z");

    @Mock PetFollowRepository petFollows;
    @Mock UserFollowRepository userFollows;
    @Mock PetProfileRepository pets;
    @Mock AccountQueryService accounts;
    @Mock UserHideRelationReader hideRelations;
    @Mock FollowReader followReader;
    @Mock RedisRateLimiter rateLimiter;
    @Mock ApplicationEventPublisher events;

    private PetFollowService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PetFollowService(petFollows, pets, accounts, hideRelations, followReader, rateLimiter,
                new FollowDailyQuota(userFollows, petFollows, clock), events, clock);
        when(accounts.isActive(ME)).thenReturn(true);
        when(accounts.isActive(OWNER)).thenReturn(true);
        when(pets.findById(PET)).thenReturn(Optional.of(PetFixtures.pet(OWNER, PET, PetType.CAT, NOW)));
        when(pets.existsById(PET)).thenReturn(true);
        when(pets.findFollowerCount(PET)).thenReturn(5);
        when(followReader.followStates(anyLong(), anyCollection())).thenReturn(Map.of());
    }

    private static void assertRejected(Executable call, HttpStatus status) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    // ===== AC3 追更 =====

    @Test
    void followCreatesRowCountsAndPublishesOnce() {
        when(petFollows.insertIfAbsent(ME, PET)).thenReturn(1);

        PetFollowResultResponse r = service.follow(ME, PET);

        assertThat(r).isEqualTo(new PetFollowResultResponse(true, 5L));
        verify(pets).addFollowerCount(PET, 1);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isEqualTo(new PetFollowedEvent(ME, PET, OWNER, NOW));
        // 追更宠物不是「关注一个人」（FR-154.1）
        assertThat(ev.getAllValues()).noneMatch(UserFollowedEvent.class::isInstance);
    }

    /** 限流先于一切：超限时连本人状态都不查。 */
    @Test
    void rateLimitComesFirstAndSharesTheFollowBucket() {
        Mockito.doThrow(AppException.rateLimited("too fast")).when(rateLimiter)
                .check(eq("rl:social:follow:" + ME), eq(30), any());

        assertRejected(() -> service.follow(ME, PET), HttpStatus.TOO_MANY_REQUESTS);
        assertRejected(() -> service.unfollow(ME, PET), HttpStatus.TOO_MANY_REQUESTS);
        verify(accounts, never()).isActive(anyLong());
        verify(pets, never()).findById(anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void inactiveCallerIs401() {
        when(accounts.isActive(ME)).thenReturn(false);
        assertRejected(() -> service.follow(ME, PET), HttpStatus.UNAUTHORIZED);
        verify(pets, never()).findById(anyLong());
    }

    @Test
    void missingPetIs404() {
        when(pets.findById(PET)).thenReturn(Optional.empty());
        assertRejected(() -> service.follow(ME, PET), HttpStatus.NOT_FOUND);
    }

    /** 主人已注销 / DEACTIVATED → 与宠物不存在同一个 404。 */
    @Test
    void inactiveOwnerIs404() {
        when(accounts.isActive(OWNER)).thenReturn(false);
        assertRejected(() -> service.follow(ME, PET), HttpStatus.NOT_FOUND);
        verify(petFollows, never()).insertIfAbsent(anyLong(), anyLong());
    }

    /** READ_ONLY 宠物可以被追更。 */
    @Test
    void readOnlyPetCanBeFollowed() {
        PetProfile ro = PetFixtures.pet(OWNER, PET, PetType.CAT, NOW);
        ro.markReadOnly();
        when(pets.findById(PET)).thenReturn(Optional.of(ro));
        when(petFollows.insertIfAbsent(ME, PET)).thenReturn(1);

        assertThat(service.follow(ME, PET).iFollow()).isTrue();
        verify(pets).addFollowerCount(PET, 1);
    }

    @Test
    void ownPetIs422() {
        when(pets.findById(PET)).thenReturn(Optional.of(PetFixtures.pet(ME, PET, PetType.CAT, NOW)));
        assertRejected(() -> service.follow(ME, PET), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** 我拉黑了主人 → 403 blocked-user（拍板点 ①，不是关注侧的 409）。 */
    @Test
    void iBlockedOwnerIs403BlockedUser() {
        when(hideRelations.isBlocked(ME, OWNER)).thenReturn(true);
        assertThatThrownBy(() -> service.follow(ME, PET)).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getType()).isEqualTo(ErrorTypes.BLOCKED_USER);
        });
        verify(petFollows, never()).insertIfAbsent(anyLong(), anyLong());
    }

    /** 🔴 主人拉黑了我 → 照常成功、照发事件（不暴露被拉黑，抑制在 1.2 通知侧）。 */
    @Test
    void ownerBlockedMeStillSucceedsAndPublishes() {
        when(hideRelations.isBlocked(OWNER, ME)).thenReturn(true);
        when(petFollows.insertIfAbsent(ME, PET)).thenReturn(1);

        assertThat(service.follow(ME, PET).iFollow()).isTrue();
        verify(pets).addFollowerCount(PET, 1);
        verify(events).publishEvent(any(PetFollowedEvent.class));
    }

    /** 已追更：不查额度、不写、不计数、不发事件（幂等）。 */
    @Test
    void alreadyFollowingIsIdempotent() {
        when(petFollows.existsByFollowerIdAndPetProfileId(ME, PET)).thenReturn(true);

        assertThat(service.follow(ME, PET)).isEqualTo(new PetFollowResultResponse(true, 5L));
        verify(petFollows, never()).countCreatedSince(anyLong(), any());
        verify(userFollows, never()).countCreatedSince(anyLong(), any());
        verify(petFollows, never()).insertIfAbsent(anyLong(), anyLong());
        verify(pets, never()).addFollowerCount(anyLong(), anyInt());
        verifyNoInteractions(events);
    }

    /** 并发双击：insert 返回 0 → 不计数、不发事件。 */
    @Test
    void concurrentInsertLosingRaceDoesNotCountOrPublish() {
        when(petFollows.insertIfAbsent(ME, PET)).thenReturn(0);

        service.follow(ME, PET);

        verify(pets, never()).addFollowerCount(anyLong(), anyInt());
        verifyNoInteractions(events);
    }

    /** 每日额度两表合计 ≥ 500 → 429，什么都不写。 */
    @Test
    void combinedDailyQuotaIs429() {
        when(userFollows.countCreatedSince(eq(ME), any())).thenReturn(200L);
        when(petFollows.countCreatedSince(eq(ME), any())).thenReturn(300L);

        assertRejected(() -> service.follow(ME, PET), HttpStatus.TOO_MANY_REQUESTS);
        verify(petFollows, never()).insertIfAbsent(anyLong(), anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void combinedDailyQuota499StillFollows() {
        when(userFollows.countCreatedSince(eq(ME), any())).thenReturn(499L);
        when(petFollows.insertIfAbsent(ME, PET)).thenReturn(1);

        assertThat(service.follow(ME, PET).iFollow()).isTrue();
    }

    /** 拒绝顺序：同时满足多个拒绝条件时，取最前面那条。 */
    @Test
    void rejectionOrder() {
        // 本人失效 + 宠物不存在 → 401
        when(accounts.isActive(ME)).thenReturn(false);
        when(pets.findById(PET)).thenReturn(Optional.empty());
        assertRejected(() -> service.follow(ME, PET), HttpStatus.UNAUTHORIZED);
        when(accounts.isActive(ME)).thenReturn(true);
        // 宠物是自己的 + 我拉黑了「主人」（自己）→ 422 先于 403
        when(pets.findById(PET)).thenReturn(Optional.of(PetFixtures.pet(ME, PET, PetType.CAT, NOW)));
        when(hideRelations.isBlocked(ME, ME)).thenReturn(true);
        assertRejected(() -> service.follow(ME, PET), HttpStatus.UNPROCESSABLE_ENTITY);
        // 我拉黑了主人 + 额度已满 → 403 先于 429
        when(pets.findById(PET)).thenReturn(Optional.of(PetFixtures.pet(OWNER, PET, PetType.CAT, NOW)));
        when(hideRelations.isBlocked(ME, OWNER)).thenReturn(true);
        when(petFollows.countCreatedSince(eq(ME), any())).thenReturn(500L);
        assertRejected(() -> service.follow(ME, PET), HttpStatus.FORBIDDEN);
        verifyNoInteractions(events);
    }

    // ===== AC4 取消追更 =====

    @Test
    void unfollowDeletesAndDecrementsWithoutEvent() {
        when(petFollows.deleteRelation(ME, PET)).thenReturn(1);

        PetFollowResultResponse r = service.unfollow(ME, PET);

        assertThat(r).isEqualTo(new PetFollowResultResponse(false, 5L));
        verify(pets).addFollowerCount(PET, -1);
        verifyNoInteractions(events);
    }

    /** 主人 DEACTIVATED / 拉黑关系都不影响取消（PRD AC11）。 */
    @Test
    void unfollowIgnoresOwnerStatusAndBlocks() {
        when(accounts.isActive(OWNER)).thenReturn(false);
        when(hideRelations.isBlocked(anyLong(), anyLong())).thenReturn(true);
        when(petFollows.deleteRelation(ME, PET)).thenReturn(1);

        assertThat(service.unfollow(ME, PET).iFollow()).isFalse();
        verify(pets).addFollowerCount(PET, -1);
        verify(accounts, never()).isActive(OWNER);
    }

    /** 未追更：幂等成功，受影响 0 行不扣减。 */
    @Test
    void unfollowWhenNotFollowingIsIdempotent() {
        when(petFollows.deleteRelation(ME, PET)).thenReturn(0);

        assertThat(service.unfollow(ME, PET).iFollow()).isFalse();
        verify(pets, never()).addFollowerCount(anyLong(), anyInt());
    }

    /** 宠物不存在 → 404（拍板点 ②）。 */
    @Test
    void unfollowMissingPetIs404() {
        when(pets.existsById(PET)).thenReturn(false);
        assertRejected(() -> service.unfollow(ME, PET), HttpStatus.NOT_FOUND);
        verify(petFollows, never()).deleteRelation(anyLong(), anyLong());
    }

    // ===== AC6 / AC7 联动 =====

    @Test
    void removeOnBlockUsesOwnerScopedDeleteAndNoEventOrRateLimit() {
        service.removeOnBlock(ME, OWNER);

        verify(petFollows).deleteByFollowerAndOwner(ME, OWNER);
        verify(petFollows, never()).deleteByFollowerAndOwner(OWNER, ME);
        verifyNoInteractions(rateLimiter, events);
    }

    @Test
    void purgeFollowerUsesAtomicPurge() {
        service.purgeFollower(ME);

        verify(petFollows).purgeFollower(ME);
        verifyNoInteractions(rateLimiter, events);
    }

    // ===== AC8 列表 =====

    private User owner(long id, Role role) {
        User u = Mockito.mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getRole()).thenReturn(role);
        return u;
    }

    private static PetFollow follow(long followerId, long petId, Instant at) {
        try {
            var ctor = PetFollow.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            PetFollow f = ctor.newInstance();
            ReflectionTestUtils.setField(f, "followerId", followerId);
            ReflectionTestUtils.setField(f, "petProfileId", petId);
            ReflectionTestUtils.setField(f, "createdAt", at);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final long LIST_USER = 3L;
    private static final String LIST_TOKEN = "u".repeat(32);

    private void listUserExists() {
        User u = owner(LIST_USER, Role.USER);
        when(accounts.findActiveByPublicToken(LIST_TOKEN)).thenReturn(Optional.of(u));
    }

    @Test
    void emptyListHasZeroCountAndNoNextCursor() {
        listUserExists();
        when(petFollows.findFollowingPage(eq(LIST_USER), any(), anyLong(), eq(21))).thenReturn(List.of());
        when(petFollows.countByFollowerId(LIST_USER)).thenReturn(0L);

        FollowedPetPage page = service.followingOf(LIST_TOKEN, ME, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.petFollowingCount()).isZero();
    }

    @Test
    void firstPageAssemblesRowsInBatchAndCountsOnlyOnFirstPage() {
        listUserExists();
        // 21 行 → hasMore，页内 20 行
        List<PetFollow> rows = new ArrayList<>();
        List<PetProfile> petRows = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            long petId = 100 + i;
            rows.add(follow(LIST_USER, petId, NOW.minusSeconds(i)));
            petRows.add(PetFixtures.pet(i % 2 == 0 ? OWNER : 4L, petId, PetType.DOG, NOW));
        }
        when(petFollows.findFollowingPage(eq(LIST_USER), any(), anyLong(), eq(21))).thenReturn(rows);
        when(petFollows.countByFollowerId(LIST_USER)).thenReturn(25L);
        when(pets.findAllById(any())).thenReturn(petRows.subList(0, 20));
        when(accounts.findAuthorViewsWithoutTags(any())).thenReturn(Map.of(
                OWNER, new AuthorView(OWNER, "Aurel", null, false, List.of()),
                4L, new AuthorView(4L, "Budi", null, false, List.of())));
        when(accounts.publicTokensOf(any(), eq(true))).thenReturn(Map.of(OWNER, "tok-owner", 4L, "tok-budi"));
        when(petFollows.findFollowedPetIdsAmong(eq(ME), any())).thenReturn(List.of(100L));
        when(followReader.followStates(eq(ME), anyCollection()))
                .thenReturn(Map.of(OWNER, new FollowState(true, false), 4L, FollowState.NONE));

        FollowedPetPage page = service.followingOf(LIST_TOKEN, ME, null);

        assertThat(page.items()).hasSize(20);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.petFollowingCount()).isEqualTo(25L);
        KeysetCursor next = KeysetCursor.decodeOrNull(page.nextCursor());
        assertThat(next.id()).isEqualTo(119L);
        assertThat(next.createdAt()).isEqualTo(NOW.minusSeconds(19));
        FollowedPetItem first = page.items().get(0);
        assertThat(first.petId()).isEqualTo(100L);
        assertThat(first.ownerNickname()).isEqualTo("Aurel");
        assertThat(first.ownerToken()).isEqualTo("tok-owner");
        assertThat(first.iFollow()).isTrue();
        assertThat(first.iFollowOwner()).isTrue();
        FollowedPetItem second = page.items().get(1);
        assertThat(second.ownerToken()).isEqualTo("tok-budi");
        assertThat(second.iFollow()).isFalse();
        assertThat(second.iFollowOwner()).isFalse();
        // 整页各一次批量查询（禁止 N+1）
        verify(pets).findAllById(any());
        verify(accounts).findAuthorViewsWithoutTags(any());
        verify(accounts).publicTokensOf(any(), eq(true));
        verify(petFollows).findFollowedPetIdsAmong(eq(ME), any());
        verify(followReader).followStates(eq(ME), anyCollection());
        verify(pets, never()).findById(anyLong());
    }

    @Test
    void laterPageOmitsCountAndUsesCursor() {
        listUserExists();
        Instant ts = NOW.minusSeconds(30);
        String cursor = new KeysetCursor(ts, 77L).encode();
        when(petFollows.findFollowingPage(LIST_USER, ts, 77L, 21)).thenReturn(List.of());

        FollowedPetPage page = service.followingOf(LIST_TOKEN, ME, cursor);

        assertThat(page.petFollowingCount()).isNull();
        verify(petFollows).findFollowingPage(LIST_USER, ts, 77L, 21);
        verify(petFollows, never()).countByFollowerId(anyLong());
    }

    /** 坏游标按首页（不报错）。 */
    @Test
    void badCursorFallsBackToFirstPage() {
        listUserExists();
        when(petFollows.findFollowingPage(eq(LIST_USER), any(), eq(Long.MAX_VALUE), eq(21))).thenReturn(List.of());

        FollowedPetPage page = service.followingOf(LIST_TOKEN, ME, "!!not-a-cursor!!");

        assertThat(page.items()).isEmpty();
        verify(petFollows).findFollowingPage(eq(LIST_USER), any(), eq(Long.MAX_VALUE), eq(21));
    }

    /** 游客：iFollow / iFollowOwner 恒 false，且不查我的追更 / 关注集合。 */
    @Test
    void guestSeesFalseFlagsWithoutRelationLookups() {
        listUserExists();
        when(petFollows.findFollowingPage(eq(LIST_USER), any(), anyLong(), eq(21)))
                .thenReturn(List.of(follow(LIST_USER, PET, NOW)));
        when(pets.findAllById(any())).thenReturn(List.of(PetFixtures.pet(OWNER, PET, PetType.CAT, NOW)));
        when(accounts.findAuthorViewsWithoutTags(any()))
                .thenReturn(Map.of(OWNER, new AuthorView(OWNER, "Aurel", null, false, List.of())));
        when(accounts.publicTokensOf(any(), eq(true))).thenReturn(Map.of(OWNER, "tok-owner"));

        FollowedPetPage page = service.followingOf(LIST_TOKEN, null, null);

        assertThat(page.items()).singleElement().satisfies(i -> {
            assertThat(i.iFollow()).isFalse();
            assertThat(i.iFollowOwner()).isFalse();
            assertThat(i.ownerToken()).isEqualTo("tok-owner");
        });
        verify(petFollows, never()).findFollowedPetIdsAmong(anyLong(), any());
        verify(followReader, never()).followStates(anyLong(), anyCollection());
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    /** 主人 DEACTIVATED 的宠物仍列出（列表不按主人状态过滤）。 */
    @Test
    void deactivatedOwnersPetStillListed() {
        listUserExists();
        when(accounts.isActive(OWNER)).thenReturn(false);
        when(petFollows.findFollowingPage(eq(LIST_USER), any(), anyLong(), eq(21)))
                .thenReturn(List.of(follow(LIST_USER, PET, NOW)));
        when(pets.findAllById(any())).thenReturn(List.of(PetFixtures.pet(OWNER, PET, PetType.CAT, NOW)));
        when(accounts.findAuthorViewsWithoutTags(any()))
                .thenReturn(Map.of(OWNER, new AuthorView(OWNER, "Aurel", null, false, List.of())));
        when(accounts.publicTokensOf(any(), eq(true))).thenReturn(Map.of(OWNER, "tok-owner"));

        assertThat(service.followingOf(LIST_TOKEN, ME, null).items()).hasSize(1);
    }

    @Test
    void listOwnerMissingOrInactiveIs404() {
        when(accounts.findActiveByPublicToken(anyString())).thenReturn(Optional.empty());
        assertRejected(() -> service.followingOf(LIST_TOKEN, ME, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void officialAccountListIs403ForOthersButOpenToSelf() {
        User admin = owner(LIST_USER, Role.ADMIN);
        when(accounts.findActiveByPublicToken(LIST_TOKEN)).thenReturn(Optional.of(admin));
        when(petFollows.findFollowingPage(eq(LIST_USER), any(), anyLong(), eq(21))).thenReturn(List.of());

        assertRejected(() -> service.followingOf(LIST_TOKEN, ME, null), HttpStatus.FORBIDDEN);
        assertRejected(() -> service.followingOf(LIST_TOKEN, null, null), HttpStatus.FORBIDDEN);
        assertThat(service.followingOf(LIST_TOKEN, LIST_USER, null).items()).isEmpty();
    }

    /** 我拉黑了主页用户 → 403 blocked-user；主页用户拉黑了我 → 照常返回。 */
    @Test
    void blockDirectionsOnList() {
        listUserExists();
        when(petFollows.findFollowingPage(eq(LIST_USER), any(), anyLong(), eq(21))).thenReturn(List.of());

        when(hideRelations.isBlocked(LIST_USER, ME)).thenReturn(true);
        assertThat(service.followingOf(LIST_TOKEN, ME, null).items()).isEmpty();

        when(hideRelations.isBlocked(ME, LIST_USER)).thenReturn(true);
        assertThatThrownBy(() -> service.followingOf(LIST_TOKEN, ME, null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.BLOCKED_USER));
    }

    @Test
    void writeOrderInsertThenCountThenEvent() {
        when(petFollows.insertIfAbsent(ME, PET)).thenReturn(1);

        service.follow(ME, PET);

        InOrder order = inOrder(petFollows, pets, events);
        order.verify(petFollows).insertIfAbsent(ME, PET);
        order.verify(pets).addFollowerCount(PET, 1);
        order.verify(events).publishEvent(any(PetFollowedEvent.class));
    }
}
