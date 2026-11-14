package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.notify.port.NotificationPetNames;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.PetFollowReader;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.service.AutoFollowTargets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * L0：关注更新提醒扇出（V1.5.0 onboarding-recall Story 5.1 · AC2 ~ AC5 / AC11 · OR-13 / OR-15）—— 私密不发、
 * 官方 / 虚拟 / 自动关注目标 / 非 active 作者不发、追更命中整体跳过、作者本人 / 非 active / 隐藏 / 拉黑排除、
 * 文案参数（昵称 + 第一只宠物名、isDiary）、单人异常不影响下一位、分批 500、开关关闭 no-op。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPostNotifyListenerTest {

    private static final long AUTHOR = 1L;
    private static final long POST = 555L;
    private static final long MOCHI = 70L;
    private static final long KOPI = 71L;

    @Mock FollowReader follows;
    @Mock PetFollowReader petFollows;
    @Mock UserHideRelationReader hideRelations;
    @Mock AccountQueryService accounts;
    @Mock UserPostNotifyWriter writer;
    @Mock NotificationPetNames petNames;

    @BeforeEach
    void defaults() {
        when(accounts.isActive(AUTHOR)).thenReturn(true);
        when(accounts.activeIdsAmong(anyCollection()))
                .thenAnswer(inv -> new HashSet<>(inv.<Collection<Long>>getArgument(0)));
        when(hideRelations.holdersHiding(eq(AUTHOR), anyCollection())).thenReturn(Set.of());
        when(hideRelations.blockedByHolder(eq(AUTHOR), anyCollection())).thenReturn(Set.of());
        when(petFollows.followersOfAny(anyCollection())).thenReturn(Map.of());
        when(accounts.findActorViews(List.of(AUTHOR)))
                .thenReturn(Map.of(AUTHOR, new ActorView(AUTHOR, "Budi", "tok", true)));
        when(petNames.namesOf(anyCollection())).thenReturn(Map.of(MOCHI, "Mochi", KOPI, "Kopi"));
        when(writer.write(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean())).thenReturn(true);
    }

    private UserPostNotifyListener listener(boolean enabled, String autoFollowIds) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("petNames", petNames);
        return new UserPostNotifyListener(follows, petFollows, hideRelations, accounts,
                new AutoFollowTargets(autoFollowIds), writer, bf.getBeanProvider(NotificationPetNames.class), enabled);
    }

    private UserPostNotifyListener listener() {
        return listener(true, "");
    }

    private static ContentPublishedEvent event(ContentType type, ContentVisibility vis, List<Long> petIds) {
        return new ContentPublishedEvent(POST, AUTHOR, type, petIds.isEmpty() ? null : petIds.get(0), 1L, vis,
                Instant.parse("2026-11-15T03:00:00Z"), petIds, List.of());
    }

    private static ContentPublishedEvent publicDiary(Long... pets) {
        return event(ContentType.GROWTH_MOMENT, ContentVisibility.PUBLIC, List.of(pets));
    }

    @Test
    @DisplayName("AC2：公开 Moment → 所有关注者各写一条（post 文案参数：昵称、无宠物名、isDiary=false）")
    void publicMomentNotifiesAllFollowers() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(2L, 3L));

        listener().onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of()));

        verify(writer).write(2L, POST, AUTHOR, "Budi", null, false);
        verify(writer).write(3L, POST, AUTHOR, "Budi", null, false);
        verifyNoInteractions(petFollows, petNames);
    }

    @Test
    @DisplayName("AC2：公开 Tips（KNOWLEDGE）也触发")
    void publicTipsNotifies() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(2L));
        listener().onContentPublished(event(ContentType.KNOWLEDGE, ContentVisibility.PUBLIC, List.of()));
        verify(writer).write(2L, POST, AUTHOR, "Budi", null, false);
    }

    @Test
    @DisplayName("AC10：公开 Diary → isDiary=true，宠物名取第一只关联宠物")
    void publicDiaryUsesFirstPetName() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(2L));
        listener().onContentPublished(publicDiary(KOPI, MOCHI));
        verify(writer).write(2L, POST, AUTHOR, "Budi", "Kopi", true);
    }

    @Test
    @DisplayName("AC2：私密帖什么都不发（连关注者都不查）")
    void privatePostIgnored() {
        listener().onContentPublished(event(ContentType.GROWTH_MOMENT, ContentVisibility.PRIVATE, List.of(MOCHI)));
        verifyNoInteractions(follows, writer);
    }

    @Test
    @DisplayName("AC3：官方号 / 虚拟号 / 自动关注目标 / 非 active 作者 → 不发")
    void excludedAuthors() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(2L));
        ContentPublishedEvent e = event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of());

        when(accounts.isOfficialAccount(AUTHOR)).thenReturn(true);
        listener().onContentPublished(e);
        when(accounts.isOfficialAccount(AUTHOR)).thenReturn(false);

        when(accounts.isVirtual(AUTHOR)).thenReturn(true);
        listener().onContentPublished(e);
        when(accounts.isVirtual(AUTHOR)).thenReturn(false);

        listener(true, "5, 1").onContentPublished(e);

        when(accounts.isActive(AUTHOR)).thenReturn(false);
        listener().onContentPublished(e);

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("AC11：开关关闭 → 整条 no-op")
    void disabledIsNoop() {
        listener(false, "").onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of()));
        verifyNoInteractions(follows, accounts, writer);
    }

    @Test
    @DisplayName("AC5 / FR-181：追更了帖子任一宠物的人整体跳过（只走 PET_DIARY_POSTED）")
    void petFollowersSkipped() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(2L, 3L, 4L));
        when(petFollows.followersOfAny(List.of(MOCHI, KOPI)))
                .thenReturn(Map.of(2L, List.of(MOCHI), 4L, List.of(KOPI)));

        listener().onContentPublished(publicDiary(MOCHI, KOPI));

        verify(writer).write(3L, POST, AUTHOR, "Budi", "Mochi", true);
        verify(writer, never()).write(eq(2L), anyLong(), anyLong(), any(), any(), anyBoolean());
        verify(writer, never()).write(eq(4L), anyLong(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("AC4：作者本人 / 非 active / 隐藏了作者 / 被作者拉黑 → 排除")
    void recipientExclusions() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(AUTHOR, 2L, 3L, 4L, 5L, 6L));
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of(AUTHOR, 2L, 4L, 5L, 6L));
        when(hideRelations.holdersHiding(eq(AUTHOR), anyCollection())).thenReturn(Set.of(4L));
        when(hideRelations.blockedByHolder(eq(AUTHOR), anyCollection())).thenReturn(Set.of(5L));

        listener().onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of()));

        ArgumentCaptor<Long> to = ArgumentCaptor.forClass(Long.class);
        verify(writer, times(2)).write(to.capture(), anyLong(), anyLong(), any(), any(), anyBoolean());
        assertThat(to.getAllValues()).containsExactly(2L, 6L);
    }

    @Test
    @DisplayName("单人写入异常只 warn，继续下一位")
    void singleFailureDoesNotStopFanOut() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(2L, 3L));
        when(writer.write(eq(2L), anyLong(), anyLong(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("db"));

        listener().onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of()));

        verify(writer).write(3L, POST, AUTHOR, "Budi", null, false);
    }

    @Test
    @DisplayName("分批 500：1201 位关注者 → 3 批有效性 / 隐藏 / 拉黑查询")
    void batchesOf500() {
        List<Long> many = new ArrayList<>();
        for (long i = 100; i < 100 + 1201; i++) {
            many.add(i);
        }
        when(follows.followerIdsOf(AUTHOR)).thenReturn(many);

        listener().onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of()));

        verify(accounts, times(3)).activeIdsAmong(anyCollection());
        verify(hideRelations, times(3)).holdersHiding(eq(AUTHOR), anyCollection());
        verify(hideRelations, times(3)).blockedByHolder(eq(AUTHOR), anyCollection());
        verify(writer, times(1201)).write(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("没有关注者 → 不查昵称、不写")
    void noFollowers() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of());
        listener().onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of()));
        verify(accounts, never()).findActorViews(any());
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("昵称 / 宠物名取失败 → 空昵称、按无宠物名渲染，照常写")
    void lookupFailuresDegrade() {
        when(follows.followerIdsOf(AUTHOR)).thenReturn(List.of(2L));
        when(accounts.findActorViews(any())).thenThrow(new IllegalStateException("db"));
        when(petNames.namesOf(anyCollection())).thenThrow(new IllegalStateException("db"));

        listener().onContentPublished(publicDiary(MOCHI));

        verify(writer).write(2L, POST, AUTHOR, "", null, true);
    }
}
