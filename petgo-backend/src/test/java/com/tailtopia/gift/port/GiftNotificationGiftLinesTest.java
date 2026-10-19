package com.tailtopia.gift.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.domain.GiftSource;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.service.GiftIdemKeys;
import com.tailtopia.gift.service.GiftTestFixtures;
import com.tailtopia.notify.port.NotificationGiftLines.GiftLine;
import com.tailtopia.notify.port.NotificationGiftLines.Query;
import com.tailtopia.pay.domain.PayChannel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 gift L2 第二轮（L0）：通知列表「送的是什么」—— 同种累加、混合不给、窗口外 / 别人 / 别的宠物不算。 */
class GiftNotificationGiftLinesTest {

    private static final Instant T0 = Instant.parse("2026-10-19T08:00:00Z");

    private final GiftOrderRepository repo = mock(GiftOrderRepository.class);
    private final GiftNotificationGiftLines port = new GiftNotificationGiftLines(repo);

    private static GiftOrder delivered(long id, long sender, long pet, long giftId, int qty, Instant at) {
        GiftOrder o = GiftOrder.pending("TOK" + id, sender, pet, 8L, "Mochi",
                GiftTestFixtures.gift(giftId, 1000, false, false, false, 0), qty, PayChannel.PAWCOIN,
                GiftSource.DIARY_TAB, GiftIdemKeys.send(sender, "r" + id), T0);
        ReflectionTestUtils.setField(o, "id", id);
        ReflectionTestUtils.setField(o, "status", GiftOrderStatus.DELIVERED);
        ReflectionTestUtils.setField(o, "deliveredAt", at);
        return o;
    }

    @Test
    void emptyQueriesDoNotHitRepository() {
        assertThat(port.linesOf(Map.of())).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void sameGiftSumsMixedGiftsOmittedOutsideWindowIgnored() {
        when(repo.findDeliveredForNotify(anyCollection(), anyCollection(), eq(GiftOrderStatus.DELIVERED), any()))
                .thenReturn(List.of(
                        delivered(1, 2L, 70L, 5L, 1, T0.minusSeconds(3600)), // 上一批（已读通知）的，窗口外
                        delivered(2, 2L, 70L, 5L, 5, T0.plusSeconds(1)),
                        delivered(3, 2L, 70L, 5L, 2, T0.plusSeconds(60)), // 同一人未读期间再送同一种 → 累加
                        delivered(4, 3L, 70L, 5L, 1, T0.plusSeconds(1)),
                        delivered(5, 3L, 70L, 6L, 1, T0.plusSeconds(2)), // 送了两种 → 不给
                        delivered(6, 4L, 71L, 6L, 10, T0.plusSeconds(1)),
                        delivered(7, 4L, 70L, 5L, 1, T0.plusSeconds(1)))); // 4 送给 70 的不算进 71 的条目

        Map<Long, GiftLine> out = port.linesOf(Map.of(
                100L, new Query(70L, 2L, T0),
                101L, new Query(70L, 3L, T0),
                102L, new Query(71L, 4L, T0),
                103L, new Query(72L, 9L, T0)));

        assertThat(out).containsOnlyKeys(100L, 102L);
        assertThat(out.get(100L)).isEqualTo(new GiftLine("Gift5", "Hadiah5", 7));
        assertThat(out.get(102L)).isEqualTo(new GiftLine("Gift6", "Hadiah6", 10));
        verify(repo).findDeliveredForNotify(anyCollection(), anyCollection(), eq(GiftOrderStatus.DELIVERED), eq(T0));
    }
}
