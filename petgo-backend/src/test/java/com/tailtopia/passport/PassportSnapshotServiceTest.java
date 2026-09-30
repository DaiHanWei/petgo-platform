package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.passport.domain.PassportSnapshot;
import com.tailtopia.passport.domain.PetPassport;
import com.tailtopia.passport.repository.PassportSnapshotRepository;
import com.tailtopia.passport.repository.PetPassportRepository;
import com.tailtopia.passport.service.PassportSnapshotService;
import com.tailtopia.passport.service.PassportTokenGenerator;
import com.tailtopia.passport.service.PetPassportService;
import com.tailtopia.passport.service.PlaceSetHash;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.service.PlaceIdentityQuery;
import com.tailtopia.place.service.PlaceStampQueryService;
import com.tailtopia.profile.dto.PetPassportSubject;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.4 · AC3 / AC7（L0）：发起的冻结 / 复用 / 409 / 422，列表只列已付，回看不下发 placeId。 */
class PassportSnapshotServiceTest {

    private static final long USER = 7L;
    private static final long PET = 70L;

    private final PetPassportService passports = mock(PetPassportService.class);
    private final PetPassportRepository passportRows = mock(PetPassportRepository.class);
    private final PassportSnapshotRepository snapshots = mock(PassportSnapshotRepository.class);
    private final PlaceStampQueryService stamps = mock(PlaceStampQueryService.class);
    private final PlaceIdentityQuery places = mock(PlaceIdentityQuery.class);
    private final PetProfileQueryService pets = mock(PetProfileQueryService.class);
    private final KeepsakePurchaseService purchases = mock(KeepsakePurchaseService.class);
    private final PassportTokenGenerator tokens = mock(PassportTokenGenerator.class);
    private final PassportSnapshotService service = new PassportSnapshotService(passports, passportRows, snapshots,
            stamps, places, new PlaceSetHash(places), pets, purchases, tokens);

    private final PetPassportSubject subject = new PetPassportSubject(PET, "Momo", "CAT", Instant.EPOCH);
    private PetPassport passport;

    @BeforeEach
    void setUp() {
        passport = org.springframework.beans.BeanUtils.instantiateClass(PetPassport.class);
        ReflectionTestUtils.setField(passport, "passportNo", "TT02P2600001");
        when(passports.ensureIssuedForOwner(USER))
                .thenReturn(Optional.of(new PetPassportService.IssuedPassport(subject, passport)));
        when(passportRows.findForUpdateByPetProfileId(PET)).thenReturn(Optional.of(passport));
        when(passportRows.findByPetProfileId(PET)).thenReturn(Optional.of(passport));
        when(pets.findPassportSubject(USER)).thenReturn(Optional.of(subject));
        when(places.resolveFinal(anyCollection())).thenAnswer(inv -> {
            Map<Long, Long> out = new HashMap<>();
            for (Object o : (java.util.Collection<?>) inv.getArgument(0)) {
                out.put((Long) o, (Long) o);
            }
            return out;
        });
        when(tokens.generate()).thenReturn("n".repeat(32));
        when(snapshots.saveAndFlush(any())).thenAnswer(inv -> {
            PassportSnapshot s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 99L);
            return s;
        });
        when(purchases.start(anyLong(), any(), any())).thenReturn(KeepsakePurchaseResponse.unlocked("kp"));
    }

    private void currentStamps(long... ids) {
        when(stamps.stampRefsOf(PET)).thenReturn(java.util.Arrays.stream(ids)
                .mapToObj(id -> PassportVersionQueryTest.ref(id, 2, PlaceAvailability.ACTIVE)).toList());
    }

    @Test
    void firstStartFreezesStampsAndDelegates() {
        currentStamps(1, 2);
        var resp = service.start(USER, PayChannel.PAWCOIN);
        assertThat(resp.snapshotToken()).isEqualTo("n".repeat(32));
        assertThat(resp.unlocked()).isTrue();
        ArgumentCaptor<PassportSnapshot> saved = ArgumentCaptor.forClass(PassportSnapshot.class);
        verify(snapshots).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStampCount()).isEqualTo(2);
        assertThat(saved.getValue().placeIds()).containsExactly(1L, 2L);
        assertThat(saved.getValue().frozenStamps().get(0).visitCount()).isEqualTo(2);
        ArgumentCaptor<KeepsakeRef> ref = ArgumentCaptor.forClass(KeepsakeRef.class);
        verify(purchases).start(eq(USER), ref.capture(), eq(PayChannel.PAWCOIN));
        assertThat(ref.getValue()).isEqualTo(new KeepsakeRef(KeepsakeSku.PASSPORT_SNAP, 99L, "n".repeat(32), PET, false));
        verify(passportRows).findForUpdateByPetProfileId(PET);
    }

    @Test
    void sameSetReusesLatestUnpaidSnapshot() {
        currentStamps(1, 2);
        PassportSnapshot unpaid = PassportVersionQueryTest.paid(2, 1);
        ReflectionTestUtils.setField(unpaid, "paidAt", null);
        ReflectionTestUtils.setField(unpaid, "id", 5L);
        when(snapshots.findByPetProfileIdAndPaidAtIsNullOrderByCreatedAtDescIdDesc(PET)).thenReturn(List.of(unpaid));
        service.start(USER, PayChannel.QRIS);
        verify(snapshots, never()).saveAndFlush(any());
        ArgumentCaptor<KeepsakeRef> ref = ArgumentCaptor.forClass(KeepsakeRef.class);
        verify(purchases).start(eq(USER), ref.capture(), eq(PayChannel.QRIS));
        assertThat(ref.getValue().refId()).isEqualTo(5L);
    }

    @Test
    void newStampAfterUnpaidCreatesSecondSnapshot() {
        currentStamps(1, 2, 3);
        PassportSnapshot unpaid = PassportVersionQueryTest.paid(1, 2);
        ReflectionTestUtils.setField(unpaid, "paidAt", null);
        when(snapshots.findByPetProfileIdAndPaidAtIsNullOrderByCreatedAtDescIdDesc(PET)).thenReturn(List.of(unpaid));
        service.start(USER, PayChannel.QRIS);
        verify(snapshots).saveAndFlush(any());
    }

    @Test
    void alreadyBoughtCurrentSetIs409() {
        currentStamps(1, 2);
        when(snapshots.findByPetProfileIdAndPaidAtIsNotNullOrderByPaidAtDescIdDesc(PET))
                .thenReturn(List.of(PassportVersionQueryTest.paid(1, 2)));
        assertThatThrownBy(() -> service.start(USER, PayChannel.PAWCOIN)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getType()).isEqualTo(ErrorTypes.KEEPSAKE_ALREADY_UNLOCKED));
        verify(purchases, never()).start(anyLong(), any(), any());
    }

    @Test
    void noStampsIs422AndNoPetIs404() {
        currentStamps();
        assertThatThrownBy(() -> service.start(USER, PayChannel.PAWCOIN)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        when(passports.ensureIssuedForOwner(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.start(8L, PayChannel.PAWCOIN)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void listOnlyPaid() {
        when(snapshots.findByPetProfileIdAndPaidAtIsNotNullOrderByPaidAtDescIdDesc(PET))
                .thenReturn(List.of(PassportVersionQueryTest.paid(1, 2)));
        var items = service.list(USER).items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).stampCount()).isEqualTo(2);
    }

    @Test
    void detailUsesFrozenStampsWithCurrentStampArtAndNeverExposesPlaceId() {
        PassportSnapshot s = PassportVersionQueryTest.paid(1, 2);
        when(snapshots.findByPublicTokenAndPetProfileId("tok", PET)).thenReturn(Optional.of(s));
        when(places.stampUrlsOf(anyCollection())).thenReturn(Map.of(2L, "https://cdn/s2.png"));
        var d = service.get(USER, "tok");
        assertThat(d.petName()).isEqualTo("Momo");
        assertThat(d.passportNo()).isEqualTo("TT02P2600001");
        assertThat(d.stamps()).extracting(x -> x.placeToken()).containsExactly("t1", "t2");
        assertThat(d.stamps().get(1).stampImageUrl()).isEqualTo("https://cdn/s2.png");
        assertThat(d.stamps().get(0).stampImageUrl()).isNull();
        assertThat(java.util.Arrays.stream(com.tailtopia.passport.dto.PassportSnapshotDetailResponse.Stamp.class
                .getRecordComponents()).map(c -> c.getName())).doesNotContain("placeId");
        verify(stamps, never()).stampRefsOf(anyLong()); // 不读实时打卡
    }

    @Test
    void unpaidOrUnknownDetailIs404() {
        PassportSnapshot s = PassportVersionQueryTest.paid(1);
        ReflectionTestUtils.setField(s, "paidAt", null);
        when(snapshots.findByPublicTokenAndPetProfileId("u", PET)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.get(USER, "u")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.get(USER, "x")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
