package com.tailtopia.shared.logistics.biteship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRate;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;
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

    // ---------- v1.4.2 shop Story 5.1：运单与测试钩子 ----------

    @Test
    @DisplayName("5.1：createOrder → stub_<ref>、STUBWB+10 位、confirmed、price=请求运费；重复 reference → 40002060")
    void stubCreateOrder() {
        CreateOutcome out = stub.createOrder(new BiteshipCreateOrderRequest("TOKO-1-1", "jne", "reg", 13_000L));
        assertThat(out).isInstanceOf(CreateOutcome.Created.class);
        var snap = ((CreateOutcome.Created) out).order();
        assertThat(snap.id()).isEqualTo("stub_TOKO-1-1");
        assertThat(snap.waybillId()).matches("^STUBWB\\d{10}$");
        assertThat(snap.status()).isEqualTo("confirmed");
        assertThat(snap.price()).isEqualTo(13_000L);
        assertThat(stub.createOrder(new BiteshipCreateOrderRequest("TOKO-1-1", "jne", "reg", 13_000L)))
                .isEqualTo(new CreateOutcome.Rejected("40002060", "reference_id already used"));
    }

    @Test
    @DisplayName("5.1：BiteshipStubScript 推进状态 / 制造失败；cancel 只对四个早期状态有效")
    void stubScript() {
        assertThat(new BiteshipConfig().biteshipStubScript(new UnavailableBiteshipClient()))
                .as("非桩模式没有测试钩子").isNull();
        BiteshipStubScript script = new BiteshipConfig().biteshipStubScript(stub);
        assertThat(script).isNotNull();
        stub.createOrder(new BiteshipCreateOrderRequest("R-1", "jne", "reg", 1L));
        script.setStatus("stub_R-1", "picked");
        assertThat(stub.getOrder("stub_R-1").status()).isEqualTo("picked");
        assertThat(stub.cancelOrder("stub_R-1", "others")).isEqualTo(new CancelOutcome.NotCancellable("picked"));

        stub.createOrder(new BiteshipCreateOrderRequest("R-2", "jne", "reg", 1L));
        assertThat(stub.cancelOrder("stub_R-2", "others")).isInstanceOf(CancelOutcome.Cancelled.class);
        assertThat(stub.getOrder("stub_R-2").status()).isEqualTo("cancelled");

        script.failNextCreate("40000001");
        assertThat(stub.createOrder(new BiteshipCreateOrderRequest("R-3", "jne", "reg", 1L)))
                .isInstanceOf(CreateOutcome.Rejected.class);
        script.timeoutNextCreate();
        assertThat(stub.createOrder(new BiteshipCreateOrderRequest("R-4", "jne", "reg", 1L)))
                .isInstanceOf(CreateOutcome.Unknown.class);
        assertThat(stub.createOrder(new BiteshipCreateOrderRequest("R-5", "jne", "reg", 1L)))
                .isInstanceOf(CreateOutcome.Created.class);
        script.failNextGetOrder();
        assertThatThrownBy(() -> stub.getOrder("stub_R-5")).isInstanceOf(BiteshipException.class);
        assertThat(stub.getOrder("stub_R-5").status()).isEqualTo("confirmed");
    }
}
