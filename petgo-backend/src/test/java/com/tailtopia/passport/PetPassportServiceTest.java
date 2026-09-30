package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.passport.domain.PassportSource;
import com.tailtopia.passport.domain.PetPassport;
import com.tailtopia.passport.event.PassportIssuedEvent;
import com.tailtopia.passport.repository.PetPassportRepository;
import com.tailtopia.passport.service.PetPassportService;
import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.service.PlaceStampQueryService;
import com.tailtopia.profile.dto.PetPassportSubject;
import com.tailtopia.profile.service.CardNumberService;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V1.3.2 batch-a Story 1.2 · L0：护照签发（AC1）与护照页组装（AC3）。
 *
 * <p>号源 SQL 的各条件（物种段 / 建档之后 / 删档标 / 已占用）在 {@link PetPassportSqlShapeTest}
 * 钉 SQL 形状、在 L1 {@code PetPassportIntegrationTest} 真库验证。
 */
class PetPassportServiceTest {

    private static final long USER = 7L;
    private static final long PET = 70L;
    private static final Instant PET_CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private final PetPassportRepository repo = mock(PetPassportRepository.class);
    private final PetProfileQueryService pets = mock(PetProfileQueryService.class);
    private final CardNumberService numbers = mock(CardNumberService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlaceStampQueryService stamps = mock(PlaceStampQueryService.class);
    private final PetPassportService service =
            new PetPassportService(repo, pets, numbers, events, stamps);

    private static PetPassport row(String no, PassportSource source) {
        PetPassport p = org.springframework.beans.BeanUtils.instantiateClass(PetPassport.class);
        ReflectionTestUtils.setField(p, "id", 1L);
        ReflectionTestUtils.setField(p, "petProfileId", PET);
        ReflectionTestUtils.setField(p, "passportNo", no);
        ReflectionTestUtils.setField(p, "source", source);
        ReflectionTestUtils.setField(p, "issuedAt", Instant.now());
        return p;
    }

    private void subject(String petType) {
        when(pets.findPassportSubject(USER))
                .thenReturn(Optional.of(new PetPassportSubject(PET, "Momo", petType, PET_CREATED)));
    }

    @BeforeEach
    void setUp() {
        subject("CAT");
    }

    @Test
    void alreadyIssuedIsReturnedAsIs() {
        PetPassport existing = row("TT02P2600001", PassportSource.ISSUED);
        when(repo.findByPetProfileId(PET)).thenReturn(Optional.of(existing));

        assertThat(service.ensureIssued(PET, USER)).isSameAs(existing);
        verify(repo, never()).insertIfAbsent(anyLong(), anyString(), anyString(), any());
        verify(numbers, never()).allocatePassportNo(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void reusesKtpPassportNoWhenAvailable() {
        when(repo.findByPetProfileId(PET)).thenReturn(Optional.empty(),
                Optional.of(row("TT02P2600128", PassportSource.KTP)));
        when(pets.findReusableKtpPassportNo(USER, PET_CREATED, "CAT")).thenReturn(Optional.of("TT02P2600128"));
        when(repo.insertIfAbsent(eq(PET), eq("TT02P2600128"), eq("KTP"), any())).thenReturn(1);

        PetPassport p = service.ensureIssued(PET, USER);

        assertThat(p.getPassportNo()).isEqualTo("TT02P2600128");
        assertThat(p.getSource()).isEqualTo(PassportSource.KTP);
        verify(numbers, never()).allocatePassportNo(any());
        verify(events).publishEvent(new PassportIssuedEvent(USER, PassportSource.KTP));
    }

    @Test
    void issuesANewNumberWhenNoKtpCard() {
        subject("DOG");
        when(repo.findByPetProfileId(PET)).thenReturn(Optional.empty(),
                Optional.of(row("TT01P2600009", PassportSource.ISSUED)));
        when(pets.findReusableKtpPassportNo(USER, PET_CREATED, "DOG")).thenReturn(Optional.empty());
        when(numbers.allocatePassportNo("DOG")).thenReturn("TT01P2600009");
        when(repo.insertIfAbsent(eq(PET), eq("TT01P2600009"), eq("ISSUED"), any())).thenReturn(1);

        PetPassport p = service.ensureIssued(PET, USER);

        assertThat(p.getSource()).isEqualTo(PassportSource.ISSUED);
        verify(events).publishEvent(new PassportIssuedEvent(USER, PassportSource.ISSUED));
    }

    /** 并发：两次签发只一行 —— 输掉竞争的那次回读赢家、不发事件。 */
    @Test
    void losingTheRaceReturnsTheWinnerWithoutAnEvent() {
        PetPassport winner = row("TT02P2600002", PassportSource.ISSUED);
        when(repo.findByPetProfileId(PET)).thenReturn(Optional.empty(), Optional.of(winner));
        when(pets.findReusableKtpPassportNo(anyLong(), any(), anyString())).thenReturn(Optional.empty());
        when(numbers.allocatePassportNo("CAT")).thenReturn("TT02P2600003");
        when(repo.insertIfAbsent(anyLong(), anyString(), anyString(), any())).thenReturn(0);

        assertThat(service.ensureIssued(PET, USER)).isSameAs(winner);
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** KTP 号在查询与插入之间被占（撞 passport_no 唯一）→ 换新号重试一次，不中止事务。 */
    @Test
    void passportNoCollisionFallsBackToAFreshNumber() {
        when(repo.findByPetProfileId(PET)).thenReturn(Optional.empty(), Optional.empty(),
                Optional.of(row("TT02P2600050", PassportSource.ISSUED)));
        when(pets.findReusableKtpPassportNo(anyLong(), any(), anyString())).thenReturn(Optional.of("TT02P2600001"));
        when(repo.insertIfAbsent(eq(PET), eq("TT02P2600001"), eq("KTP"), any())).thenReturn(0);
        when(numbers.allocatePassportNo("CAT")).thenReturn("TT02P2600050");
        when(repo.insertIfAbsent(eq(PET), eq("TT02P2600050"), eq("ISSUED"), any())).thenReturn(1);

        assertThat(service.ensureIssued(PET, USER).getPassportNo()).isEqualTo("TT02P2600050");
        verify(events).publishEvent(new PassportIssuedEvent(USER, PassportSource.ISSUED));
    }

    @Test
    void petNotOwnedByUserIsRejected() {
        assertThatThrownBy(() -> service.ensureIssued(999L, USER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pageForOwnerWithoutPetIs404() {
        when(pets.findPassportSubject(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pageFor(USER)).isInstanceOf(AppException.class)
                .hasMessageContaining("宠物档案");
    }

    @Test
    void pageComposesStampsInOrderWithCount() {
        when(repo.findByPetProfileId(PET)).thenReturn(Optional.of(row("TT02P2600001", PassportSource.ISSUED)));
        when(stamps.stampsOf(PET)).thenReturn(List.of(
                new PlaceStamp("a".repeat(32), "Kopi", PlaceType.CAFE, PlaceAvailability.ACTIVE,
                        LocalDate.of(2026, 9, 1), 3, "Jl. Kopi 1", "https://cdn/place-stamps/1/s.png"),
                new PlaceStamp("b".repeat(32), "Taman", PlaceType.PARK, PlaceAvailability.UNAVAILABLE,
                        LocalDate.of(2026, 9, 20), 1, "Jl. Taman 2", null)));

        var page = service.pageFor(USER);

        assertThat(page.petName()).isEqualTo("Momo");
        assertThat(page.passportNo()).isEqualTo("TT02P2600001");
        assertThat(page.stampCount()).isEqualTo(2);
        assertThat(page.stamps()).extracting(s -> s.placeName()).containsExactly("Kopi", "Taman");
        assertThat(page.stamps().get(1).placeStatus()).isEqualTo(PlaceAvailability.UNAVAILABLE);
        // Story 1.4：专属章 URL 原样下发；无章 → null。
        assertThat(page.stamps().get(0).stampImageUrl()).isEqualTo("https://cdn/place-stamps/1/s.png");
        assertThat(page.stamps().get(1).stampImageUrl()).isNull();
        // Story 1.3：地址只对 ACTIVE 下发（即便上游误带了，DTO 层也再兜一次）。
        assertThat(page.stamps().get(0).addressText()).isEqualTo("Jl. Kopi 1");
        assertThat(page.stamps().get(1).addressText()).isNull();
    }
}
