package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.pay.service.PawCoinWalletService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 1-4 · AC4.5 —— 拆礼物不入账：GiftOpenService 不注入成长值 / 钱包 / 通知。 */
class GiftOpenServiceDependencyTest {

    private static final List<Class<?>> FORBIDDEN =
            List.of(GrowthLedgerService.class, PawCoinWalletService.class, NotificationService.class);

    @Test
    void constructorsDoNotTakeLedgerWalletOrNotifications() {
        for (Constructor<?> c : GiftOpenService.class.getDeclaredConstructors()) {
            assertThat(Arrays.asList(c.getParameterTypes())).doesNotContainAnyElementsOf(FORBIDDEN);
        }
        for (Field f : GiftOpenService.class.getDeclaredFields()) {
            assertThat(FORBIDDEN).doesNotContain(f.getType());
        }
    }
}
