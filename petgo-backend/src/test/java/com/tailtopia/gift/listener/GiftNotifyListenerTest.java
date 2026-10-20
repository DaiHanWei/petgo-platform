package com.tailtopia.gift.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.event.GiftRefundedEvent;
import com.tailtopia.gift.event.GiftSentEvent;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.service.GiftTestFixtures;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.InteractionCopyRenderer;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.social.read.UserHideRelationReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * V1.4.0 gift Story 1.2 · AC4 / AC6（L0）：拉黑 / 隐藏抑制只在本监听器、推送单笔文案（礼物名按语言、数量 1 省略 ×1）、
 * 退回通知的 args / title / body / targetRef。文案用真实 {@code i18n/messages*.properties}。
 */
class GiftNotifyListenerTest {

    private static final long SENDER = GiftTestFixtures.SENDER;
    private static final long OWNER = GiftTestFixtures.OWNER;
    private static final long PET = GiftTestFixtures.PET;
    private static final Locale ID = Locale.forLanguageTag("id");

    private GiftOrderRepository orders;
    private NotificationService notifications;
    private UserHideRelationReader hides;
    private AccountQueryService accounts;
    private GiftNotifyListener listener;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        orders = mock(GiftOrderRepository.class);
        notifications = mock(NotificationService.class);
        hides = mock(UserHideRelationReader.class);
        accounts = mock(AccountQueryService.class);
        InteractionCopyRenderer copy = new InteractionCopyRenderer(accounts, hides, source);
        listener = new GiftNotifyListener(orders, notifications, copy, hides, accounts, source);
        when(accounts.findActorViews(anyCollection()))
                .thenReturn(Map.of(SENDER, new ActorView(SENDER, "Andi", "tokA", true)));
        when(accounts.localeOf(anyLong())).thenReturn(ID);
    }

    private GiftOrder order(int quantity) {
        GiftOrder o = GiftTestFixtures.order(41L, PayChannel.QRIS, quantity, 5_000);
        when(orders.findById(41L)).thenReturn(Optional.of(o));
        return o;
    }

    private static GiftSentEvent sent() {
        return new GiftSentEvent(41L, SENDER, OWNER, PET, 5_000);
    }

    @Test
    void hiddenSenderIsNotNotified() {
        order(1);
        when(hides.isHidden(OWNER, SENDER)).thenReturn(true);

        listener.onGiftSent(sent());

        verifyNoInteractions(notifications);
    }

    @Test
    void suppressionDirectionIsOwnerHidesSender() {
        order(1);
        when(hides.isHidden(SENDER, OWNER)).thenReturn(true); // 反方向不算

        listener.onGiftSent(sent());

        verify(notifications).sendGiftReceived(eq(OWNER), eq(SENDER), eq(PET), anyString());
    }

    @Test
    void selfGiftIsIgnoredDefensively() {
        listener.onGiftSent(new GiftSentEvent(41L, OWNER, OWNER, PET, 5_000));
        verifyNoInteractions(notifications, orders);
    }

    @Test
    void indonesianPushUsesIdNameAndOmitsTimesOne() {
        order(1);
        listener.onGiftSent(sent());
        verify(notifications).sendGiftReceived(OWNER, SENDER, PET, "Andi mengirim Hadiah5 untuk Mochi");
    }

    @Test
    void englishPushUsesEnNameAndShowsQuantity() {
        order(5);
        when(accounts.localeOf(OWNER)).thenReturn(Locale.ENGLISH);
        listener.onGiftSent(sent());
        verify(notifications).sendGiftReceived(OWNER, SENDER, PET, "Andi sent Gift5 ×5 to Mochi");
    }

    @Test
    void nullOrOtherLocaleFallsBackToIdName() {
        GiftOrder o = order(5);
        assertThat(GiftNotifyListener.giftLabel(o, null)).isEqualTo("Hadiah5 ×5");
        assertThat(GiftNotifyListener.giftLabel(o, Locale.SIMPLIFIED_CHINESE)).isEqualTo("Hadiah5 ×5");
        assertThat(GiftNotifyListener.giftLabel(o, ID)).isEqualTo("Hadiah5 ×5");
    }

    @Test
    void inactiveSenderStillWritesButWithoutPushTitle() {
        order(1);
        when(accounts.findActorViews(anyCollection()))
                .thenReturn(Map.of(SENDER, new ActorView(SENDER, null, null, false)));

        listener.onGiftSent(sent());

        verify(notifications).sendGiftReceived(eq(OWNER), eq(SENDER), eq(PET), isNull());
    }

    @Test
    void notifyFailureIsSwallowed() {
        order(1);
        when(notifications.sendGiftReceived(anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException());
        assertThatCode(() -> listener.onGiftSent(sent())).doesNotThrowAnyException();
        when(hides.isHidden(anyLong(), anyLong())).thenThrow(new IllegalStateException());
        assertThatCode(() -> listener.onGiftSent(sent())).doesNotThrowAnyException();
    }

    @Test
    void refundedNotifiesSenderWithRenderedCopyAndOrderToken() {
        GiftOrder o = order(5);
        ReflectionTestUtils.setField(o, "status", GiftOrderStatus.UNDELIVERED_REFUNDED);

        listener.onRefunded(new GiftRefundedEvent(41L));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notifications).sendWithCopy(eq(SENDER), eq(NotificationType.GIFT_UNDELIVERED_REFUND),
                eq("GIFT_UNDELIVERED_REFUND"), args.capture(), eq("Hadiah tidak terkirim"),
                eq("Hadiahmu untuk Mochi tidak terkirim (hewan ini sudah tidak ada). "
                        + "25.000 PawCoin sudah dikembalikan ke saldomu."),
                eq("TOK41"));
        assertThat(args.getValue()).containsExactly("Mochi", "25.000", "hewan ini sudah tidak ada");
        verify(notifications, never()).sendGiftReceived(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void refundedEnglishUsesCommaGrouping() {
        order(5);
        when(accounts.localeOf(SENDER)).thenReturn(Locale.ENGLISH);

        listener.onRefunded(new GiftRefundedEvent(41L));

        verify(notifications).sendWithCopy(eq(SENDER), eq(NotificationType.GIFT_UNDELIVERED_REFUND),
                eq("GIFT_UNDELIVERED_REFUND"), any(), eq("Gift not delivered"),
                eq("Your gift to Mochi wasn't delivered (this pet is no longer available). "
                        + "25,000 PawCoin has been returned to your balance."),
                eq("TOK41"));
    }

    @Test
    void refundNotifyFailureIsSwallowed() {
        order(1);
        when(notifications.sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException());
        assertThatCode(() -> listener.onRefunded(new GiftRefundedEvent(41L))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onRefunded(new GiftRefundedEvent(404L))).doesNotThrowAnyException();
    }

    @Test
    void amountFormatting() {
        assertThat(GiftNotifyListener.formatAmount(25_000, ID)).isEqualTo("25.000");
        assertThat(GiftNotifyListener.formatAmount(25_000, Locale.ENGLISH)).isEqualTo("25,000");
        assertThat(GiftNotifyListener.formatAmount(1_250_000, null)).isEqualTo("1.250.000");
    }

    @Test
    void bothListenersRunAfterCommit() throws NoSuchMethodException {
        for (Method m : new Method[] {GiftNotifyListener.class.getMethod("onGiftSent", GiftSentEvent.class),
                GiftNotifyListener.class.getMethod("onRefunded", GiftRefundedEvent.class)}) {
            assertThat(m.getAnnotation(TransactionalEventListener.class).phase())
                    .isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }
}
