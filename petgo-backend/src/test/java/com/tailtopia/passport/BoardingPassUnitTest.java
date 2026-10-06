package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.passport.domain.BoardingPassUnlock;
import com.tailtopia.passport.domain.PetPassport;
import com.tailtopia.passport.repository.BoardingPassUnlockRepository;
import com.tailtopia.passport.repository.PetPassportRepository;
import com.tailtopia.passport.service.BoardingPassAnalyticsListener;
import com.tailtopia.passport.service.BoardingPassGranter;
import com.tailtopia.passport.service.BoardingPassSeat;
import com.tailtopia.passport.service.BoardingPassService;
import com.tailtopia.passport.service.PassportTokenGenerator;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceStampRef;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.service.PlaceIdentityQuery;
import com.tailtopia.place.service.PlaceStampQueryService;
import com.tailtopia.profile.dto.PetPassportSubject;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsEventGuard;
import com.tailtopia.shared.error.AppException;
import java.sql.Connection;
import java.sql.Savepoint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.5 · L0：SEAT、列表 / 详情 / 解锁的服务口径、发放口四路径、埋点。 */
class BoardingPassUnitTest {

    private static final long USER = 7L;
    private static final long PET = 70L;

    // ---------- SEAT（AC4.3）----------

    @Test
    void seatIsDeterministicWellFormedAndSpread() {
        assertThat(BoardingPassSeat.of(1, 2)).isEqualTo(BoardingPassSeat.of(1, 2)).matches("^\\d{2}[A-F]$");
        Set<String> seen = new HashSet<>();
        for (long pet = 1; pet <= 30; pet++) {
            for (long place = 1; place <= 30; place++) {
                String s = BoardingPassSeat.of(pet, place);
                assertThat(s).matches("^\\d{2}[A-F]$");
                int row = Integer.parseInt(s.substring(0, 2));
                assertThat(row).isBetween(1, 40);
                seen.add(s);
            }
        }
        assertThat(seen.size()).as("900 组入参应分布到大量不同座位").isGreaterThan(150);
    }

    // ---------- 服务（AC3 / AC4 / AC5）----------

    private final PetProfileQueryService pets = mock(PetProfileQueryService.class);
    private final PetPassportRepository passportRows = mock(PetPassportRepository.class);
    private final PlaceStampQueryService stamps = mock(PlaceStampQueryService.class);
    private final PlaceIdentityQuery places = mock(PlaceIdentityQuery.class);
    private final BoardingPassUnlockRepository unlocks = mock(BoardingPassUnlockRepository.class);
    private final KeepsakePurchaseService purchases = mock(KeepsakePurchaseService.class);
    private final PassportTokenGenerator tokens = mock(PassportTokenGenerator.class);
    private final BoardingPassService service =
            new BoardingPassService(pets, passportRows, stamps, places, unlocks, purchases, tokens);

    private static PlaceStampRef card(long placeId, PlaceAvailability a, LocalDate last) {
        return new PlaceStampRef(placeId, new PlaceStamp("t" + placeId, "P" + placeId, PlaceType.CAFE, a,
                LocalDate.of(2026, 9, 1), 3, "Jl. " + placeId, null), last);
    }

    private static BoardingPassUnlock row(long id, long placeId, Instant unlocked, Instant superseded) {
        BoardingPassUnlock b = org.springframework.beans.BeanUtils.instantiateClass(BoardingPassUnlock.class);
        ReflectionTestUtils.setField(b, "id", id);
        ReflectionTestUtils.setField(b, "publicToken", "u" + id);
        ReflectionTestUtils.setField(b, "petProfileId", PET);
        ReflectionTestUtils.setField(b, "placeId", placeId);
        ReflectionTestUtils.setField(b, "unlockedAt", unlocked);
        ReflectionTestUtils.setField(b, "supersededAt", superseded);
        return b;
    }

    private void owner() {
        when(pets.findPassportSubject(USER)).thenReturn(Optional.of(new PetPassportSubject(PET, "Momo", "CAT", Instant.EPOCH)));
        PetPassport pp = org.springframework.beans.BeanUtils.instantiateClass(PetPassport.class);
        ReflectionTestUtils.setField(pp, "passportNo", "TT02P2600001");
        when(passportRows.findByPetProfileId(PET)).thenReturn(Optional.of(pp));
        when(places.resolveFinal(anyCollection())).thenAnswer(inv -> {
            Map<Long, Long> out = new HashMap<>();
            for (Object o : (java.util.Collection<?>) inv.getArgument(0)) {
                out.put((Long) o, (Long) o == 9L ? 1L : (Long) o); // 9 已并入 1
            }
            return out;
        });
    }

    @Test
    void listSortsByLastVisitAndMarksOnlyLiveUnlocks() {
        owner();
        when(stamps.stampRefsOf(PET)).thenReturn(List.of(card(1, PlaceAvailability.ACTIVE, LocalDate.of(2026, 9, 3)),
                card(2, PlaceAvailability.UNAVAILABLE, LocalDate.of(2026, 9, 20))));
        when(unlocks.findByPetProfileId(PET)).thenReturn(List.of(row(5, 1, Instant.EPOCH, null),
                row(6, 2, Instant.EPOCH, Instant.EPOCH)));
        var r = service.list(USER);
        assertThat(r.passportNo()).isEqualTo("TT02P2600001");
        assertThat(r.items()).extracting(i -> i.placeToken()).containsExactly("t2", "t1");
        assertThat(r.items().get(0).unlocked()).as("superseded 不算").isFalse();
        assertThat(r.items().get(1).unlocked()).isTrue();
    }

    @Test
    void detailResolvesMergedTokenAndHidesAddressWhenUnavailable() {
        owner();
        when(places.findIdByToken("old")).thenReturn(Optional.of(9L));
        when(places.findIdByToken("gone")).thenReturn(Optional.of(2L));
        when(stamps.stampRefsOf(PET)).thenReturn(List.of(card(1, PlaceAvailability.ACTIVE, LocalDate.of(2026, 9, 3)),
                card(2, PlaceAvailability.UNAVAILABLE, LocalDate.of(2026, 9, 20))));
        when(places.cardInfoOf(1L)).thenReturn(Optional.of(new PlaceIdentityQuery.PlaceCardInfo("t1", "Jakarta", "https://img")));
        when(places.cardInfoOf(2L)).thenReturn(Optional.of(new PlaceIdentityQuery.PlaceCardInfo("t2", "Bandung", null)));
        when(pets.findBreed(USER)).thenReturn(Optional.of("Anggora"));

        var d = service.detail(USER, "old");
        assertThat(d.placeToken()).isEqualTo("t1");
        assertThat(d.passenger()).isEqualTo("Momo");
        assertThat(d.breed()).isEqualTo("Anggora");
        assertThat(d.city()).isEqualTo("Jakarta");
        assertThat(d.addressText()).isEqualTo("Jl. 1");
        assertThat(d.placeImageUrl()).isEqualTo("https://img");
        assertThat(d.seat()).isEqualTo(BoardingPassSeat.of(PET, 1L));
        assertThat(d.unlocked()).isFalse();
        assertThat(d.unlockToken()).isNull();

        var g = service.detail(USER, "gone");
        assertThat(g.addressText()).isNull();
        assertThat(g.city()).isNull();
        assertThat(g.placeStatus()).isEqualTo(PlaceAvailability.UNAVAILABLE);
    }

    @Test
    void unknownTokenOrNoCheckinIs404() {
        owner();
        when(places.findIdByToken("x")).thenReturn(Optional.empty());
        when(places.findIdByToken("y")).thenReturn(Optional.of(3L));
        when(stamps.stampRefsOf(PET)).thenReturn(List.of(card(1, PlaceAvailability.ACTIVE, LocalDate.of(2026, 9, 3))));
        for (String t : List.of("x", "y")) {
            assertThatThrownBy(() -> service.detail(USER, t)).isInstanceOfSatisfying(AppException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThatThrownBy(() -> service.unlock(USER, t, PayChannel.QRIS)).isInstanceOfSatisfying(AppException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verify(purchases, never()).start(anyLong(), any(), any());
    }

    @Test
    void unlockUpsertsLocksAndDelegates() {
        owner();
        when(places.findIdByToken("t1")).thenReturn(Optional.of(1L));
        when(stamps.stampRefsOf(PET)).thenReturn(List.of(card(1, PlaceAvailability.UNAVAILABLE, LocalDate.of(2026, 9, 3))));
        when(tokens.generate()).thenReturn("n".repeat(32));
        when(unlocks.findForUpdate(PET, 1L)).thenReturn(Optional.of(row(5, 1, null, null)));
        when(purchases.start(anyLong(), any(), any())).thenReturn(KeepsakePurchaseResponse.unlocked("kp"));
        when(places.lockIfFinal(1L)).thenReturn(true);
        service.unlock(USER, "t1", PayChannel.PAWCOIN);
        verify(places).lockIfFinal(1L);
        verify(unlocks).insertIfAbsent(PET, 1L, "n".repeat(32));
        ArgumentCaptor<KeepsakeRef> ref = ArgumentCaptor.forClass(KeepsakeRef.class);
        verify(purchases).start(eq(USER), ref.capture(), eq(PayChannel.PAWCOIN));
        assertThat(ref.getValue()).isEqualTo(new KeepsakeRef(KeepsakeSku.BOARDING_PASS, 5L, "u5", PET, false));
    }

    /** 复审：合并与解锁并发 —— 锁到时已被并掉 → 重解析到保留方再锁；仍不行 → 409 不建行。 */
    @Test
    void unlockRacingMergeReResolvesOrRefuses() {
        owner();
        when(places.findIdByToken("t1")).thenReturn(Optional.of(1L));
        when(stamps.stampRefsOf(PET)).thenReturn(List.of(card(1, PlaceAvailability.ACTIVE, LocalDate.of(2026, 9, 3))));
        when(places.lockIfFinal(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.unlock(USER, "t1", PayChannel.QRIS)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(unlocks, never()).insertIfAbsent(anyLong(), anyLong(), anyString());
    }

    // ---------- 发放口（AC6）----------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private NamedParameterJdbcTemplate jdbc() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jt = mock(JdbcTemplate.class);
        Connection con = mock(Connection.class);
        when(con.setSavepoint()).thenReturn(mock(Savepoint.class));
        when(jdbc.getJdbcTemplate()).thenReturn(jt);
        when(jt.execute(any(ConnectionCallback.class)))
                .thenAnswer(inv -> ((ConnectionCallback) inv.getArgument(0)).doInConnection(con));
        return jdbc;
    }

    private static Map<String, Object> dbRow(Object superseded) {
        Map<String, Object> m = new HashMap<>();
        m.put("pet_profile_id", PET);
        m.put("place_id", 9L);
        m.put("superseded_at", superseded);
        return m;
    }

    @Test
    void granterFourPaths() throws Exception {
        NamedParameterJdbcTemplate jdbc = jdbc();
        when(tokens.generate()).thenReturn("g".repeat(32));
        BoardingPassGranter g = new BoardingPassGranter(jdbc, tokens);
        assertThat(g.sku()).isEqualTo(KeepsakeSku.BOARDING_PASS);

        when(jdbc.queryForList(startsWith("SELECT pet_profile_id"), anyMap())).thenReturn(List.of());
        assertThat(g.grant(5, 1)).as("删档").isEqualTo(GrantOutcome.REF_MISSING);

        when(jdbc.queryForList(startsWith("SELECT pet_profile_id"), anyMap())).thenReturn(List.of(dbRow(null)));
        when(jdbc.update(startsWith("UPDATE boarding_pass_unlocks SET unlocked_at = now() WHERE id"), anyMap())).thenReturn(1);
        assertThat(g.grant(5, 1)).isEqualTo(GrantOutcome.GRANTED);
        when(jdbc.update(startsWith("UPDATE boarding_pass_unlocks SET unlocked_at = now() WHERE id"), anyMap())).thenReturn(0);
        assertThat(g.grant(5, 1)).isEqualTo(GrantOutcome.ALREADY_UNLOCKED);

        // 付款期间被合并：9 → 1，对 (pet, 1) 那行发放
        when(jdbc.queryForList(startsWith("SELECT pet_profile_id"), anyMap())).thenReturn(List.of(dbRow(Instant.EPOCH)));
        when(jdbc.queryForList(startsWith("SELECT merged_into_id"), anyMap(), eq(Long.class)))
                .thenAnswer(inv -> ((Map<String, Object>) inv.getArgument(1)).get("id").equals(9L) ? List.of(1L) : List.of());
        when(jdbc.update(startsWith("UPDATE boarding_pass_unlocks SET unlocked_at = now() WHERE pet_profile_id"), anyMap()))
                .thenReturn(1);
        assertThat(g.grant(5, 1)).isEqualTo(GrantOutcome.GRANTED);
        ArgumentCaptor<Map> p = ArgumentCaptor.forClass(Map.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(startsWith("INSERT INTO boarding_pass_unlocks"), p.capture());
        assertThat(p.getValue()).containsEntry("place", 1L).containsEntry("pet", PET);
        when(jdbc.update(startsWith("UPDATE boarding_pass_unlocks SET unlocked_at = now() WHERE pet_profile_id"), anyMap()))
                .thenReturn(0);
        assertThat(g.grant(5, 1)).isEqualTo(GrantOutcome.ALREADY_UNLOCKED);

        when(jdbc.queryForList(startsWith("SELECT pet_profile_id"), anyMap())).thenThrow(new IllegalStateException("db"));
        assertThat(g.grant(5, 1)).as("不抛").isEqualTo(GrantOutcome.REF_MISSING);
    }

    // ---------- 埋点（AC10）----------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void analyticsCarriesPlaceTypeAndPriceOnly() {
        AnalyticsClient analytics = mock(AnalyticsClient.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of("PARK"));
        new BoardingPassAnalyticsListener(analytics, jdbc)
                .onUnlocked(new KeepsakeUnlockedEvent(USER, KeepsakeSku.BOARDING_PASS, 5L, 1000L, PayChannel.QRIS));
        ArgumentCaptor<Map> p = ArgumentCaptor.forClass(Map.class);
        verify(analytics).capture(anyString(), eq("boarding_pass_unlocked"), p.capture());
        assertThat(p.getValue()).isEqualTo(Map.of("place_type", "PARK", "price", 1000L));
        AnalyticsEventGuard guard = new AnalyticsEventGuard();
        assertThat(guard.allowsEvent("boarding_pass_unlocked")).isTrue();
        assertThat(guard.filterProperties(p.getValue())).isEqualTo(p.getValue());
    }
}
