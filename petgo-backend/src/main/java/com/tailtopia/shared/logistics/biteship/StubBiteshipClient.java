package com.tailtopia.shared.logistics.biteship;

import com.tailtopia.shared.logistics.biteship.BiteshipException.Kind;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRate;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 确定性、不出网的 Biteship 桩（v1.4.2 shop Story 1.1）：L0 / L1 / 云端 / App 联调造数用。
 *
 * <ul>
 *   <li>默认两家：JNE Reguler 10,000（支持保价）、SiCepat REG 12,000（不支持保价）；</li>
 *   <li>目的邮编 {@code 99999} → 不可配送（{@code 40001010}）；{@code 99998} → 抛 SERVER 500；</li>
 *   <li>不校验 key、不看发货地邮编。</li>
 * </ul>
 * 运单三方法为最小内存实现，Story 5.1 补测试钩子。
 */
public class StubBiteshipClient implements BiteshipClient {

    public static final String NO_COURIER_POSTAL = "99999";
    public static final String FAILING_POSTAL = "99998";

    static final List<BiteshipRate> DEFAULT_RATES = List.of(
            new BiteshipRate("jne", "JNE", "reg", "Reguler", "2 - 3 days", "2 - 3", "days",
                    10_000L, true),
            new BiteshipRate("sicepat", "SiCepat", "reg", "REG", "1 - 2 days", "1 - 2", "days",
                    12_000L, false));

    private static final Set<String> CANCELLABLE =
            Set.of("confirmed", "scheduled", "allocated", "picking_up");

    private final Map<String, BiteshipOrderSnapshot> orders = new ConcurrentHashMap<>();
    private final AtomicLong waybillSeq = new AtomicLong(1);

    @Override
    public String providerMode() {
        return "STUB";
    }

    @Override
    public BiteshipRatesResult rates(BiteshipRatesRequest req) {
        if (NO_COURIER_POSTAL.equals(req.destPostalCode())) {
            return BiteshipRatesResult.noCourier(40001010);
        }
        if (FAILING_POSTAL.equals(req.destPostalCode())) {
            throw new BiteshipException(Kind.SERVER, 500, null);
        }
        return BiteshipRatesResult.of(DEFAULT_RATES);
    }

    @Override
    public CreateOutcome createOrder(BiteshipCreateOrderRequest req) {
        String id = "stub_" + req.referenceId();
        BiteshipOrderSnapshot snap = new BiteshipOrderSnapshot(id, "confirmed",
                "STUBWB" + String.format("%010d", waybillSeq.getAndIncrement()), null,
                req.courierCompany(), req.courierType(), req.price(), null, Instant.now());
        orders.put(id, snap);
        return new CreateOutcome.Created(snap);
    }

    @Override
    public CancelOutcome cancelOrder(String biteshipOrderId, String reason) {
        BiteshipOrderSnapshot snap = orders.get(biteshipOrderId);
        if (snap == null) {
            return new CancelOutcome.Unknown("UNKNOWN_ORDER");
        }
        if (!CANCELLABLE.contains(snap.status())) {
            return new CancelOutcome.NotCancellable(snap.status());
        }
        orders.put(biteshipOrderId, new BiteshipOrderSnapshot(snap.id(), "cancelled",
                snap.waybillId(), snap.trackingId(), snap.courierCompany(), snap.courierType(),
                snap.price(), snap.link(), Instant.now()));
        return new CancelOutcome.Cancelled();
    }

    @Override
    public BiteshipOrderSnapshot getOrder(String biteshipOrderId) {
        BiteshipOrderSnapshot snap = orders.get(biteshipOrderId);
        if (snap == null) {
            throw new BiteshipException(Kind.BAD_REQUEST, 404, null);
        }
        return snap;
    }
}
