package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.notify.port.NotificationPetNames;
import com.tailtopia.social.read.PetFollowReader;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * L0：追更宠物有新 Diary 提醒（V1.4.2 social-topic · Story 1.2 · AC6）—— 类型 / 可见性 / 宠物过滤、作者有效性、
 * 收件人排除（作者本人 / 非 active / 隐藏作者 / 被作者 BLOCK）、已关注作者照收、一人一条取第一只未提醒的宠物、
 * 全部冲突不发、单人异常不影响下一位、分批 500。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PetDiaryNotifyListenerTest {

    private static final long AUTHOR = 1L;
    private static final long POST = 555L;
    private static final long MOCHI = 70L;
    private static final long KOPI = 71L;
    /** UTC 11-04 20:00 = WIB 11-05 03:00 —— 日志日期必须是 WIB 日。 */
    private static final Instant NOW = Instant.parse("2026-11-04T20:00:00Z");
    private static final LocalDate WIB_TODAY = LocalDate.of(2026, 11, 5);

    @Mock PetFollowReader petFollows;
    @Mock UserHideRelationReader hideRelations;
    @Mock AccountQueryService accounts;
    @Mock PetDiaryNotifyWriter writer;
    @Mock NotificationPetNames petNames;

    @BeforeEach
    void defaults() {
        when(accounts.isActive(AUTHOR)).thenReturn(true);
        // 默认：所有人 active、无隐藏 / 拉黑、日志都能插入
        when(accounts.activeIdsAmong(anyCollection())).thenAnswer(inv -> new HashSet<>(inv.<Collection<Long>>getArgument(0)));
        when(hideRelations.holdersHiding(eq(AUTHOR), anyCollection())).thenReturn(Set.of());
        when(hideRelations.blockedByHolder(eq(AUTHOR), anyCollection())).thenReturn(Set.of());
        when(writer.notifyIfFirstToday(anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(true);
        when(petNames.namesOf(anyCollection())).thenReturn(Map.of(MOCHI, "Mochi", KOPI, "Kopi"));
    }

    private PetDiaryNotifyListener listener() {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("petNames", petNames);
        return new PetDiaryNotifyListener(petFollows, hideRelations, accounts, writer,
                bf.getBeanProvider(NotificationPetNames.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ContentPublishedEvent diary(List<Long> petIds) {
        return event(ContentType.GROWTH_MOMENT, ContentVisibility.PUBLIC, petIds);
    }

    private static ContentPublishedEvent event(ContentType type, ContentVisibility visibility, List<Long> petIds) {
        return new ContentPublishedEvent(POST, AUTHOR, type, petIds.isEmpty() ? null : petIds.get(0), 1L, visibility,
                NOW, petIds);
    }

    private void followers(Map<Long, List<Long>> m) {
        when(petFollows.followersOfAny(anyCollection())).thenReturn(m);
    }

    // ===== 过滤：零交互 =====

    @Test
    void nonDiaryPrivateOrPetlessPostsDoNothing() {
        PetDiaryNotifyListener l = listener();
        l.onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC, List.of(MOCHI)));
        l.onContentPublished(event(ContentType.GROWTH_MOMENT, ContentVisibility.PRIVATE, List.of(MOCHI)));
        l.onContentPublished(diary(List.of()));
        verifyNoInteractions(petFollows, writer);
        verify(accounts, never()).isActive(anyLong());
    }

    @Test
    void inactiveAuthorSendsNothing() {
        when(accounts.isActive(AUTHOR)).thenReturn(false);
        listener().onContentPublished(diary(List.of(MOCHI)));
        verifyNoInteractions(petFollows, writer);
    }

    @Test
    void noFollowersWritesNothing() {
        followers(Map.of());
        listener().onContentPublished(diary(List.of(MOCHI)));
        verifyNoInteractions(writer);
    }

    // ===== 正常路径 =====

    @Test
    void followerGetsOneNotificationWithWibDateAndPetName() {
        followers(Map.of(10L, List.of(MOCHI)));
        listener().onContentPublished(diary(List.of(MOCHI)));
        verify(writer).notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY);
    }

    /** 已关注作者本人的追更者照常收 —— 本监听器根本不查关注关系。 */
    @Test
    void followerWhoAlsoFollowsAuthorStillReceives() {
        followers(Map.of(10L, List.of(MOCHI)));
        listener().onContentPublished(diary(List.of(MOCHI)));
        verify(writer).notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY);
    }

    @Test
    void twoFollowedPetsInOnePostSendOnlyOneWithFirstPet() {
        followers(Map.of(10L, List.of(MOCHI, KOPI)));
        listener().onContentPublished(diary(List.of(MOCHI, KOPI)));
        verify(writer, times(1)).notifyIfFirstToday(anyLong(), anyLong(), anyLong(), any(), eq(WIB_TODAY));
        verify(writer).notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY);
        verify(writer, never()).notifyIfFirstToday(10L, POST, KOPI, "Kopi", WIB_TODAY);
    }

    @Test
    void firstPetAlreadyNotifiedTodayFallsToSecond() {
        followers(Map.of(10L, List.of(MOCHI, KOPI)));
        when(writer.notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY)).thenReturn(false);
        listener().onContentPublished(diary(List.of(MOCHI, KOPI)));
        verify(writer).notifyIfFirstToday(10L, POST, KOPI, "Kopi", WIB_TODAY);
    }

    @Test
    void allPetsAlreadyNotifiedTodaySendsNothing() {
        followers(Map.of(10L, List.of(MOCHI, KOPI)));
        when(writer.notifyIfFirstToday(eq(10L), anyLong(), anyLong(), any(), eq(WIB_TODAY))).thenReturn(false);
        listener().onContentPublished(diary(List.of(MOCHI, KOPI)));
        verify(writer).notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY);
        verify(writer).notifyIfFirstToday(10L, POST, KOPI, "Kopi", WIB_TODAY);
    }

    // ===== 排除 =====

    @Test
    void authorInactiveHidingAndBlockedFollowersAreSkipped() {
        Map<Long, List<Long>> m = new LinkedHashMap<>();
        m.put(AUTHOR, List.of(MOCHI)); // 作者本人
        m.put(10L, List.of(MOCHI));    // 正常
        m.put(11L, List.of(MOCHI));    // DEACTIVATED
        m.put(12L, List.of(MOCHI));    // 追更者隐藏了作者
        m.put(13L, List.of(MOCHI));    // 作者 BLOCK 了追更者
        followers(m);
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of(10L, 12L, 13L));
        when(hideRelations.holdersHiding(eq(AUTHOR), anyCollection())).thenReturn(Set.of(12L));
        when(hideRelations.blockedByHolder(eq(AUTHOR), anyCollection())).thenReturn(Set.of(13L));

        listener().onContentPublished(diary(List.of(MOCHI)));

        verify(writer, times(1)).notifyIfFirstToday(anyLong(), anyLong(), anyLong(), any(), eq(WIB_TODAY));
        verify(writer).notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY);
        verify(writer, never()).notifyIfFirstToday(eq(AUTHOR), anyLong(), anyLong(), any(), any());
        verify(writer, never()).notifyIfFirstToday(eq(11L), anyLong(), anyLong(), any(), any());
        verify(writer, never()).notifyIfFirstToday(eq(12L), anyLong(), anyLong(), any(), any());
        verify(writer, never()).notifyIfFirstToday(eq(13L), anyLong(), anyLong(), any(), any());
        // 作者本人不进任何批量判定
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> batch = ArgumentCaptor.forClass(Collection.class);
        verify(accounts).activeIdsAmong(batch.capture());
        assertThat(batch.getValue()).doesNotContain(AUTHOR);
    }

    @Test
    void oneFailingFollowerDoesNotStopTheNext() {
        Map<Long, List<Long>> m = new LinkedHashMap<>();
        m.put(10L, List.of(MOCHI));
        m.put(11L, List.of(MOCHI));
        followers(m);
        when(writer.notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY)).thenThrow(new IllegalStateException("x"));

        listener().onContentPublished(diary(List.of(MOCHI)));

        verify(writer).notifyIfFirstToday(11L, POST, MOCHI, "Mochi", WIB_TODAY);
    }

    @Test
    void missingPetNameStillSendsWithNullName() {
        followers(Map.of(10L, List.of(MOCHI)));
        when(petNames.namesOf(anyCollection())).thenReturn(Map.of());
        listener().onContentPublished(diary(List.of(MOCHI)));
        verify(writer).notifyIfFirstToday(10L, POST, MOCHI, null, WIB_TODAY);
    }

    // ===== 分批 =====

    @Test
    void fanOutIsBatchedBy500() {
        Map<Long, List<Long>> m = new LinkedHashMap<>();
        for (long f = 1000; f < 1000 + 1201; f++) {
            m.put(f, List.of(MOCHI));
        }
        followers(m);

        listener().onContentPublished(diary(List.of(MOCHI)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(accounts, times(3)).activeIdsAmong(batches.capture());
        List<Integer> sizes = new ArrayList<>();
        batches.getAllValues().forEach(b -> sizes.add(b.size()));
        assertThat(sizes).containsExactly(500, 500, 201);
        verify(hideRelations, times(3)).holdersHiding(eq(AUTHOR), anyCollection());
        verify(hideRelations, times(3)).blockedByHolder(eq(AUTHOR), anyCollection());
        verify(writer, times(1201)).notifyIfFirstToday(anyLong(), eq(POST), eq(MOCHI), eq("Mochi"), eq(WIB_TODAY));
        verify(petNames, times(1)).namesOf(anyCollection());
    }

    /**
     * 复审 1：第一只宠物「插日志 + 写通知」事务失败（回滚，名额未占）→ warn 后回退到第二只，仍只发 1 条。
     */
    @Test
    void failedWriteOnFirstPetFallsBackToSecondPet() {
        followers(Map.of(10L, List.of(MOCHI, KOPI)));
        when(writer.notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY))
                .thenThrow(new IllegalStateException("insert failed"));

        listener().onContentPublished(diary(List.of(MOCHI, KOPI)));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(writer);
        order.verify(writer).notifyIfFirstToday(10L, POST, MOCHI, "Mochi", WIB_TODAY);
        order.verify(writer).notifyIfFirstToday(10L, POST, KOPI, "Kopi", WIB_TODAY);
        verify(writer, times(2)).notifyIfFirstToday(anyLong(), anyLong(), anyLong(), any(), any());
    }
}
