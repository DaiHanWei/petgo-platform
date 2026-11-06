package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.domain.BannerTargetType;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.service.ShopBannerQueryService;
import com.tailtopia.shop.service.ShopBannerQueryService.BannerState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（V1.4.2 shop-admin Story 3.1 · AD-7 AC2）：banner 六态纯函数，且与 App 取数 {@code select} 共用生效判定 ——
 * 同一组输入下，后台标 LIVE 的集合 == App 下发的集合（顺序一致）。
 */
class BannerStateTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final Function<String, String> URL = k -> "https://cdn/" + k;

    private static ShopBanner banner(long id, boolean active, Instant start, Instant end, BannerTargetType type, String value) {
        ShopBanner b = ShopBanner.create("k" + id, 1056, 444, (int) (100 - id));
        ReflectionTestUtils.setField(b, "id", id);
        if (active) {
            b.activate();
        }
        b.schedule(start, end);
        b.retarget(type, value);
        return b;
    }

    @Test
    void sixStates() {
        List<ShopBanner> all = List.of(
                banner(1, true, null, null, BannerTargetType.NONE, null),
                banner(2, true, NOW.plusSeconds(3600), null, BannerTargetType.NONE, null),
                banner(3, true, null, NOW, BannerTargetType.NONE, null),
                banner(4, true, null, null, BannerTargetType.PRODUCT, "off-sale"),
                banner(5, true, null, null, BannerTargetType.COUPON, "not-claimable"),
                banner(6, false, null, null, BannerTargetType.NONE, null));
        Map<Long, BannerState> s = ShopBannerQueryService.states(all, NOW, Set.of(), Set.of(), URL, 6);
        assertThat(s).containsEntry(1L, BannerState.LIVE).containsEntry(2L, BannerState.PENDING)
                .containsEntry(3L, BannerState.EXPIRED).containsEntry(4L, BannerState.TARGET_INVALID)
                .containsEntry(5L, BannerState.TARGET_INVALID).containsEntry(6L, BannerState.INACTIVE);
    }

    @Test
    void onlyTheFirstSixValidOnesAreLiveTheRestAreOverLimit() {
        List<ShopBanner> all = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            all.add(banner(i, true, null, null, BannerTargetType.NONE, null));
        }
        Map<Long, BannerState> s = ShopBannerQueryService.states(all, NOW, Set.of(), Set.of(), URL, 6);
        assertThat(s.values().stream().filter(v -> v == BannerState.LIVE)).hasSize(6);
        assertThat(s).containsEntry(7L, BannerState.OVER_LIMIT).containsEntry(8L, BannerState.OVER_LIMIT);
    }

    @Test
    void liveSetEqualsWhatTheAppReceives() {
        List<ShopBanner> all = List.of(
                banner(1, true, null, null, BannerTargetType.PRODUCT, "p-on"),
                banner(2, true, null, null, BannerTargetType.PRODUCT, "p-off"),
                banner(3, true, null, null, BannerTargetType.COUPON, "c-ok"),
                banner(4, false, null, null, BannerTargetType.NONE, null),
                banner(5, true, null, null, BannerTargetType.EXTERNAL, "https://x.test"));
        Set<String> onSale = Set.of("p-on");
        Set<String> claimable = Set.of("c-ok");
        Map<Long, BannerState> s = ShopBannerQueryService.states(all, NOW, onSale, claimable, URL, 2);
        // App 取数：候选 = 已上架且在排期内（repository 已按同顺序过滤）
        List<ShopBanner> candidates = all.stream().filter(ShopBanner::isActive).filter(b -> b.isScheduledAt(NOW)).toList();
        List<Long> app = ShopBannerQueryService.select(candidates, onSale, claimable, URL, 2).stream()
                .map(l -> l.banner().getId()).toList();
        List<Long> admin = s.entrySet().stream().filter(e -> e.getValue() == BannerState.LIVE).map(Map.Entry::getKey).toList();
        assertThat(admin).isEqualTo(app).containsExactly(1L, 3L);
        assertThat(s).containsEntry(5L, BannerState.OVER_LIMIT).containsEntry(2L, BannerState.TARGET_INVALID);
    }

    @Test
    void missingImageUrlIsNotShownByTheAppSoItIsNotLive() {
        Map<Long, BannerState> s = ShopBannerQueryService.states(
                List.of(banner(1, true, null, null, BannerTargetType.NONE, null)), NOW, Set.of(), Set.of(), k -> null, 6);
        assertThat(s).containsEntry(1L, BannerState.TARGET_INVALID);
    }
}
