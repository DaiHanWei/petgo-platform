package com.tailtopia.ops.popup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.dto.OpsPopupCandidateView;
import com.tailtopia.ops.popup.repository.OpsPopupImpressionRepository;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（Mockito + 固定 Clock）：运营弹窗候选过滤（V1.3.4 Story 5.1 · AC3）。 */
class OpsPopupCandidateServiceTest {

    /** 2026-10-12T10:00:00Z = WIB 17:00。 */
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final long USER_ID = 42L;

    private final OpsPopupRepository popups = mock(OpsPopupRepository.class);
    private final OpsPopupImpressionRepository impressions = mock(OpsPopupImpressionRepository.class);
    private final ShopProductRepository products = mock(ShopProductRepository.class);
    private final ShopImageUrlResolver imageUrls = mock(ShopImageUrlResolver.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final com.tailtopia.auth.service.OpsAudienceQuery audienceQuery =
            mock(com.tailtopia.auth.service.OpsAudienceQuery.class);
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void defaults() {
        when(impressions.findShownPopupIds(anyLong(), anyCollection())).thenReturn(Set.of());
        when(impressions.findShownPopupIds(anyLong(), anyCollection(), any(Instant.class))).thenReturn(Set.of());
        when(audienceQuery.matches(anyLong(), any(), any())).thenReturn(true);
    }

    private OpsPopupCandidateService service(Instant now) {
        return new OpsPopupCandidateService(popups, impressions, new OpsTargetPolicy(products), imageUrls, accounts,
                audienceQuery, Clock.fixed(now, ZoneOffset.UTC));
    }

    private OpsPopup popup(OpsPopupAudience audience, OpsPopupFrequency frequency) {
        return popup(audience, frequency, "https://cdn.x/id.jpg", OpsTargetType.NONE, null);
    }

    private OpsPopup popup(OpsPopupAudience audience, OpsPopupFrequency frequency, String imageId,
            OpsTargetType targetType, String targetValue) {
        OpsPopup p = OpsPopup.create("internal", NOW.minus(Duration.ofDays(1)), null, 0, audience, 7, frequency,
                imageId, null, "Judul", "Title", "Isi", "Body", "Lihat", "See", targetType, targetValue);
        p.activate();
        ReflectionTestUtils.setField(p, "id", ids.incrementAndGet());
        return p;
    }

    private void loggedInRegisteredAt(Instant createdAt) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(USER_ID);
        when(u.getCreatedAt()).thenReturn(createdAt);
        when(accounts.findUserById(USER_ID)).thenReturn(Optional.of(u));
    }

    @SuppressWarnings("unchecked")
    private Collection<OpsPopupAudience> queriedAudiences() {
        ArgumentCaptor<Collection<OpsPopupAudience>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(popups).findLiveCandidates(eq(NOW), captor.capture());
        return captor.getValue();
    }

    private static List<String> tokens(List<OpsPopupCandidateView> views) {
        return views.stream().map(OpsPopupCandidateView::token).toList();
    }

    @Test
    void guestQueriesOnlyAllAndNeverTouchesImpressions() {
        OpsPopup all = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(all));

        List<OpsPopupCandidateView> out = service(NOW).candidates(null);

        assertThat(tokens(out)).containsExactly(all.getPublicToken());
        assertThat(queriedAudiences()).containsExactlyInAnyOrder(OpsPopupAudience.ALL);
        verifyNoInteractions(impressions);
        verifyNoInteractions(accounts);
    }

    // ===== V1.4.1 四维人群条件 =====

    private static com.tailtopia.ops.audience.AudienceFilters catOwners() {
        return new com.tailtopia.ops.audience.AudienceFilters(null,
                Set.of(com.tailtopia.profile.domain.PetType.CAT), null, null);
    }

    @Test
    void guestNeverGetsPopupWithAudienceFilters() {
        OpsPopup plain = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        OpsPopup targeted = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        targeted.setAudienceFilters(catOwners());
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(targeted, plain));

        assertThat(tokens(service(NOW).candidates(null))).containsExactly(plain.getPublicToken());
        verifyNoInteractions(audienceQuery);
    }

    @Test
    void loggedInUserGetsTargetedPopupOnlyWhenMatching() {
        loggedInRegisteredAt(NOW.minus(Duration.ofDays(30)));
        OpsPopup plain = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        OpsPopup targeted = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        targeted.setAudienceFilters(catOwners());
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(targeted, plain));

        when(audienceQuery.matches(eq(USER_ID), eq(catOwners()), eq(NOW))).thenReturn(true);
        assertThat(tokens(service(NOW).candidates(USER_ID)))
                .containsExactly(targeted.getPublicToken(), plain.getPublicToken());

        when(audienceQuery.matches(eq(USER_ID), eq(catOwners()), eq(NOW))).thenReturn(false);
        assertThat(tokens(service(NOW).candidates(USER_ID))).containsExactly(plain.getPublicToken());
    }

    @Test
    void unknownUserIsTreatedAsGuest() {
        when(accounts.findUserById(USER_ID)).thenReturn(Optional.empty());
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of());

        assertThat(service(NOW).candidates(USER_ID)).isEmpty();
        assertThat(queriedAudiences()).containsExactlyInAnyOrder(OpsPopupAudience.ALL);
        verifyNoInteractions(impressions);
    }

    @Test
    void loggedInQueriesAllThreeAudiences() {
        loggedInRegisteredAt(NOW.minus(Duration.ofDays(30)));
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of());

        service(NOW).candidates(USER_ID);

        assertThat(queriedAudiences()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(OpsPopupAudience.class));
    }

    @Test
    void newUserBoundaryExactlyNx24hIsNew() {
        OpsPopup newOnly = popup(OpsPopupAudience.NEW, OpsPopupFrequency.ONCE);
        OpsPopup oldOnly = popup(OpsPopupAudience.OLD, OpsPopupFrequency.ONCE);
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(newOnly, oldOnly));

        loggedInRegisteredAt(NOW.minus(Duration.ofHours(7 * 24)));
        assertThat(tokens(service(NOW).candidates(USER_ID))).containsExactly(newOnly.getPublicToken());

        loggedInRegisteredAt(NOW.minus(Duration.ofHours(7 * 24)).minusSeconds(1));
        assertThat(tokens(service(NOW).candidates(USER_ID))).containsExactly(oldOnly.getPublicToken());
    }

    @Test
    void onceAlreadyShownIsDropped() {
        loggedInRegisteredAt(NOW.minus(Duration.ofDays(30)));
        OpsPopup shown = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        OpsPopup fresh = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(shown, fresh));
        when(impressions.findShownPopupIds(eq(USER_ID), anyCollection())).thenReturn(Set.of(shown.getId()));

        assertThat(tokens(service(NOW).candidates(USER_ID))).containsExactly(fresh.getPublicToken());
    }

    @Test
    void dailyResetsAtWibMidnight() {
        loggedInRegisteredAt(NOW.minus(Duration.ofDays(30)));
        OpsPopup daily = popup(OpsPopupAudience.ALL, OpsPopupFrequency.DAILY);
        // 曝光在 2026-10-12T16:59:59Z = WIB 10-12 23:59:59。
        Instant shownAt = Instant.parse("2026-10-12T16:59:59Z");
        when(impressions.findShownPopupIds(eq(USER_ID), anyCollection(), any(Instant.class)))
                .thenAnswer(inv -> ((Instant) inv.getArgument(2)).isAfter(shownAt) ? Set.of() : Set.of(daily.getId()));

        Instant sameWibDay = Instant.parse("2026-10-12T16:59:59Z");
        when(popups.findLiveCandidates(eq(sameWibDay), anyCollection())).thenReturn(List.of(daily));
        assertThat(service(sameWibDay).candidates(USER_ID)).as("同一 WIB 日不再下发").isEmpty();

        Instant nextWibDay = Instant.parse("2026-10-12T17:00:00Z");
        when(popups.findLiveCandidates(eq(nextWibDay), anyCollection())).thenReturn(List.of(daily));
        assertThat(tokens(service(nextWibDay).candidates(USER_ID))).as("过 WIB 午夜恢复")
                .containsExactly(daily.getPublicToken());
    }

    @Test
    void startOfWibDayIsSeventeenZuluOfPreviousUtcDay() {
        assertThat(OpsPopupCandidateService.startOfWibDay(Instant.parse("2026-10-12T16:59:59Z")))
                .isEqualTo(Instant.parse("2026-10-11T17:00:00Z"));
        assertThat(OpsPopupCandidateService.startOfWibDay(Instant.parse("2026-10-12T17:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-12T17:00:00Z"));
    }

    @Test
    void delistedProductIsDroppedWithOneBatchQuery() {
        OpsPopup onSale = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://cdn.x/a.jpg",
                OpsTargetType.PRODUCT, "onSale");
        OpsPopup delisted = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://cdn.x/b.jpg",
                OpsTargetType.PRODUCT, "delisted");
        OpsPopup onSaleAgain = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://cdn.x/c.jpg",
                OpsTargetType.PRODUCT, "onSale");
        when(products.findActivePublicTokens(anyCollection())).thenReturn(Set.of("onSale"));
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(delisted, onSale, onSaleAgain));

        List<OpsPopupCandidateView> out = service(NOW).candidates(null);

        assertThat(tokens(out)).containsExactly(onSale.getPublicToken(), onSaleAgain.getPublicToken());
        assertThat(out.get(0).target().type()).isEqualTo("PRODUCT");
        assertThat(out.get(0).target().value()).isEqualTo("onSale");
        // 🎯 N+1 守卫：多个 PRODUCT 目标只查一次，且不走单条实体查询。
        verify(products, times(1)).findActivePublicTokens(anyCollection());
        verify(products, never()).findByPublicTokenAndActiveTrue(any());
    }

    @Test
    void noProductTargetsMeansNoProductQuery() {
        when(popups.findLiveCandidates(eq(NOW), anyCollection()))
                .thenReturn(List.of(popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE)));

        service(NOW).candidates(null);

        verifyNoInteractions(products);
    }

    @Test
    @SuppressWarnings("unchecked")
    void frequencyFilterRunsBeforeTargetCheck() {
        loggedInRegisteredAt(NOW.minus(Duration.ofDays(30)));
        OpsPopup shown = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://cdn.x/a.jpg",
                OpsTargetType.PRODUCT, "shownProduct");
        OpsPopup fresh = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://cdn.x/b.jpg",
                OpsTargetType.PRODUCT, "freshProduct");
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(shown, fresh));
        when(impressions.findShownPopupIds(eq(USER_ID), anyCollection())).thenReturn(Set.of(shown.getId()));
        when(products.findActivePublicTokens(anyCollection())).thenReturn(Set.of("freshProduct", "shownProduct"));

        assertThat(tokens(service(NOW).candidates(USER_ID))).containsExactly(fresh.getPublicToken());

        ArgumentCaptor<Collection<String>> queried = ArgumentCaptor.forClass(Collection.class);
        verify(products, times(1)).findActivePublicTokens(queried.capture());
        assertThat(queried.getValue()).as("已满频次的弹窗不再查其商品").containsExactly("freshProduct");
    }

    @Test
    void deletedUserIsTreatedAsGuest() {
        User u = mock(User.class);
        when(u.getId()).thenReturn(USER_ID);
        when(u.getDeletedAt()).thenReturn(NOW.minusSeconds(5));
        when(accounts.findUserById(USER_ID)).thenReturn(Optional.of(u));
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of());

        service(NOW).candidates(USER_ID);

        assertThat(queriedAudiences()).containsExactlyInAnyOrder(OpsPopupAudience.ALL);
        verifyNoInteractions(impressions);
    }

    @Test
    void externalNonHttpIsDropped() {
        OpsPopup bad = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://cdn.x/a.jpg",
                OpsTargetType.EXTERNAL, "javascript:alert(1)");
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(bad));

        assertThat(service(NOW).candidates(null)).isEmpty();
    }

    @Test
    void objectKeyImageWithoutCdnIsDropped() {
        OpsPopup key = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "ops/popup/a.jpg", OpsTargetType.NONE,
                null);
        when(imageUrls.publicUrl("ops/popup/a.jpg")).thenReturn(null);
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(key));

        assertThat(service(NOW).candidates(null)).isEmpty();
    }

    @Test
    void objectKeyImageWithCdnResolves() {
        OpsPopup key = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "ops/popup/a.jpg", OpsTargetType.NONE,
                null);
        when(imageUrls.publicUrl("ops/popup/a.jpg")).thenReturn("https://cdn.x/ops/popup/a.jpg");
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(key));

        assertThat(service(NOW).candidates(null).get(0).imageUrlId()).isEqualTo("https://cdn.x/ops/popup/a.jpg");
    }

    @Test
    void plainHttpImageIsDropped() {
        OpsPopup http = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "http://cdn.x/a.jpg",
                OpsTargetType.NONE, null);
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(http));

        assertThat(service(NOW).candidates(null)).isEmpty();
    }

    @Test
    void httpsImageWithoutHostIsDropped() {
        OpsPopup noHost = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https:///a.jpg",
                OpsTargetType.NONE, null);
        OpsPopup bareScheme = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://",
                OpsTargetType.NONE, null);
        OpsPopup unparsable = popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, "https://cdn.x/a b.jpg",
                OpsTargetType.NONE, null);
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(noHost, bareScheme, unparsable));

        assertThat(service(NOW).candidates(null)).isEmpty();
        verifyNoInteractions(imageUrls);
    }

    @Test
    void unusableEnImageAndBlankEnTextBecomeNull() {
        OpsPopup p = OpsPopup.create("internal", NOW.minusSeconds(60), null, 0, OpsPopupAudience.ALL, 7,
                OpsPopupFrequency.ONCE, "https://cdn.x/id.jpg", "http://cdn.x/en.jpg", "Judul", "  ", "Isi", "",
                "Lihat", " ", OpsTargetType.NONE, null);
        p.activate();
        ReflectionTestUtils.setField(p, "id", 1L);
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(List.of(p));

        OpsPopupCandidateView v = service(NOW).candidates(null).get(0);

        assertThat(v.imageUrlId()).isEqualTo("https://cdn.x/id.jpg");
        assertThat(v.imageUrlEn()).isNull();
        assertThat(v.titleId()).isEqualTo("Judul");
        assertThat(v.titleEn()).isNull();
        assertThat(v.bodyEn()).isNull();
        assertThat(v.buttonTextId()).isEqualTo("Lihat");
        assertThat(v.buttonTextEn()).isNull();
        assertThat(v.frequency()).isEqualTo("ONCE");
        assertThat(v.target().type()).isEqualTo("NONE");
        assertThat(v.target().value()).isNull();
        assertThat(v.token()).hasSize(22);
    }

    @Test
    void truncatesToTwentyAndKeepsRepositoryOrder() {
        List<OpsPopup> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(popup(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE));
        }
        when(popups.findLiveCandidates(eq(NOW), anyCollection())).thenReturn(many);

        List<OpsPopupCandidateView> out = service(NOW).candidates(null);

        assertThat(out).hasSize(OpsPopupCandidateService.MAX_ITEMS);
        assertThat(tokens(out)).containsExactlyElementsOf(
                many.subList(0, 20).stream().map(OpsPopup::getPublicToken).toList());
    }
}
