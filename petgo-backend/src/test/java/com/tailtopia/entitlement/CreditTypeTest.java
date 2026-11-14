package com.tailtopia.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.entitlement.domain.CreditFamily;
import com.tailtopia.entitlement.domain.CreditType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0：Story 4-1 AC2.5。 */
class CreditTypeTest {

    @Test
    void wireRoundTrip() {
        for (CreditType t : CreditType.values()) {
            assertThat(CreditType.fromWire(t.wire())).contains(t);
        }
        assertThat(CreditType.KTP_HD_STUDENT_MALAM.wire()).isEqualTo("KTP_HD:STUDENT_MALAM");
        assertThat(CreditType.GIFT_1K.wire()).isEqualTo("GIFT_1K");
    }

    @Test
    void fromWireUnknownIsEmpty() {
        assertThat(CreditType.fromWire("KTP_HD:FOO")).isEmpty();
        assertThat(CreditType.fromWire(null)).isEmpty();
        assertThat(CreditType.fromWire("KTP_HD_KTP")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "KTP, NULL, KTP_HD_KTP",
            "PASSPORT, BINTANG, KTP_HD_PASSPORT",
            "KITAS, NULL, KTP_HD_KITAS",
            "SIM, NULL, KTP_HD_SIM",
            "STUDENT, BINTANG, KTP_HD_STUDENT_BINTANG",
            "STUDENT, MALAM, KTP_HD_STUDENT_MALAM",
            "STUDENT, CERIA, KTP_HD_STUDENT_CERIA",
            "STUDENT, KLASIK, KTP_HD_STUDENT_KLASIK",
            "STUDENT, NULL, KTP_HD_STUDENT_KLASIK"})
    void ktpHdOfValid(String type, String variant, CreditType expected) {
        assertThat(CreditType.ktpHdOf(type, variant)).isEqualTo(expected);
    }

    @Test
    void ktpHdOfInvalid() {
        assertThatThrownBy(() -> CreditType.ktpHdOf("PET_PASSPORT", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditType.ktpHdOf("STUDENT", "NEON")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditType.ktpHdOf(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditType.ktpHdOf("ANY", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void family() {
        List<CreditType> all = List.of(CreditType.values());
        assertThat(all.subList(0, 9)).allMatch(t -> t.family() == CreditFamily.KTP_HD);
        assertThat(CreditType.TAILSONALITY.family()).isEqualTo(CreditFamily.TAILSONALITY);
        assertThat(CreditType.GIFT_1K.family()).isEqualTo(CreditFamily.GIFT_1K);
    }

    @Test
    void ktpTemplatesAreEightWithoutAny() {
        assertThat(CreditType.ktpTemplates()).containsExactly(CreditType.KTP_HD_KTP, CreditType.KTP_HD_PASSPORT,
                CreditType.KTP_HD_KITAS, CreditType.KTP_HD_SIM, CreditType.KTP_HD_STUDENT_BINTANG,
                CreditType.KTP_HD_STUDENT_MALAM, CreditType.KTP_HD_STUDENT_CERIA, CreditType.KTP_HD_STUDENT_KLASIK);
    }
}
