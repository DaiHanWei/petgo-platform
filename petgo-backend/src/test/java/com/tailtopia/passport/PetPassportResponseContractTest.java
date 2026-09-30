package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.passport.dto.PassportStampView;
import com.tailtopia.passport.dto.PetPassportResponse;
import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * V1.3.2 Story 1.2 · L0 契约金标：护照接口对外 JSON（AC3）。Dart 侧
 * {@code test/pet_passport/pet_passport_wire_contract_test.dart} 用同一套键。
 */
class PetPassportResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static PetPassportResponse sample() {
        PassportStampView v = PassportStampView.of(new PlaceStamp("a".repeat(32), "Kopi", PlaceType.CAFE,
                PlaceAvailability.ACTIVE, LocalDate.of(2026, 9, 22), 2, "Jl. Senopati 75"));
        return new PetPassportResponse("Momo", "TT02P2600128", 1, List.of(v));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactContractKeys() {
        Map<String, Object> m = wire(sample());
        assertThat(m.keySet()).isEqualTo(Set.of("petName", "passportNo", "stampCount", "stamps"));
        Map<String, Object> stamp = ((List<Map<String, Object>>) m.get("stamps")).get(0);
        // stampImageUrl 本 story 恒 null → NON_NULL 省略。
        assertThat(stamp.keySet()).isEqualTo(Set.of("placeToken", "placeName", "placeType", "placeStatus",
                "firstVisitDate", "visitCount", "addressText"));
        assertThat(stamp.get("addressText")).isEqualTo("Jl. Senopati 75");
        assertThat(stamp.get("placeStatus")).isEqualTo("ACTIVE");
        assertThat(stamp.get("firstVisitDate")).isEqualTo("2026-09-22");
        assertThat(stamp.get("placeType")).isEqualTo("CAFE");
    }

    /** Story 1.3 · AC2：UNAVAILABLE 的章不带地址（省略该键）。 */
    @Test
    @SuppressWarnings("unchecked")
    void unavailableStampOmitsAddress() {
        PassportStampView v = PassportStampView.of(new PlaceStamp("b".repeat(32), "Taman", PlaceType.PARK,
                PlaceAvailability.UNAVAILABLE, LocalDate.of(2026, 9, 22), 1, "Jl. Rahasia 9"));
        Map<String, Object> m = wire(v);
        assertThat(m).doesNotContainKey("addressText");
        assertThat(m.get("placeStatus")).isEqualTo("UNAVAILABLE");
    }

    /** 🔴 AC3.3：不下发任何总数分母。 */
    @Test
    @SuppressWarnings("unchecked")
    void noDenominatorKeysAnywhere() {
        Map<String, Object> m = wire(sample());
        Map<String, Object> stamp = ((List<Map<String, Object>>) m.get("stamps")).get(0);
        for (String banned : List.of("total", "totalStamps", "max", "maxStamps", "capacity", "remaining",
                "totalPlaces")) {
            assertThat(m).doesNotContainKey(banned);
            assertThat(stamp).doesNotContainKey(banned);
        }
        // 对外不外露自增 id。
        assertThat(m).doesNotContainKey("id").doesNotContainKey("petId");
        assertThat(stamp).doesNotContainKey("placeId");
    }
}
