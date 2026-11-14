package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationAggregateDao;
import com.tailtopia.notify.repository.NotificationRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * L0：{@code sendRendered}（V1.5.0 onboarding-recall Story 5.2 · AC5）—— 按收件人语言渲染并<b>落库存渲染结果</b>（真实
 * properties：纪念日四语言）、码点截断不劈 emoji、push=false 只写通知 + 角标、push=true 推同一句、虚拟 / 官方号 / 分类关闭不推。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceRenderedTest {

    private static final long R = 11L;

    @Mock NotificationRepository repo;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock NotificationPusher pusher;
    @Mock AccountQueryService accountQuery;
    @Mock NotificationAggregateDao aggregates;
    @Mock NotificationActorRepository actors;
    @Mock InteractionCopyRenderer copy;
    @Mock InteractionActorResolver resolver;
    @Mock PushPreferenceService prefs;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:i18n/messages");
        ms.setDefaultEncoding(StandardCharsets.UTF_8.name());
        ms.setFallbackToSystemLocale(false);
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(accountQuery.localeOf(R)).thenReturn(Locale.forLanguageTag("id"));
        when(prefs.isEnabled(anyLong(), any())).thenReturn(true);
        service = new NotificationService(repo, redis, pusher, ms, accountQuery, aggregates, actors, copy, resolver,
                prefs);
    }

    private Notification anniversary(boolean push) {
        return service.sendRendered(R, NotificationType.COMPANION_ANNIVERSARY, "COMPANION_ANNIVERSARY",
                new Object[] {"Mochi", "30"}, "fbT", "fbB", "7", push);
    }

    @Test
    @DisplayName("ID：落库存渲染后的纪念日文案；push=true 推同一句；targetRef=petId")
    void idRenderedStoredAndPushed() {
        Notification n = anniversary(true);
        assertThat(n.getTitle()).isEqualTo("🎉 Hari ke-30 bareng Mochi!");
        assertThat(n.getBody()).isEqualTo(
                "Cheers buat 30 hari kalian! Abadikan gaya Mochi hari ini, satu foto aja cukup 📸");
        assertThat(n.getTargetRef()).isEqualTo("7");
        assertThat(n.getDeepLinkType()).isEqualTo("COMPANION_ANNIVERSARY");
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + R);
        verify(pusher).pushToUser(eq(R), eq("🎉 Hari ke-30 bareng Mochi!"), eq(n.getBody()),
                eq("COMPANION_ANNIVERSARY"), anyString(), eq("7"));
    }

    @Test
    @DisplayName("EN：撇号正确（Mochi's）；未知语言回退 EN（默认 messages.properties）")
    void enRendered() {
        when(accountQuery.localeOf(R)).thenReturn(Locale.forLanguageTag("en"));
        Notification n = anniversary(false);
        assertThat(n.getTitle()).isEqualTo("🎉 Day 30 with Mochi!");
        assertThat(n.getBody()).isEqualTo(
                "Cheers to 30 days together! Snap Mochi's look today, one photo is all it takes 📸");
        when(accountQuery.localeOf(R)).thenReturn(Locale.forLanguageTag("fr"));
        assertThat(anniversary(false).getTitle()).isEqualTo("🎉 Day 30 with Mochi!");
    }

    @Test
    @DisplayName("push=false → 只写通知 + 角标，不推")
    void pushFalseOnlyWrites() {
        anniversary(false);
        verify(repo).save(any());
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + R);
        verifyNoInteractions(pusher);
    }

    @Test
    @DisplayName("push=true 但虚拟号 / 官方号 / 分类关闭 → 不推，通知照写")
    void pushGates() {
        when(accountQuery.isVirtual(R)).thenReturn(true);
        anniversary(true);
        when(accountQuery.isVirtual(R)).thenReturn(false);
        when(accountQuery.isOfficialAccount(R)).thenReturn(true);
        anniversary(true);
        when(accountQuery.isOfficialAccount(R)).thenReturn(false);
        when(prefs.isEnabled(R, NotificationType.COMPANION_ANNIVERSARY)).thenReturn(false);
        anniversary(true);
        verify(repo, org.mockito.Mockito.times(3)).save(any());
        verify(pusher, never()).pushToUser(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺键回退兜底串")
    void missingKeyFallsBack() {
        Notification n = service.sendRendered(R, NotificationType.COMPANION_ANNIVERSARY, "NO_SUCH_KEY",
                new Object[] {"Mochi"}, "fbT", "fbB", "7", false);
        assertThat(n.getTitle()).isEqualTo("fbT");
        assertThat(n.getBody()).isEqualTo("fbB");
    }

    @Test
    @DisplayName("码点截断：title ≤ 120、body ≤ 255，不劈 emoji（代理对）")
    void truncatesByCodePoints() {
        String longTitle = "a".repeat(119) + "🎉🎉";
        String t = NotificationService.truncateCodePoints(longTitle, NotificationService.TITLE_MAX);
        assertThat(t.codePointCount(0, t.length())).isEqualTo(120);
        assertThat(t).endsWith("🎉");
        assertThat(Character.isHighSurrogate(t.charAt(t.length() - 1))).isFalse();
        assertThat(NotificationService.truncateCodePoints("🎉".repeat(300), NotificationService.BODY_MAX))
                .isEqualTo("🎉".repeat(255));
        assertThat(NotificationService.truncateCodePoints("short", 120)).isEqualTo("short");
        assertThat(NotificationService.truncateCodePoints(null, 120)).isNull();
    }
}
