package com.tailtopia.shared.logistics.biteship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.logistics.biteship.dto.BiteshipRate;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 1.1 · Biteship 桩的确定性输出。 */
class StubBiteshipClientTest {

    private final StubBiteshipClient stub = new StubBiteshipClient();

    private static BiteshipRatesRequest to(String dest) {
        return new BiteshipRatesRequest("00000", dest,
                List.of(new BiteshipRatesRequest.Item("n", 1L, 1, 1000, 20, 15, 10)));
    }

    @Test
    @DisplayName("默认两家：JNE 10,000 支持保价、SiCepat 12,000 不支持")
    void defaultTwoCouriers() {
        BiteshipRatesResult r = stub.rates(to("40115"));
        assertThat(r.serviceable()).isTrue();
        assertThat(r.rates()).containsExactly(
                new BiteshipRate("jne", "JNE", "reg", "Reguler", "2 - 3 days", "2 - 3", "days",
                        10_000L, true),
                new BiteshipRate("sicepat", "SiCepat", "reg", "REG", "1 - 2 days", "1 - 2", "days",
                        12_000L, false));
        assertThat(stub.providerMode()).isEqualTo("STUB");
    }

    @Test
    @DisplayName("99999 → 不可配送（40001010）")
    void noCourier() {
        BiteshipRatesResult r = stub.rates(to("99999"));
        assertThat(r.serviceable()).isFalse();
        assertThat(r.noCourierCode()).isEqualTo(40001010);
    }

    @Test
    @DisplayName("99998 → 抛 SERVER 500")
    void failing() {
        assertThatThrownBy(() -> stub.rates(to("99998")))
                .isInstanceOfSatisfying(BiteshipException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(BiteshipException.Kind.SERVER);
                    assertThat(e.getHttpStatus()).isEqualTo(500);
                });
    }
}
