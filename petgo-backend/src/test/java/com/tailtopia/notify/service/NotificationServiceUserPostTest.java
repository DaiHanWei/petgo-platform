package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.repository.FollowUpdatePushDao;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationAggregateDao;
import com.tailtopia.notify.repository.NotificationRepository;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * L0：关注更新通知（V1.5.0 onboarding-recall Story 5.1 · AC6 ~ AC10 · OR-13 / OR-14）—— 文案选择（Diary / Moment / 无宠物名，
 * 四语言真实 properties）、写入形状（type / targetRef / body / variant / 角标 / 日志初值）、重复事件不写、提交后推送决策顺序与
 * outcome 回写；以及 {@code PET_DIARY_POSTED} 多一道两类合计名额闸门（满了只留通知中心）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceUserPostTest {

    private static final long R = 11L;
    private static final long POST = 555L;
    private static final long AUTHOR = 9L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @Mock NotificationRepository repo;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock NotificationPusher pusher;
    @Mock org.springframework.context.MessageSource messageSource;
    @Mock AccountQueryService accountQuery;
    @Mock NotificationAggregateDao aggregates;
    @Mock NotificationActorRepository actors;
    @Mock InteractionCopyRenderer copy;
    @Mock InteractionActorResolver resolver;
    @Mock PushPreferenceService prefs;
    @Mock FollowUpdatePushDao dao;
    @Mock FollowUpdatePushQuota quota;
    @Mock AppVersionGate gate;

    @BeforeEach
    void defaults() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(accountQuery.localeOf(R)).thenReturn(Locale.forLanguageTag("id"));
        when(copy.userPostPublishedTitle(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn("Budi baru posting nih, intip yuk 👀");
        when(prefs.isEnabled(anyLong(), any())).thenReturn(true);
        when(quota.today()).thenReturn(TODAY);
        when(quota.tryClaim(anyLong())).thenReturn(true);
        when(quota.tryClaim(anyLong(), any())).thenReturn(true);
        when(dao.insertNotifyLog(anyLong(), anyLong(), anyLong(), any(), anyString())).thenReturn(true);
        when(dao.claimAuthorDaily(anyLong(), anyLong(), any())).thenReturn(true);
        when(gate.reportedV150OrLater(anyLong())).thenReturn(true);
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    private NotificationService service() {
        return new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy,
                resolver, prefs, null, dao, quota, gate);
    }

    // ===== 写入形状 =====

    @Test
    @DisplayName("AC10：通知落库形状——USER_POST_PUBLISHED、targetRef=postId、body 空、variant 空、角标 +1、日志初值 MUTED")
    void writesNotificationShapeAndLog() {
        Notification n = service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);

        assertThat(n.getType()).isEqualTo(NotificationType.USER_POST_PUBLISHED);
        assertThat(n.getDeepLinkType()).isEqualTo("USER_POST_PUBLISHED");
        assertThat(n.getTargetRef()).isEqualTo("555");
        assertThat(n.getBody()).isEmpty();
        assertThat(n.getVariant()).isNull();
        assertThat(n.getTitle()).isEqualTo("Budi baru posting nih, intip yuk 👀");
        assertThat(n.getDeepLinkToken()).hasSize(32);
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + R);
        verify(dao).insertNotifyLog(R, POST, AUTHOR, TODAY, "MUTED");
        verify(copy).userPostPublishedTitle("Budi", null, false, Locale.forLanguageTag("id"));
    }

    @Test
    @DisplayName("同收件人同帖已登记（重复事件）→ 不写通知、不加角标、不推")
    void duplicateEventWritesNothing() {
        when(dao.insertNotifyLog(anyLong(), anyLong(), anyLong(), any(), anyString())).thenReturn(false);

        assertThat(service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false)).isNull();

        verify(repo, never()).save(any());
        verifyNoInteractions(valueOps, pusher);
    }

    // ===== 推送决策顺序 =====

    @Test
    @DisplayName("全部通过 → 推送（标题同通知、正文空）并回写 PUSHED；顺序：分类 → 版本 → 同作者抢位 → 名额 → 推")
    void happyPathPushesInOrder() {
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);

        InOrder o = inOrder(prefs, gate, dao, quota, pusher);
        o.verify(prefs).isEnabled(R, NotificationType.USER_POST_PUBLISHED);
        o.verify(gate).reportedV150OrLater(R);
        o.verify(dao).claimAuthorDaily(R, AUTHOR, TODAY);
        o.verify(quota).tryClaim(R, TODAY);
        o.verify(pusher).pushToUserNow(eq(R), eq("Budi baru posting nih, intip yuk 👀"), eq(""),
                eq("USER_POST_PUBLISHED"), anyString(), eq("555"));
        o.verify(dao).updateOutcome(R, POST, "PUSHED");
    }

    @Test
    @DisplayName("虚拟号收件人 → NO_PUSH_ACCT，不查分类 / 版本、不占位不占名额")
    void virtualRecipient() {
        when(accountQuery.isVirtual(R)).thenReturn(true);
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);
        assertNoPushWithOutcome("NO_PUSH_ACCT");
        verify(prefs, never()).isEnabled(anyLong(), any());
    }

    @Test
    @DisplayName("官方号收件人 → NO_PUSH_ACCT")
    void officialRecipient() {
        when(accountQuery.isOfficialAccount(R)).thenReturn(true);
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);
        assertNoPushWithOutcome("NO_PUSH_ACCT");
    }

    @Test
    @DisplayName("AC8：分类关闭 → 不推、outcome 保持初值 MUTED（不回写）、不占名额")
    void categoryOff() {
        when(prefs.isEnabled(R, NotificationType.USER_POST_PUBLISHED)).thenReturn(false);
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);
        verifyNoInteractions(pusher, gate);
        verify(dao, never()).claimAuthorDaily(anyLong(), anyLong(), any());
        verify(quota, never()).tryClaim(anyLong(), any());
        verify(dao, never()).updateOutcome(anyLong(), anyLong(), anyString());
        verify(repo).save(any());
    }

    @Test
    @DisplayName("AC9：最后上报版本 < 1.5.0 → OLD_APP，通知照写")
    void oldApp() {
        when(gate.reportedV150OrLater(R)).thenReturn(false);
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);
        assertNoPushWithOutcome("OLD_APP");
        verify(repo).save(any());
    }

    @Test
    @DisplayName("AC6：今天已推过该作者 → AUTHOR_DUP，不占名额")
    void authorDup() {
        when(dao.claimAuthorDaily(R, AUTHOR, TODAY)).thenReturn(false);
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);
        assertNoPushWithOutcome("AUTHOR_DUP");
        verify(quota, never()).tryClaim(anyLong(), any());
    }

    @Test
    @DisplayName("AC7：两类合计名额已满 → CAP_REACHED")
    void capReached() {
        when(quota.tryClaim(R, TODAY)).thenReturn(false);
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);
        verify(pusher, never()).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        verify(dao).updateOutcome(R, POST, "CAP_REACHED");
    }

    @Test
    @DisplayName("推送投递失败 → FAILED（不重推）；推送抛异常 → FAILED 且不外抛")
    void pushFailure() {
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(false);
        service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false);
        verify(dao).updateOutcome(R, POST, "FAILED");

        org.mockito.Mockito.clearInvocations(dao);
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("im down"));
        service().sendUserPostPublished(R, POST + 1, AUTHOR, "Budi", null, false);
        verify(dao).updateOutcome(R, POST + 1, "FAILED");
    }

    @Test
    @DisplayName("outcome 回写失败只 warn、不外抛")
    void outcomeWriteFailureSwallowed() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db"))
                .when(dao).updateOutcome(anyLong(), anyLong(), anyString());
        assertThat(service().sendUserPostPublished(R, POST, AUTHOR, "Budi", null, false)).isNotNull();
    }

    private void assertNoPushWithOutcome(String outcome) {
        verify(pusher, never()).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        verify(pusher, never()).pushToUser(anyLong(), any(), any(), any(), any(), any());
        verify(quota, never()).tryClaim(anyLong(), any());
        verify(dao).updateOutcome(R, POST, outcome);
    }

    // ===== PET_DIARY_POSTED 名额闸门（AC7 / AC13）=====

    @Test
    @DisplayName("AC7：PET_DIARY_POSTED 在分类闸门之后占名额；名额满 → 只留通知中心、不推")
    void petDiaryCapReached() {
        when(copy.petDiaryPostedTitle(any(), any())).thenReturn("Mochi punya cerita baru");
        when(quota.tryClaim(R)).thenReturn(false);

        service().sendPetDiaryPosted(R, POST, 70L, "Mochi");

        verify(repo).save(any());
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + R);
        verifyNoInteractions(pusher, gate);
    }

    @Test
    @DisplayName("AC13：PET_DIARY_POSTED 名额未满 → 照推；不加版本门槛；分类关闭不占名额")
    void petDiaryUnderCapPushesAndCategoryOffDoesNotClaim() {
        when(copy.petDiaryPostedTitle(any(), any())).thenReturn("Mochi punya cerita baru");

        service().sendPetDiaryPosted(R, POST, 70L, "Mochi");
        verify(quota).tryClaim(R);
        verify(pusher).pushToUser(eq(R), eq("Mochi punya cerita baru"), eq(""), eq("PET_DIARY_POSTED"), anyString(),
                eq("555"));
        verifyNoInteractions(gate);

        org.mockito.Mockito.clearInvocations(quota, pusher);
        when(prefs.isEnabled(R, NotificationType.PET_DIARY_POSTED)).thenReturn(false);
        service().sendPetDiaryPosted(R, POST, 70L, "Mochi");
        verify(quota, never()).tryClaim(anyLong());
        verifyNoInteractions(pusher);
    }

    @Test
    @DisplayName("旧 10 参构造（无名额 bean）→ PET_DIARY_POSTED 照常推（名额闸门不生效）")
    void legacyConstructorWithoutQuota() {
        when(copy.petDiaryPostedTitle(any(), any())).thenReturn("t");
        new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy, resolver,
                prefs).sendPetDiaryPosted(R, POST, 70L, "Mochi");
        verify(pusher).pushToUser(eq(R), eq("t"), eq(""), eq("PET_DIARY_POSTED"), anyString(), eq("555"));
    }

    // ===== 文案（真实 properties）=====

    @Test
    @DisplayName("AC10：文案选择——Diary+宠物名 → diary；Diary 无宠物名 / Moment → post；四语言；撇号正确")
    void copySelectionAcrossLocales() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        InteractionCopyRenderer r = new InteractionCopyRenderer(accountQuery, null, ms);
        Locale id = Locale.forLanguageTag("id");
        Locale en = Locale.forLanguageTag("en");

        assertThat(r.userPostPublishedTitle("Budi", "Mochi", true, id)).isEqualTo("Budi baru update Diary Mochi, kepoin yuk 👀");
        assertThat(r.userPostPublishedTitle("Budi", "Mochi", true, en)).isEqualTo("Budi just updated Mochi's Diary, take a peek 👀");
        assertThat(r.userPostPublishedTitle("Budi", null, true, id)).isEqualTo("Budi baru posting nih, intip yuk 👀");
        assertThat(r.userPostPublishedTitle("Budi", " ", true, en)).isEqualTo("Budi just posted something new, take a peek 👀");
        assertThat(r.userPostPublishedTitle("Budi", "Mochi", false, en)).isEqualTo("Budi just posted something new, take a peek 👀");
        assertThat(r.userPostPublishedTitle("Budi", "Mochi", true, Locale.SIMPLIFIED_CHINESE)).contains("Budi").contains("Mochi");
        assertThat(r.userPostPublishedTitle("Budi", "Mochi", true, Locale.forLanguageTag("fr")))
                .isEqualTo("Budi just updated Mochi's Diary, take a peek 👀");
    }
}
