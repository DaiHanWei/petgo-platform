package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.place.domain.GeoBox;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceCheckin;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.dto.PlaceCheckinRequest;
import com.tailtopia.place.dto.PlaceCheckinResponse;
import com.tailtopia.place.repository.PlaceCheckinPetRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.place.repository.PlaceVisitRepository;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V1.3.2 batch-a Story 1.1 · L0：打卡判定（AC2）。
 *
 * <p>距离边界 499 / 501 m、WIB 日界、合并后保留方当天已打卡、宠物归属、并发唯一冲突转 409、
 * 下架 404、响应与异常都不带距离。
 */
class PlaceCheckinServiceTest {

    private static final double LAT = -6.2;
    private static final double LNG = 106.8;
    private static final long USER = 7L;
    private static final long PET = 70L;
    private static final long PLACE_ID = 500L;

    private final PlaceRepository places = mock(PlaceRepository.class);
    private final PlaceVisitRepository checkins = mock(PlaceVisitRepository.class);
    private final PlaceCheckinPetRepository checkinPets = mock(PlaceCheckinPetRepository.class);
    private final PetProfileQueryService pets = mock(PetProfileQueryService.class);
    private final PlaceTokenGenerator tokens = new PlaceTokenGenerator();

    private Place place;

    /** WIB 2026-09-30 10:00 = UTC 03:00。 */
    private static final Instant NOON_WIB = Instant.parse("2026-09-30T03:00:00Z");

    private PlaceCheckinService serviceAt(Instant now) {
        return new PlaceCheckinService(places, checkins, checkinPets, pets, tokens,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        place = Place.mark("keeptoken0000000000000000000000k", "Kopi Kucing", PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE), LAT, LNG, "Jl. A", null, 1L, "Jakarta");
        ReflectionTestUtils.setField(place, "id", PLACE_ID);
        when(places.resolveForView(anyString())).thenReturn(Optional.of(place));
        when(pets.findPetIdByOwner(USER)).thenReturn(Optional.of(PET));
        when(checkins.saveAndFlush(any(PlaceCheckin.class))).thenAnswer(inv -> {
            PlaceCheckin c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 9001L);
            return c;
        });
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    /** 正北偏移 {@code meters} 米的纬度（与 GeoBox 同一地球半径，结果精确到亚米）。 */
    private static double latNorth(double meters) {
        return LAT + Math.toDegrees(meters / GeoBox.EARTH_RADIUS_METERS);
    }

    private static PlaceCheckinRequest at(double lat) {
        return new PlaceCheckinRequest(lat, LNG, List.of(PET));
    }

    private static void assertProblem(Throwable t, java.net.URI type, HttpStatus status) {
        assertThat(t).isInstanceOf(AppException.class);
        AppException e = (AppException) t;
        assertThat(e.getType()).isEqualTo(type);
        assertThat(e.getStatus()).isEqualTo(status);
    }

    @Test
    void within499MetersPasses() {
        PlaceCheckinResponse r = serviceAt(NOON_WIB).checkIn("t", USER, at(latNorth(499)));

        assertThat(r.placeToken()).isEqualTo(place.getPublicToken());
        assertThat(r.visitDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(r.checkinToken()).hasSize(32);
    }

    @Test
    void beyond501MetersIsTooFarAndDoesNotLeakTheDistance() {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                () -> serviceAt(NOON_WIB).checkIn("t", USER, at(latNorth(501))));

        assertProblem(t, ErrorTypes.CHECKIN_TOO_FAR, HttpStatus.UNPROCESSABLE_ENTITY);
        // 🔴 detail 不能出现任何数字（距离值 / 半径都不回），防试探边界。
        assertThat(t.getMessage()).doesNotContainPattern("\\d");
        verify(checkins, never()).saveAndFlush(any());
    }

    @Test
    void missingOrInvalidCoordinatesAre422WithoutEchoingThem() {
        PlaceCheckinService s = serviceAt(NOON_WIB);
        for (PlaceCheckinRequest bad : List.of(
                new PlaceCheckinRequest(null, LNG, List.of(PET)),
                new PlaceCheckinRequest(LAT, null, List.of(PET)),
                new PlaceCheckinRequest(91d, LNG, List.of(PET)))) {
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> s.checkIn("t", USER, bad));
            assertProblem(t, ErrorTypes.VALIDATION, HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(t.getMessage()).doesNotContain("106").doesNotContain("-6");
        }
    }

    @Test
    void delistedOrMissingPlaceIsPlain404() {
        when(places.resolveForView("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(NOON_WIB).checkIn("gone", USER, at(LAT)))
                .satisfies(t -> assertProblem(t, ErrorTypes.NOT_FOUND, HttpStatus.NOT_FOUND));
    }

    @Test
    void noPetProfileIs422NoPet() {
        when(pets.findPetIdByOwner(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(NOON_WIB).checkIn("t", USER, at(LAT)))
                .satisfies(t -> assertProblem(t, ErrorTypes.CHECKIN_NO_PET, HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void someoneElsesPetOrEmptyListIs403() {
        PlaceCheckinService s = serviceAt(NOON_WIB);
        for (List<Long> ids : List.of(List.of(999L), List.of(PET, 999L), List.<Long>of())) {
            assertThatThrownBy(() -> s.checkIn("t", USER, new PlaceCheckinRequest(LAT, LNG, ids)))
                    .satisfies(t -> assertProblem(t, ErrorTypes.CHECKIN_PET_FORBIDDEN, HttpStatus.FORBIDDEN));
        }
    }

    @Test
    void alreadyCheckedInTodayAtCurrentPlaceIs409() {
        // 合并后：原场所的历史打卡已被后台改挂到保留方 → 按当前 place_id 查即命中。
        when(checkins.existsForPetOnDay(PET, PLACE_ID, LocalDate.of(2026, 9, 30))).thenReturn(true);

        assertThatThrownBy(() -> serviceAt(NOON_WIB).checkIn("mergedToken", USER, at(LAT)))
                .satisfies(t -> assertProblem(t, ErrorTypes.CHECKIN_ALREADY_TODAY, HttpStatus.CONFLICT));
        verify(checkins, never()).saveAndFlush(any());
    }

    @Test
    void mergedPlaceRecordsOnTheKeptPlace() {
        // resolveForView 已把 MERGED 解析到保留方 —— 写入的 place_id / origin_place_id 都是保留方。
        serviceAt(NOON_WIB).checkIn("mergedToken", USER, at(LAT));

        org.mockito.ArgumentCaptor<PlaceCheckin> cap = org.mockito.ArgumentCaptor.forClass(PlaceCheckin.class);
        verify(checkins).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getPlaceId()).isEqualTo(PLACE_ID);
        assertThat(cap.getValue().getOriginPlaceId()).isEqualTo(PLACE_ID);
    }

    @Test
    void wibDayBoundary() {
        // WIB 23:59 = UTC 16:59 → 当天；WIB 次日 00:00 = UTC 17:00 → 次日。
        Instant lastMinute = Instant.parse("2026-09-30T16:59:00Z");
        Instant nextDay = Instant.parse("2026-09-30T17:00:00Z");

        assertThat(serviceAt(lastMinute).checkIn("t", USER, at(LAT)).visitDate())
                .isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(serviceAt(nextDay).checkIn("t", USER, at(LAT)).visitDate())
                .isEqualTo(LocalDate.of(2026, 10, 1));

        // 当天已打卡只挡当天，次日 00:00 放行。
        when(checkins.existsForPetOnDay(PET, PLACE_ID, LocalDate.of(2026, 9, 30))).thenReturn(true);
        assertThatThrownBy(() -> serviceAt(lastMinute).checkIn("t", USER, at(LAT)))
                .satisfies(t -> assertProblem(t, ErrorTypes.CHECKIN_ALREADY_TODAY, HttpStatus.CONFLICT));
        assertThat(serviceAt(nextDay).checkIn("t", USER, at(LAT)).visitDate())
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void concurrentUniqueViolationBecomes409Not500() {
        when(checkinPets.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("x",
                new org.hibernate.exception.ConstraintViolationException("dup", null,
                        PlaceCheckinService.DAILY_UNIQUE_CONSTRAINT)));

        assertThatThrownBy(() -> serviceAt(NOON_WIB).checkIn("t", USER, at(LAT)))
                .satisfies(t -> assertProblem(t, ErrorTypes.CHECKIN_ALREADY_TODAY, HttpStatus.CONFLICT));
    }

    /** 复审：别的完整性错误（并发删档撞 FK 等）不能被说成「今天已打卡」。 */
    @Test
    void otherIntegrityViolationsAreNotReportedAsAlreadyToday() {
        when(checkinPets.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("x",
                new org.hibernate.exception.ConstraintViolationException("fk", null,
                        "place_checkin_pets_pet_profile_id_fkey")));

        assertThatThrownBy(() -> serviceAt(NOON_WIB).checkIn("t", USER, at(LAT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newStampVersusRepeatVisit() {
        when(checkins.countForPetAtPlace(PET, PLACE_ID)).thenReturn(0L, 1L);
        PlaceCheckinResponse first = serviceAt(NOON_WIB).checkIn("t", USER, at(LAT));
        assertThat(first.isNewStamp()).isTrue();
        assertThat(first.visitCount()).isEqualTo(1);

        when(checkins.countForPetAtPlace(PET, PLACE_ID)).thenReturn(2L, 3L);
        PlaceCheckinResponse again = serviceAt(NOON_WIB).checkIn("t", USER, at(LAT));
        assertThat(again.isNewStamp()).isFalse();
        assertThat(again.visitCount()).isEqualTo(3);
    }

    @Test
    void checkedInTodayUsesTheWibDay() {
        when(checkins.existsForUserOnDay(eq(USER), eq(PLACE_ID), eq(LocalDate.of(2026, 10, 1))))
                .thenReturn(true);

        assertThat(serviceAt(Instant.parse("2026-09-30T17:00:00Z")).checkedInToday(USER, PLACE_ID)).isTrue();
        assertThat(serviceAt(Instant.parse("2026-09-30T16:59:00Z")).checkedInToday(USER, PLACE_ID)).isFalse();
        verify(checkins, never()).existsForPetOnDay(anyLong(), anyLong(), any());
    }
}
