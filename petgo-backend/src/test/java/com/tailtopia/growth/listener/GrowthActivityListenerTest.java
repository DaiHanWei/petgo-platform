package com.tailtopia.growth.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.event.UserDailyActiveEvent;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.content.event.ContentRemovedEvent;
import com.tailtopia.content.event.ContentUncollectedEvent;
import com.tailtopia.content.event.ContentUnlikedEvent;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.event.PlaceCheckedInEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.MilestoneLevel;
import com.tailtopia.profile.event.MilestoneCompletedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** V1.4.0 ① Story 1.1 · AC6–AC14（L0）：每类事件 → 期望的入账 / 扣回命令（规则、幂等键、ref、pet_ids）。 */
class GrowthActivityListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T05:00:00Z");

    private final GrowthLedgerService ledger = mock(GrowthLedgerService.class);
    private final ContentPetQuery contentPets = mock(ContentPetQuery.class);
    private final ContentLikeRepository likes = mock(ContentLikeRepository.class);
    private final GrowthActivityListener listener = new GrowthActivityListener(ledger, contentPets, likes,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @org.junit.jupiter.api.BeforeEach
    void stillLikedByDefault() {
        when(likes.existsByPostIdAndUserId(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    private List<AccrualCommand> accrued(int times) {
        ArgumentCaptor<AccrualCommand> c = ArgumentCaptor.forClass(AccrualCommand.class);
        verify(ledger, times(times)).accrue(c.capture());
        return c.getAllValues();
    }

    private List<ReverseCommand> reversed(int times) {
        ArgumentCaptor<ReverseCommand> c = ArgumentCaptor.forClass(ReverseCommand.class);
        verify(ledger, times(times)).reverse(c.capture());
        return c.getAllValues();
    }

    private void postWithPet(long postId, Long... petIds) {
        when(contentPets.petsOf(postId)).thenReturn(List.of(petIds));
    }

    @Test
    void dailyLoginUsesWibDateInKeyAndQuotaDay() {
        LocalDate day = LocalDate.of(2026, 10, 18);
        listener.onDailyActive(new UserDailyActiveEvent(7L, day));

        AccrualCommand c = accrued(1).get(0);
        assertThat(c.rule()).isEqualTo(RuleCode.DAILY_LOGIN);
        assertThat(c.userId()).isEqualTo(7L);
        assertThat(c.idemKey()).isEqualTo("DAILY_LOGIN:7:2026-10-18");
        assertThat(c.occurredAt().atZone(com.tailtopia.shared.schedule.ScheduleWindow.WIB).toLocalDate())
                .isEqualTo(day);
        assertThat(c.refType()).isNull();
    }

    @Test
    void publishedPostIsKeyedByPostIdWithPet() {
        Instant at = Instant.parse("2026-10-18T01:00:00Z");
        listener.onPublished(new ContentPublishedEvent(5L, 7L, ContentType.GROWTH_MOMENT, 9L, 1,
                ContentVisibility.PRIVATE, at));

        AccrualCommand c = accrued(1).get(0);
        assertThat(c.rule()).isEqualTo(RuleCode.POST_PUBLISHED);
        assertThat(c.userId()).isEqualTo(7L);
        assertThat(c.idemKey()).isEqualTo("POST:5");
        assertThat(c.refType()).isEqualTo("CONTENT_POST");
        assertThat(c.refId()).isEqualTo("5");
        assertThat(c.petIds()).containsExactly(9L);
        assertThat(c.occurredAt()).isEqualTo(at);
    }

    @Test
    void publishedMomentWithoutPetHasNoPetIds() {
        listener.onPublished(new ContentPublishedEvent(5L, 7L, ContentType.DAILY, null, 0,
                ContentVisibility.PUBLIC, NOW));

        assertThat(accrued(1).get(0).petIds()).isNull();
    }

    @Test
    void commentByOtherGivesPostedToCommenterAndReceivedToPostAuthorNotParent() {
        postWithPet(5L, 9L);
        listener.onCommented(new ContentCommentedEvent(5L, 77L, 3L, 7L, 8L, NOW, 70L));

        List<AccrualCommand> cs = accrued(2);
        assertThat(cs.get(0).rule()).isEqualTo(RuleCode.COMMENT_POSTED);
        assertThat(cs.get(0).userId()).isEqualTo(3L);
        assertThat(cs.get(0).idemKey()).isEqualTo("COMMENT_POSTED:77");
        assertThat(cs.get(1).rule()).isEqualTo(RuleCode.COMMENT_RECEIVED);
        assertThat(cs.get(1).userId()).isEqualTo(7L); // 帖子作者，不是被回复者 8
        assertThat(cs.get(1).idemKey()).isEqualTo("COMMENT_RECEIVED:77");
        assertThat(cs.get(1).refId()).isEqualTo("5");
        assertThat(cs.get(1).petIds()).containsExactly(9L);
    }

    /** 多宠物线合入后（CROSS-STORY 2026-10-18「其他口径」）：发帖取事件全部关联宠物，互动取 content_pets 全部关联宠物。 */
    @Test
    void multiPetPostWritesAllLinkedPets() {
        listener.onPublished(new ContentPublishedEvent(5L, 7L, ContentType.GROWTH_MOMENT, 9L, 1,
                ContentVisibility.PUBLIC, NOW, List.of(9L, 10L)));
        assertThat(accrued(1).get(0).petIds()).containsExactly(9L, 10L);

        postWithPet(6L, 9L, 10L);
        listener.onCommented(new ContentCommentedEvent(6L, 78L, 3L, 7L, 8L, NOW, 70L));
        List<AccrualCommand> cs = accrued(3);
        assertThat(cs.get(1).petIds()).containsExactly(9L, 10L);
        assertThat(cs.get(2).petIds()).containsExactly(9L, 10L);
    }

    /** V1.5.0 likes Story 1.1 · A-AC30：自动赞 → 作者无 LIKE_RECEIVED、虚拟号无 LIKE_GIVEN（整段跳过，不复查）。 */
    @Test
    void automatedLikeAccruesNothing() {
        postWithPet(5L, 9L);
        listener.onLiked(new ContentLikedEvent(5L, 3L, 7L, NOW, true));
        verify(ledger, never()).accrue(any());
        verify(ledger, never()).reverse(any());
        verify(likes, never()).existsByPostIdAndUserId(5L, 3L);
    }

    /** Story 4.4 · AC4.2 / AC4.5：他人点赞多宠帖 → LIKE_RECEIVED 写全部关联宠物；查询抛错 → null 且照常入账。 */
    @Test
    void likeOnMultiPetPostWritesAllPetsAndQueryFailureStillAccrues() {
        postWithPet(5L, 9L, 10L);
        listener.onLiked(new ContentLikedEvent(5L, 3L, 7L, NOW));
        List<AccrualCommand> cs = accrued(2);
        assertThat(cs.get(1).rule()).isEqualTo(RuleCode.LIKE_RECEIVED);
        assertThat(cs.get(1).petIds()).containsExactly(9L, 10L);

        when(contentPets.petsOf(6L)).thenThrow(new IllegalStateException("db down"));
        listener.onLiked(new ContentLikedEvent(6L, 3L, 7L, NOW));
        List<AccrualCommand> all = accrued(4);
        assertThat(all.subList(2, 4)).allSatisfy(c -> assertThat(c.petIds()).isNull());
        assertThat(all.get(3).rule()).isEqualTo(RuleCode.LIKE_RECEIVED);
    }

    /** Story 4.4 · AC4.1：事件 petIds 为空表（Moment / Tips）→ pet_ids = null。 */
    @Test
    void publishedWithEmptyPetIdsHasNoPetIds() {
        listener.onPublished(new ContentPublishedEvent(5L, 7L, ContentType.KNOWLEDGE, null, 0,
                ContentVisibility.PUBLIC, NOW, List.of()));
        assertThat(accrued(1).get(0).petIds()).isNull();
    }

    @Test
    void selfCommentGivesNothing() {
        listener.onCommented(new ContentCommentedEvent(5L, 77L, 7L, 7L, 3L, NOW, 70L));

        verify(ledger, never()).accrue(any());
    }

    @Test
    void goneAuthorSkipsReceived() {
        listener.onCommented(new ContentCommentedEvent(5L, 77L, 3L, -1L, null, NOW, null));

        List<AccrualCommand> cs = accrued(1);
        assertThat(cs.get(0).rule()).isEqualTo(RuleCode.COMMENT_POSTED);
    }

    @Test
    void missingPostDoesNotBlockAccrual() {
        when(contentPets.petsOf(5L)).thenReturn(List.of());
        listener.onLiked(new ContentLikedEvent(5L, 3L, 7L, NOW));

        List<AccrualCommand> cs = accrued(2);
        assertThat(cs).allSatisfy(c -> assertThat(c.petIds()).isNull());
    }

    @Test
    void likeGivesBothSidesKeyedForever() {
        postWithPet(5L);
        listener.onLiked(new ContentLikedEvent(5L, 3L, 7L, NOW));

        List<AccrualCommand> cs = accrued(2);
        assertThat(cs.get(0).rule()).isEqualTo(RuleCode.LIKE_GIVEN);
        assertThat(cs.get(0).userId()).isEqualTo(3L);
        assertThat(cs.get(0).idemKey()).isEqualTo("LIKE_GIVEN:3:5");
        assertThat(cs.get(1).rule()).isEqualTo(RuleCode.LIKE_RECEIVED);
        assertThat(cs.get(1).userId()).isEqualTo(7L);
        assertThat(cs.get(1).idemKey()).isEqualTo("LIKE_RECEIVED:3:5");
    }

    @Test
    void likeAlreadyGoneWhenAccrualCommitsIsReversedRightAway() {
        postWithPet(5L);
        when(likes.existsByPostIdAndUserId(5L, 3L)).thenReturn(false);

        listener.onLiked(new ContentLikedEvent(5L, 3L, 7L, NOW));

        accrued(2);
        List<ReverseCommand> rs = reversed(2);
        assertThat(rs).extracting(ReverseCommand::originalIdemKey)
                .containsExactly("LIKE_GIVEN:3:5", "LIKE_RECEIVED:3:5");
        assertThat(rs).allSatisfy(r -> assertThat(r.sourceKey()).isEqualTo("UNLIKE"));
    }

    @Test
    void likeStillPresentIsNotReversed() {
        postWithPet(5L);

        listener.onLiked(new ContentLikedEvent(5L, 3L, 7L, NOW));

        verify(ledger, never()).reverse(any());
    }

    @Test
    void unlikeReversesBothOriginalKeys() {
        listener.onUnliked(new ContentUnlikedEvent(5L, 3L, 7L));

        List<ReverseCommand> rs = reversed(2);
        assertThat(rs).extracting(ReverseCommand::originalIdemKey)
                .containsExactly("LIKE_GIVEN:3:5", "LIKE_RECEIVED:3:5");
        assertThat(rs).allSatisfy(r -> {
            assertThat(r.reason()).isEqualTo(ReverseReason.UNLIKE);
            assertThat(r.sourceKey()).isEqualTo("UNLIKE");
            assertThat(r.amountOverride()).isNull();
        });
    }

    @Test
    void collectGivesAuthorPassiveAndSelfCollectIsIgnored() {
        postWithPet(5L, 9L);
        listener.onCollected(new ContentCollectedEvent(5L, 3L, 7L));
        listener.onCollected(new ContentCollectedEvent(5L, 7L, 7L));

        AccrualCommand c = accrued(1).get(0);
        assertThat(c.rule()).isEqualTo(RuleCode.COLLECT_RECEIVED);
        assertThat(c.userId()).isEqualTo(7L);
        assertThat(c.idemKey()).isEqualTo("COLLECT_RECEIVED:3:5");
    }

    @Test
    void uncollectReversesCollectReceived() {
        listener.onUncollected(new ContentUncollectedEvent(5L, 3L, 7L));

        ReverseCommand r = reversed(1).get(0);
        assertThat(r.originalIdemKey()).isEqualTo("COLLECT_RECEIVED:3:5");
        assertThat(r.reason()).isEqualTo(ReverseReason.UNCOLLECT);
    }

    @Test
    void checkinIsKeyedByCheckinIdWithAllPets() {
        listener.onCheckedIn(new PlaceCheckedInEvent(7L, "t".repeat(32), PlaceType.PARK, true, 1,
                List.of(9L, 10L), 11L));

        AccrualCommand c = accrued(1).get(0);
        assertThat(c.rule()).isEqualTo(RuleCode.PLACE_CHECKIN);
        assertThat(c.idemKey()).isEqualTo("CHECKIN:11");
        assertThat(c.refType()).isEqualTo("PLACE_CHECKIN");
        assertThat(c.refId()).isEqualTo("11");
        assertThat(c.petIds()).containsExactly(9L, 10L);
    }

    @Test
    void milestonePicksRuleByLevel() {
        listener.onMilestone(new MilestoneCompletedEvent(7L, "C-S1", MilestoneLevel.S, "t",
                MilestoneCompletionSource.SYSTEM_AUTO, 9L));
        listener.onMilestone(new MilestoneCompletedEvent(7L, "C-M5", MilestoneLevel.M, "t",
                MilestoneCompletionSource.SYSTEM_AUTO, 9L));
        listener.onMilestone(new MilestoneCompletedEvent(7L, "C-L1", MilestoneLevel.L, "t",
                MilestoneCompletionSource.SYSTEM_AUTO, null));

        List<AccrualCommand> cs = accrued(3);
        assertThat(cs).extracting(AccrualCommand::rule)
                .containsExactly(RuleCode.MILESTONE_S, RuleCode.MILESTONE_M, RuleCode.MILESTONE_L);
        assertThat(cs.get(0).idemKey()).isEqualTo("MILESTONE:7:C-S1:9");
        assertThat(cs.get(0).refType()).isEqualTo("MILESTONE");
        assertThat(cs.get(0).refId()).isEqualTo("C-S1");
        assertThat(cs.get(0).petIds()).containsExactly(9L);
        assertThat(cs.get(2).idemKey()).isEqualTo("MILESTONE:7:C-L1:0");
        assertThat(cs.get(2).petIds()).isNull();
    }

    /** V1.4.2 Story 2.1 T7.4（D-12）：账号级 6 个编码走按宠物路径时不入账（防 2 只宠物 = 2 次给分）。 */
    @Test
    void perPetMilestoneEventForAccountLevelCodeIsIgnored() {
        for (String code : List.of("C-S19", "C-S20", "D-S19", "D-S20", "G-S12", "G-S13")) {
            listener.onMilestone(new MilestoneCompletedEvent(7L, code, MilestoneLevel.S, "t",
                    MilestoneCompletionSource.SYSTEM_AUTO, 9L));
        }
        verify(ledger, never()).accrue(any());
    }

    /** V1.4.2 Story 2.1 T7.3（P-7）：账号级 1 条 MILESTONE_S，键 = 账号 + flag，refId = flagKey，关联写了完成行的宠物。 */
    @Test
    void accountMilestoneAccruesOnceKeyedByUserAndFlag() {
        listener.onAccountMilestone(new com.tailtopia.profile.event.AccountMilestoneCompletedEvent(7L,
                "FIRST_FOLLOW_USER", List.of(9L, 10L), List.of("C-S19", "D-S19"), "C-S19"));

        AccrualCommand c = accrued(1).get(0);
        assertThat(c.userId()).isEqualTo(7L);
        assertThat(c.rule()).isEqualTo(RuleCode.MILESTONE_S);
        assertThat(c.idemKey()).isEqualTo("MILESTONE_ACCT:7:FIRST_FOLLOW_USER");
        assertThat(c.refType()).isEqualTo("MILESTONE");
        assertThat(c.refId()).isEqualTo("FIRST_FOLLOW_USER");
        assertThat(c.petIds()).containsExactly(9L, 10L);
    }

    /** V1.4.2 Story 2.1 AC5.1（G-3）：触发时没有 ACTIVE 宠物，成长值照发，pet_ids 为 NULL。 */
    @Test
    void accountMilestoneWithoutPetsStillAccrues() {
        listener.onAccountMilestone(new com.tailtopia.profile.event.AccountMilestoneCompletedEvent(7L,
                "FIRST_COLLECT_CONTENT", List.of(), List.of(), null));

        AccrualCommand c = accrued(1).get(0);
        assertThat(c.idemKey()).isEqualTo("MILESTONE_ACCT:7:FIRST_COLLECT_CONTENT");
        assertThat(c.refId()).isEqualTo("FIRST_COLLECT_CONTENT");
        assertThat(c.petIds()).isNull();
    }

    @Test
    void takedownReversesOnlyThePostPoints() {
        listener.onRemoved(new ContentRemovedEvent(5L, 7L, NOW));

        ReverseCommand r = reversed(1).get(0);
        assertThat(r.originalIdemKey()).isEqualTo("POST:5");
        assertThat(r.reason()).isEqualTo(ReverseReason.TAKEDOWN);
        assertThat(r.sourceKey()).isEqualTo("TAKEDOWN");
        verify(ledger, never()).accrue(any());
    }

    @Test
    void ledgerFailureIsSwallowedAndSecondCommandStillRuns() {
        postWithPet(5L);
        when(ledger.accrue(any())).thenThrow(new IllegalStateException("boom")).thenReturn(true);

        listener.onLiked(new ContentLikedEvent(5L, 3L, 7L, NOW));

        accrued(2);
    }
}
